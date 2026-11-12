package com.rxinfiles.tool

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 目录读取与文件信息工具。
 *
 * 与原实现保持一致的两条路径：
 *  - [listWithShell]：通过 shell 执行 `ls -la` 解析（root / adb 级别可用，能看到系统目录）
 *  - [listWithJava]：通过 Java 文件 API 列举（无特权时降级使用）
 */
object FileRepository {

    private const val TAG = "FileRepository"

    private val lineRegex = Regex("[dl-].*")
    private val splitRegex = Regex("\\s+")

    /**
     * `ls -la` 时间列的格式。
     *
     * **不能拿 `SimpleDateFormat` 当字段**：它是有状态的（内部一个共享的 `Calendar`），
     * 而 `FileRepository` 是**单例**、[listWithShell] 又跑在 `Dispatchers.IO` 上 ——
     * 快速连点目录时，上一个协程被 `cancel()` 后**不会立刻停**（取消是协作式的，
     * 这条链路上没有挂起点），于是两个线程可能同时用同一个实例。
     * 症状很隐蔽：**解析结果错乱**（列表里某个文件的时间变成 1970、或串成另一个文件的时间），
     * 而不是抛异常 —— [parseLsTime] 的 `try` 兜不住这种情况。
     *
     * `DateTimeFormatter` 是不可变的，天然线程安全。
     */
    private val lsTimeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.US)

    /** 统一入口：优先使用 shell，失败则降级 Java */
    fun listDir(path: String): List<FileItem> {
        val raw = if (ShellExecutor.hasShell) {
            listWithShell(path) ?: listWithJava(path)
        } else {
            listWithJava(path)
        }
        return applySort(raw)
    }

    // ------------------------------------------------------------- 排序

    /**
     * 当前排序方式，由 [MainActivity] 读偏好后写入。
     *
     * **放在仓库层而不是各调用点**，是因为两条通道必须用**同一套**排序：
     * 此前排序只写在 [listWithJava] 里，shell 通道直接沿用 `ls -la` 的输出顺序
     * —— 于是「普通身份」与「ROOT / ADB 身份」看到的排列不一样，
     * 而这条路只有换身份才看得出来。
     */
    var sortKey: FileSort.Key = FileSort.DEFAULT_KEY
    var sortOrder: FileSort.Order = FileSort.DEFAULT_ORDER
    var sortDirsFirst: Boolean = FileSort.DEFAULT_DIRS_FIRST

    /**
     * 应用当前排序。
     *
     * `..` 摘出来**永远钉在最前**：它是「回上级」这个动作的入口，
     * 不是一个该被按大小 / 时间排来排去的条目。
     */
    private fun applySort(items: List<FileItem>): List<FileItem> {
        if (items.size < 2) return items
        val hasParent = items[0].name == ".."
        val body = if (hasParent) items.subList(1, items.size) else items
        val sorted = body.sortedWith { a, b ->
            FileSort.compare(
                a.name, a.isDirectory, a.size, a.lastModified,
                b.name, b.isDirectory, b.size, b.lastModified,
                sortKey, sortOrder, sortDirsFirst
            )
        }
        return if (hasParent) listOf(items[0]) + sorted else sorted
    }

    /**
     * 一次列目录返回的结果：列表 + 「这个目录是真的读不出来吗」。
     *
     * <h2>为什么要把判定和列表绑在一起返回</h2>
     *
     * 判「真的读不出来」**不能看列表项数** —— [listWithJava] 先塞 `..` 再列，
     * `listFiles()` 返回 null 时直接 `?: return result`，所以「读不出来」拿到的是
     * **只含 `..` 的非空列表**，`isEmpty()` 恒假。要分清真伪必须显式再探一次
     * （[probeListJava] / [probeListShell]）。
     *
     * 这两件事（列出 + 探测）在同一个 IO 线程里连着做完，比让调用方拿着列表再回
     * IO 线程探一次少一次往返，也避免两次调用之间目录状态变化导致的结论不一致。
     *
     * <h2>什么时候才值得探</h2>
     *
     * 探测本身有成本（Java 通道失败时还要 `Os.open` 问 errno）。所以**只在
     * {@link IncomingFile#isUnderAndroidData} 为真、且当前没有 shell 通道时才探** ——
     * 全工程只有这一个场景需要这个答案（见 {@link IncomingFile#shouldWarnNoAccess}）；
     * 其余路径里这个字段恒为 false，零额外开销。
     *
     * @return [Checked]：`items` 与 [listDir] 完全一致；`noAccess` 为真表示
     *         「确实位于 Android/data 且当前身份读不出来」
     */
    fun listDirChecked(path: String): Checked {
        val items = listDir(path)
        // 两个前置条件先廉价判掉，避免为绝大多数目录白付一次探测
        if (!IncomingFile.isUnderAndroidData(path) || ShellExecutor.hasShell) {
            return Checked(items, false)
        }
        // 到这儿说明：路径在 Android/data 下，且当前是普通身份（无 shell 通道）
        val probe = probeListJava(path)
        return Checked(
            items,
            IncomingFile.shouldWarnNoAccess(path, ShellExecutor.level.label, probe.isOk)
        )
    }

    /** [listDirChecked] 的返回值。 */
    data class Checked(val items: List<FileItem>, val noAccess: Boolean)

    // ------------------------------------------------------------ shell 路径

    /**
     * 解析 `ls -la` 输出。
     * 复刻原实现的规则：跳过 total 行、正则 [dl-] 开头的行、\s+ 限 8 段切分、
     * 取末段为文件名并剥离 " -> " 链接目标、跳过 . 与 ..
     */
    private fun listWithShell(path: String): List<FileItem>? {
        val escaped = path.replace("'", "'\\''")
        val output = ShellExecutor.exec("ls -la '$escaped'") ?: return null
        // 命令没有输出且父目录不可判定时，视为失败并降级
        if (output.isBlank()) return null

        val result = mutableListOf<FileItem>()

        File(path).parent?.let { parent ->
            result.add(FileItem("..", parent, true, false, 0L, 0L))
        }

        for (raw in output.lines()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("total ")) continue
            if (!lineRegex.matches(line)) continue

            val parts = splitRegex.split(line, limit = 8)
            if (parts.size < 8) continue

            val perms = parts[0]
            val isLink = perms.startsWith("l")
            val name = if (isLink) parts[7].substringBefore(" -> ") else parts[7]
            if (name == "." || name == "..") continue

            val isDirByPerm = perms.startsWith("d")
            val size = parts[4].toLongOrNull() ?: 0L
            val fullPath = joinPath(path, name)

            // 符号链接需要判断指向目标的真实类型
            val isDirectory = when {
                isDirByPerm -> true
                isLink -> File(fullPath).isDirectory
                else -> false
            }

            val lastModified = parseLsTime(parts[5], parts[6])
                .takeIf { it > 0L } ?: File(fullPath).lastModified()

            result.add(
                FileItem(
                    name = name,
                    path = fullPath,
                    isDirectory = isDirectory,
                    isScript = isScriptName(name),
                    size = if (isDirectory) 0L else size,
                    lastModified = lastModified
                )
            )
        }
        return result
    }

    // ------------------------------------------------------------- java 路径

    private fun listWithJava(path: String): List<FileItem> {
        val result = mutableListOf<FileItem>()
        val dir = File(path)

        dir.parent?.let { parent ->
            result.add(FileItem("..", parent, true, false, 0L, 0L))
        }

        val children = try {
            dir.listFiles()
        } catch (t: Throwable) {
            Log.w(TAG, "listFiles failed: $path", t)
            null
        } ?: return result

        children
            .filter { it.name != "." && it.name != ".." }
            // 排序不在这里做 —— 两条通道共用 [listDir] 里的 applySort，
            // 免得又变成「只有普通身份排了序」。
            .forEach { f ->
                result.add(
                    FileItem(
                        name = f.name,
                        path = f.absolutePath,
                        isDirectory = f.isDirectory,
                        isScript = isScriptName(f.name),
                        size = if (f.isDirectory) 0L else f.length(),
                        lastModified = f.lastModified()
                    )
                )
            }
        return result
    }

    // ------------------------------------------------------------------ 工具

    fun joinPath(dir: String, name: String): String =
        if (dir.endsWith("/")) "$dir$name" else "$dir/$name"

    /**
     * 解析 `ls -la` 的时间列（`2026-10-11 10:30`），失败一律给 0（调用方按「未知时间」显示）。
     *
     * 时区取**本地时区** —— 与原来 `SimpleDateFormat` 的默认行为一致，别改成 UTC：
     * `ls` 打的就是本地时间，用 UTC 解析会让整列时间偏移若干小时。
     */
    private fun parseLsTime(date: String, time: String): Long = try {
        LocalDateTime.parse("$date $time", lsTimeFormat)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
    } catch (t: Throwable) {
        0L
    }

    /** 是否为可执行脚本 */
    fun isScriptName(name: String): Boolean = IncomingFile.isScriptName(name)

    // ------------------------------------------------------------ 类型判定

    /**
     * 判定顺序在本文件里是统一的：图片 → 视频 → 归档 → 脚本 → 文本 → 媒体 → 其它。
     *
     * `.js` 这类「既能执行也能看」的扩展名被两边都收录，是否冲突由调用方
     * 决定（MainActivity 会弹一个「打开方式」让用户选），这里只负责如实回答。
     *
     * **判据本体已经搬到 [IncomingFile]（纯 Java）。** 从外部应用「用其他应用打开」
     * 进来的文件也要用同一套判据分派，而那条链路必须能脱离 Android 跑断言
     * （见 tools/incoming-check）—— 纯 Java 类没法反过来调这个 Kotlin object。
     * 与其把扩展名表抄成两份等着它们慢慢跑偏，不如让这里全部转发过去：
     * 全工程只剩一份表，verify-incoming.py 再把归档那张表钉死等于 [Archives] 的。
     */

    /** 图片：交给内置图片查看器 */
    fun isImageName(name: String): Boolean = IncomingFile.isImageName(name)

    /** 文本：交给内置文本浏览器 */
    fun isTextName(name: String): Boolean = IncomingFile.isTextName(name)

    /** 视频：交给内置视频播放器 */
    fun isVideoName(name: String): Boolean = IncomingFile.isVideoName(name)

    fun isMediaName(name: String): Boolean = IncomingFile.isMediaName(name)

    /** 压缩包：交给归档面板 */
    fun isArchiveName(name: String): Boolean = IncomingFile.isArchiveName(name)

    /**
     * 「一定要执行」的脚本：shell 家族。
     * 点击直接进终端 —— 这类文件用文本浏览器看没有意义。
     */
    fun isStrictScriptName(name: String): Boolean = IncomingFile.isStrictScriptName(name)

    /**
     * 「两可」的脚本：既能当程序跑，也能当文本看（.py/.js/.pl/.rb/.lua）。
     * 点击时弹一个「打开方式」让用户选，而不是替他决定。
     */
    fun isAmbiguousScriptName(name: String): Boolean = IncomingFile.isAmbiguousScriptName(name)

    /** 列出某目录下的图片（供图片查看器左右翻页用），按名称排序 */
    fun listImages(dir: String): List<File> {
        val files = try {
            File(dir).listFiles()
        } catch (t: Throwable) {
            null
        } ?: return emptyList()
        return files
            .filter { it.isFile && isImageName(it.name) }
            .sortedBy { it.name.lowercase(Locale.US) }
    }

    /**
     * 内容嗅探：不认识扩展名时，看看前 4KB 像不像文本。
     *
     * 判据很保守 —— 出现 NUL 字节就判为二进制（文本文件几乎不会含 NUL），
     * 否则要求可打印字符占比够高。宁可漏判也不要误判：
     * 把一个二进制文件按文本打开，用户看到的是满屏乱码。
     *
     * 实现同样转发给 [IncomingFile.looksLikeText]：外部「用其他应用打开」进来的
     * 未知类型文件用的是同一条判据，且那条判据能离线断言。
     */
    fun looksLikeText(file: File): Boolean = IncomingFile.looksLikeText(file)

    /** 人类可读的类型描述 */
    fun describeType(name: String, isDirectory: Boolean): String = when {
        isDirectory -> "目录"
        isImageName(name) -> "图片"
        isVideoName(name) -> "视频"
        isArchiveName(name) -> "压缩包"
        isStrictScriptName(name) || isAmbiguousScriptName(name) -> "脚本"
        isTextName(name) -> "文本"
        isMediaName(name) -> "媒体"
        else -> "文件"
    }

    fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
        else -> String.format(Locale.US, "%.2f GB", bytes / 1024.0 / 1024.0 / 1024.0)
    }

    fun formatTime(millis: Long): String {
        if (millis <= 0L) return ""
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(java.util.Date(millis))
    }

    /** 判断路径是否可读，用于降级提示 */
    fun canRead(path: String): Boolean = try {
        File(path).canRead()
    } catch (t: Throwable) {
        false
    }

    // ------------------------------------------------- 外部「用其他应用打开」入口用

    /**
     * 当前 shell 身份的展示标签（ROOT / ADB / 普通）。
     *
     * 供「用其他应用打开」入口把身份写进诊断信息 —— 那一侧**刻意不允许**直接引用
     * `ShellExecutor`（可信边界断言，见 tools/incoming-check/verify-incoming.py 第 6 节：
     * 那个入口绝不卷入执行链路），所以身份只从这里转发出去。
     */
    fun shellLevelLabel(): String = ShellExecutor.level.label

    /**
     * 运行（幂等的）权限探测并返回展示标签。会执行外部进程，必须**在 IO 线程调用**。
     *
     * 放在这里而不是入口里：入口要按「真正去读它的那个身份」分流（可见性判定、搜索通道
     * 都看它），所以探测必须发生在任何读动作之前，而探测本身是执行链路，收在这一层。
     */
    fun detectShellLevel(): String {
        ShellExecutor.detect()
        return ShellExecutor.level.label
    }

    /**
     * 内置 busybox 的绝对路径 —— 搜索时用作**首选** timeout（系统 `timeout` 只是次选）。
     *
     * 为什么优先内置：本工程随包内置了 busybox（[RxinToolbox]），它的 `timeout` 一定在；
     * 而系统 toybox 的 `timeout` 各 ROM 不一定有，缺了会让搜索静默退化。
     * 必须在 IO 线程调用（首次会做一次 exec 探测）；不可用时返回 null，调用方退回系统 `timeout`。
     */
    fun busyboxTimeoutPath(context: Context): String? = try {
        RxinToolbox.ensure(context)
        RxinToolbox.busybox
    } catch (t: Throwable) {
        Log.w(TAG, "busybox path unavailable", t)
        null
    }

    private const val JAVA_SEARCH_MAX_DIRS = 20000

    /**
     * 按「文件名（逐字符）+ 大小（逐字节）」在 [dirs] 下找文件，返回**候选 + 结局**。
     *
     * <h2>按身份分流（与 [listDir] 同一条原则）</h2>
     *
     * 有 shell（ROOT / Shizuku）就走 `find` —— 那两个身份能进 `Android/data`，
     * QQ 收到的文件常在那里。没有 shell（普通身份）才退回 Java 遍历。判据一律按
     * **真正去执行它的那个身份**来，而不是拿 App 自己的结论顶替。
     *
     * <h2>返回的是候选，不是结论；失败也不静默</h2>
     *
     * 命中只说明「这个身份能用一个 find 看到它」，**不代表它就是原文件**。调用方必须再用
     * [listDir] 按同一身份复核可见性与真实大小（见 `IncomingFile.matchesSearchHit`）。
     * 结局（正常 / 超时 / 无 timeout 工具 / 无输出 / 无 shell）一并带出，免得「命令没跑」
     * 和「跑完没命中」在上层看起来一样。
     *
     * @param dirs           要搜的目录（绝对路径）
     * @param name           目标文件名（应与显示名逐字符相同）
     * @param size           目标大小（字节）；负值表示不确定 —— 那就什么都不搜
     * @param busyboxPath    内置 busybox 的绝对路径（首选 timeout）；null 表示退回系统 timeout
     * @param timeoutSeconds shell 侧超时；<=0 时命令内按 1 秒处理
     */
    fun searchByNameAndSize(
        dirs: List<String>,
        name: String,
        size: Long,
        busyboxPath: String?,
        timeoutSeconds: Int
    ): IncomingFile.SearchOutcome {
        if (dirs.isEmpty() || name.isEmpty() || size < 0L) {
            return IncomingFile.SearchOutcome(
                emptyList(), IncomingFile.SearchStatus.OK, "参数不完整，未执行"
            )
        }
        if (ShellExecutor.hasShell) {
            val cmd = IncomingFile.buildSearchCommand(dirs, name, size, busyboxPath, timeoutSeconds)
            val out = try {
                ShellExecutor.exec(cmd)
            } catch (t: Throwable) {
                Log.w(TAG, "searchByNameAndSize failed: $cmd", t)
                null
            }
            val status = IncomingFile.parseSearchStatus(out)
            return IncomingFile.SearchOutcome(IncomingFile.parseSearchOutput(out), status, "")
        }
        val paths = searchWithJava(dirs, name, size, timeoutSeconds)
        return IncomingFile.SearchOutcome(paths, IncomingFile.SearchStatus.NO_SHELL, "")
    }

    /**
     * 普通身份下的兜底搜索：没有 `find` 可用，只能自己遍历。
     *
     * **必须限时 + 限量**：`/sdcard` 下可能有几十万文件，不限就会把调用方的 IO 线程
     * 占住几分钟。到点或到量就停，返回已经找到的部分。
     */
    private fun searchWithJava(
        dirs: List<String>,
        name: String,
        size: Long,
        timeoutSeconds: Int
    ): List<String> {
        val budget = if (timeoutSeconds > 0) timeoutSeconds else 5
        val deadline = System.currentTimeMillis() + budget * 1000L
        val out = mutableListOf<String>()
        val stack = java.util.ArrayDeque<File>()
        dirs.map { File(it) }.filter { it.isDirectory }.forEach { stack.addLast(it) }
        var visited = 0
        while (stack.isNotEmpty()) {
            if (visited >= JAVA_SEARCH_MAX_DIRS || System.currentTimeMillis() > deadline) break
            val dir = stack.removeLast()
            visited++
            val children = try {
                dir.listFiles()
            } catch (t: Throwable) {
                null
            } ?: continue
            for (child in children) {
                if (child.isDirectory) {
                    stack.addLast(child)
                } else if (child.name == name && child.length() == size) {
                    out.add(child.absolutePath)
                }
            }
        }
        return out
    }

    // ------------------------------------------------- 目标目录可访问性实测（诊断用）

    /**
     * **Java 通道**列目录实测（诊断用）：能不能列、能列几项、失败时为什么。
     *
     * 走 `File.listFiles()` —— 这正是 `targetSdk = 34`（Scoped Storage）下会被拒的那条路。
     * 失败时尽力把内核给的原因问出来（放进 [describeJavaListFailure]），因为「到底是 EACCES
     * 还是路径不存在」直接决定了下一步该走哪条路。
     */
    fun probeListJava(path: String): IncomingFile.ListProbe {
        val dir = File(path)
        return try {
            val children = dir.listFiles()
            if (children != null) {
                IncomingFile.ListProbe(true, children.size, "")
            } else {
                IncomingFile.ListProbe(false, 0, describeJavaListFailure(dir))
            }
        } catch (t: Throwable) {
            IncomingFile.ListProbe(false, 0, t.javaClass.simpleName + ": " + (t.message ?: ""))
        }
    }

    /**
     * 尽力问出「为什么 `listFiles()` 返回了 null」。
     *
     * Java 的 `File` API **不暴露 errno**，所以这里用 `Os.open(O_RDONLY)` 自己问内核要一个：
     * 能打开说明读权限是有的（那 `listFiles()=null` 多半另有原因），打不开就带回 errno
     * （Scoped Storage 典型是 EACCES / EPERM）。再附上三个文件属性位
     * （exists / isDirectory / canRead）作旁证。**只读探测，不改任何东西。**
     *
     * 注：这里**不能**加 `O_DIRECTORY` —— Android 的 `OsConstants` 并没有导出这个常量
     * （Linux 有、AOSP 的 java 层没暴露）。好在以 `O_RDONLY` 打开一个目录本来就合法，
     * errno 的语义（EACCES/EPERM）与加不加 `O_DIRECTORY` 无关。
     */
    private fun describeJavaListFailure(dir: File): String = buildString {
        append("listFiles()=null")
        append(" exists=").append(dir.exists())
        append(" isDir=").append(dir.isDirectory)
        append(" canRead=").append(dir.canRead())
        try {
            val fd = android.system.Os.open(
                dir.absolutePath, android.system.OsConstants.O_RDONLY, 0
            )
            try {
                append(" openDir=ok")
            } finally {
                runCatching { android.system.Os.close(fd) }
            }
        } catch (e: android.system.ErrnoException) {
            append(" openDir errno=").append(e.errno)
        } catch (t: Throwable) {
            append(" openDir=").append(t.javaClass.simpleName)
        }
    }

    /**
     * **shell 通道**列目录实测（诊断用）：走 [ShellExecutor] 的 `ls -la`。
     *
     * 与 [probeListJava] 一一对应，好让诊断里能直接比出「两条通道结论是否一致」——
     * 若 Java 列不出而 shell 能列出，答案就是「改走 shell 通道」。
     * 没有 shell 通道时如实说明，而不是伪造成「失败」。
     */
    fun probeListShell(path: String): IncomingFile.ListProbe {
        if (!ShellExecutor.hasShell) {
            return IncomingFile.ListProbe(false, 0, "无 shell 通道（当前身份=普通）")
        }
        val out = try {
            ShellExecutor.exec("ls -la " + IncomingFile.quoteShell(path))
        } catch (t: Throwable) {
            Log.w(TAG, "probeListShell failed: $path", t)
            null
        } ?: return IncomingFile.ListProbe(false, 0, "命令执行失败（无输出）")

        // `ls -la` 成功时一定带 "." / ".." 两行；据此判「列得出来」，再扣掉伪条目报真实条数
        val entryNames = out.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("total ") && lineRegex.matches(it) }
            .mapNotNull { line ->
                val parts = line.split(Regex("\\s+"), limit = 8)
                if (parts.size < 8) {
                    null
                } else if (parts[0].startsWith("l")) {
                    parts[7].substringBefore(" -> ")
                } else {
                    parts[7]
                }
            }
            .toList()
        if (entryNames.isNotEmpty()) {
            return IncomingFile.ListProbe(true, entryNames.count { it != "." && it != ".." }, "")
        }
        // 一条像样的行都没有：把 shell 的话术如实带出来（Permission denied / No such file …）
        val noise = out.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: "无输出"
        return IncomingFile.ListProbe(false, 0, noise)
    }
}
