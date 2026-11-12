package com.rxinfiles.tool

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.OpenableColumns
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * 「用其他应用打开」的入口。只做一件事：**把用户带到那个文件面前** ——
 * 定位它所在的目录并高亮那一行。
 *
 * <h2>为什么不是「按类型打开查看器」</h2>
 *
 * 内置查看器全都只认**真实文件路径**，而外部应用递过来的是 `content://` URI。
 * 与其把内容复制出来再挑一个查看器（用户看到的其实是副本，还不是原生体验），
 * 不如直接把他带到**原文件所在目录**：一眼能看清它在哪、周围还有什么。
 *
 * <h2>四条路，按优先级</h2>
 * <ol>
 *   <li><b>能反解出原路径</b> → 直接让主界面导航过去高亮。**零拷贝、零开销。**</li>
 *   <li><b>反解不出来</b> → **定向目录查找**（本轮新增）：QQ / 微信收到的文件落在**固定、
 *       已知**的目录（用户真机已确认 `Android/data/com.tencent.mobileqq/Tencent/QQfile_recv/`），
 *       不再猜 URI —— 直接用 [FileRepository.listDir] 把这些目录列出来，按「显示名 + 大小」匹配。
 *       比全盘 `find` **更快**（只扫一两个目录）、**更可靠**（不依赖 `timeout` / `find`，
 *       用的是与可见性核对同一条通道）。</li>
 *   <li><b>还没找到</b> → 按「显示名 + 大小」在存储里**搜索**（先已知目录、再限时全盘）。</li>
 *   <li><b>仍找不到</b> → 复制到 `/sdcard/Download/` 再高亮，并**明说这是副本** ——
 *       绝不能让用户误以为他看到的就是原文件。这一路还会弹诊断面板，把「为什么没定位到」
 *       交代清楚（含定向查找与目录可访问性实测）。</li>
 * </ol>
 *
 * <h2>可信边界（这个 Activity 是 exported="true"）</h2>
 *
 * 它必须能被任何 App 唤起，代价是入参完全由对方控制。因此：
 * <ul>
 *   <li>只认 scheme 为 `content` / `file` 的 data；</li>
 *   <li>除 data 之外**不信任何 extra**；</li>
 *   <li>反解出的路径必须**存在且文件名与 DISPLAY_NAME 相符**才认（判据在
 *       [IncomingFile.matchesDisplayName]）—— **宁可判失败，也不猜一个像的路径**，
 *       猜错会把用户带去另一个文件，比不定位更糟；</li>
 *   <li>搜索命中同理：名字逐字符相符 **且 大小逐字节相同** 才认（[IncomingFile.matchesSearchHit]），
 *       而且仍要走一次可见性核对 —— 「搜到了」不等于「就是它」；</li>
 *   <li>可见性用 [FileRepository.listDir]（它会按**真正去读它的身份**分流 shell / Java）
 *       判断，**不用 `File.canRead()`** —— ROOT/Shizuku 身份能进 `Android/data`，普通身份进不去；</li>
 *   <li>文件名进 shell 命令（搜索）前一律经 [IncomingFile.quoteShell] 转义；</li>
 *   <li>**不执行任何脚本，也不打开那个文件本身。** 唯一跑起来的命令是一次**限时**的 `find`。</li>
 * </ul>
 */
class IncomingActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_incoming)

        val uri = resolveIncomingUri(intent)
        if (uri == null) {
            failAndFinish("没有收到要打开的文件")
            return
        }
        // scheme 是对方唯一伪造不了的字段 —— 先把不合格的挡在门外
        if (!IncomingFile.isHandledScheme(uri.scheme)) {
            failAndFinish("不支持打开这种位置的文件（${uri.scheme ?: "未知"}）")
            return
        }

        lifecycleScope.launch {
            val meta = withContext(Dispatchers.IO) { queryMeta(uri) }
            findViewById<TextView>(R.id.incomingStatus).text = meta.displayName.orEmpty()

            // 反解/搜索/复制都必须在收到文件的第一时间做：发送方授予的临时读权限
            // 绑在这个 Intent 上，Activity 一结束就可能失效。
            //
            // 注意这里**刻意不预先探测 shell 身份**：最常见的结果是「反解成功」，
            // 那条路不需要身份；身份只在真要搜索或真要写诊断时才惰性补一次
            // （见 ensureShellLevel），免得每次外部打开都白付一次 su/Shizuku 探测。
            val outcome = withContext(Dispatchers.IO) { locate(uri, meta) }
            when (outcome) {
                is Located.Original -> revealTo(outcome.path, outcome.note)
                is Located.Unresolved -> showUnresolvedSheet(outcome)
            }
        }
    }

    // ------------------------------------------------------------- 惰性身份探测

    /** 已探到的 shell 身份标签；null 表示还没探过。只在 IO 线程读写。 */
    private var detectedLevel: String? = null

    /**
     * 按需探测一次 shell 身份，之后缓存。
     *
     * <h2>为什么要惰性而不是在 onCreate 里无条件探（3.15.0 修正）</h2>
     *
     * 「外部打开」最常见的结果是**反解成功**，那条路上压根不需要知道身份。若在 onCreate
     * 无条件探测，等于**每次打开都执行一次外部进程**（`su` / Shizuku），有 root 的机器
     * 还可能弹授权框 —— 为一个多数时候用不到的字段付这个代价不合理。
     *
     * 所以身份只在**真正要用它的两处**补齐：
     * <ol>
     *   <li>要发起**搜索**之前 —— 搜索通道按身份分流（[FileRepository.searchByNameAndSize]
     *       看 `ShellExecutor.hasShell`），不探就会拿默认值走错通道；</li>
     *   <li>要**渲染诊断**之前 —— 诊断里那格「Shell身份」要用已探到的值，若走到这里还没探过
     *       就按需补一次。</li>
     * </ol>
     * **反解成功的路径因此一次探测都不发生。**
     *
     * 必须在 IO 线程调用（会执行外部进程）。
     */
    private fun ensureShellLevel(): String {
        detectedLevel?.let { return it }
        val label = runCatching { FileRepository.detectShellLevel() }
            .getOrDefault(FileRepository.shellLevelLabel())
        detectedLevel = label
        return label
    }

    // ------------------------------------------------------------- 定位

    private sealed class Located {
        /** 定位到了文件的真实路径（反解得到，或按名+大小搜到） */
        data class Original(val path: String, val note: String?) : Located()

        /**
         * 定位不到原位置。报告里写清了「为什么」；[copiedPath] 非空表示内容已复制到
         * `Download/RxinDownload/`（见 [IncomingFile.COPY_DIR_NAME]）。
         *
         * [shellLevel] 是这一轮探到的身份标签 —— 面板据此决定要不要给「装 Shizuku」的提示
         * （判据见 [IncomingFile.needsShellHint]）。**存在这里而不是让面板再去探一次**：
         * 面板是纯 UI，不该执行外部进程；而且重探会与诊断正文里那格「Shell身份」可能不一致。
         */
        data class Unresolved(
            val copiedPath: String?,
            val report: String,
            val shellLevel: String
        ) : Located()
    }

    /**
     * 四级定位：① 反解原路径 → ② 定向目录查找 → ③ 按名+大小搜 → ④ 复制兜底。
     *
     * 反解与定位都失败时，不再只丢一句 toast，而是把整条推理过程（诊断）带出去给面板展示。
     *
     * **身份探测是惰性的**：这里先做①（不探测），失败才进②（此时 [directedLookup] 内探一次），
     * 再进③④。因此**反解成功 = 零探测**。
     */
    private fun locate(uri: Uri, meta: Meta): Located {
        val attempts = mutableListOf<String>()
        val searchNotes = mutableListOf<String>()

        // ① 先尽力反解原路径（零拷贝）。这一路不探测身份：反解成功就到此为止。
        locateOriginal(uri, meta.displayName, attempts)?.let { return Located.Original(it, null) }

        // ② 定向目录查找：QQ/微信收到的文件落在固定、已知的目录，直接列出来按名+大小找。
        //    比全盘 find 更快、更可靠（不依赖 timeout/find，用与可见性核对同一条通道）。
        directedLookup(meta, attempts, searchNotes)?.let {
            return Located.Original(it, "已在 QQ/微信 下载目录定位到原文件")
        }

        // ③ 仍没命中：按「显示名 + 大小」在存储里搜（先已知目录、再限时全盘）
        searchOriginal(meta, attempts, searchNotes)?.let {
            return Located.Original(it, "已按文件名与大小定位到原文件")
        }

        // ④ 仍找不到：复制兜底，并把诊断信息交给面板（不再只是一句 toast）
        val copy = copyToDownload(uri, meta.displayName)
        val copyResult = if (copy != null) {
            "已复制一份到 Download/${IncomingFile.COPY_DIR_NAME}：${copy.absolutePath}"
        } else {
            "复制也失败：既定位不到原位置，也无法读取内容"
        }
        // 走到这里才需要身份：若前面搜过则已探过，没搜过（大小/名字缺失）则按需补一次
        val level = ensureShellLevel()
        // 本轮诊断重点：实测我们对关键目录到底能不能列出（Java vs shell 双通道）
        val accessReport = IncomingFile.formatAccessReport(probeTargetDirs())
        val report = IncomingFile.formatDiagnostics(
            IncomingFile.Diagnostics(
                uri.toString(),
                uri.scheme.orEmpty(),
                uri.authority.orEmpty(),
                uri.path.orEmpty(),
                uri.pathSegments ?: emptyList(),
                meta.displayName.orEmpty(),
                meta.size,
                mimeOf(uri),
                level,
                attempts,
                searchNotes,
                accessReport,
                copyResult
            )
        )
        Log.i(TAG, "未能定位源文件，已转为复制兜底。诊断:\n$report")
        return Located.Unresolved(copy?.absolutePath, report, level)
    }

    /**
     * 目标目录可访问性实测：对若干关键目录逐个做 **Java / shell 双通道**列举测试。
     *
     * <h2>为什么这一节是重点</h2>
     *
     * 用户真机反馈「**MT 管理器不借助 adb / root 也能访问 `Android/data`**」—— 这说明目标
     * 很可能是**可达的**，只是我们走的路不对。实测结果会同时回答两件事：
     * <ol>
     *   <li>是「路径没反解出来」还是「我们真的访问不到」；</li>
     *   <li>**Java 通道与 shell 通道的差异** —— 若 Java 列不出而 shell 能列出，答案就是
     *       「改走 shell 通道」，而不是去动访问策略。</li>
     * </ol>
     * 只在**定位失败**时跑（它是诊断的一部分），成功路径零开销。
     */
    private fun probeTargetDirs(): List<IncomingFile.DirProbe> {
        val root = Environment.getExternalStorageDirectory().absolutePath
        return IncomingFile.probeDirs(root).map { dir ->
            IncomingFile.DirProbe(
                dir,
                FileRepository.probeListJava(dir),
                FileRepository.probeListShell(dir)
            )
        }
    }

    /**
     * 尽力把 URI 反解成**原文件**的真实路径；反解不出返回 null。
     *
     * 「反解」本质是猜，所以每一条候选都必须过两道闸：文件名与显示名相符、且以
     * 我们的身份**列得出来**。任一条不过就换下一条，并把**被拒的原因逐条记进 [attempts]**
     * —— 下一轮排错就靠它。
     */
    private fun locateOriginal(uri: Uri, displayName: String?, attempts: MutableList<String>): String? {
        val fileScheme = "file".equals(uri.scheme, ignoreCase = true)
        val candidates = candidatePaths(uri)
        if (candidates.isEmpty()) {
            attempts.add("（没有任何可反解的候选路径：provider 未给出 _data 列，path 段也拼不出可用路径）")
        }
        for (candidate in candidates) {
            // file:// 的 path 本身就是权威路径，不需要再用显示名校对；
            // content:// 反解出来的则必须核对，否则就是「猜一个像的路径」。
            if (!fileScheme && !IncomingFile.matchesDisplayName(displayName, candidate)) {
                attempts.add("$candidate → 文件名与显示名不符")
                continue
            }
            if (isVisible(candidate)) {
                return candidate
            }
            attempts.add("$candidate → 以当前身份列不出该文件（目录不可读或不存在）")
        }
        return null
    }

    /**
     * 反解失败后的**定向目录查找**：把 QQ / 微信收到文件的**固定目录**直接列出来，
     * 按「显示名 + 大小」在里面找。
     *
     * <h2>为什么放在全盘搜索之前</h2>
     *
     * 用户真机确认了 QQ 的下载目录是固定、已知的（`Android/data/com.tencent.mobileqq/
     * Tencent/QQfile_recv/`），于是**不用再猜 URI**：直接列目录比全盘 `find` **更快**
     * （只扫一两个目录）、**更可靠**（用 [FileRepository.listDir]，它会按**真实身份**
     * 自动选 shell / Java 通道 —— 普通身份进不去的 `Android/data`，root/Shizuku 身份
     * 能用 `ls` 列出来；而且**不依赖 `timeout` / `find` 是否存在**）。
     *
     * 匹配仍是**精确**的：名字逐字符相同 **且** 大小逐字节相同（[IncomingFile.matchesDirectedHit]）。
     * 命中的条目再按既有「可见性校验」复核一次才敢用。过程逐条记进 [notes]，进诊断面板。
     */
    private fun directedLookup(
        meta: Meta,
        attempts: MutableList<String>,
        notes: MutableList<String>
    ): String? {
        val name = IncomingFile.lastSegment(meta.displayName)
        if (!IncomingFile.canSearchByNameAndSize(meta.displayName, meta.size)) {
            notes.add("定向查找跳过：显示名或大小缺失（名称=\"$name\"，大小=${meta.size}）")
            return null
        }
        // 列目录的通道按身份分流（root/Shizuku 才能用 shell 进 Android/data），所以先探一次身份。
        // 这也是「反解成功就不探测」的落点 —— 走到这里才探第一次。
        ensureShellLevel()
        val root = Environment.getExternalStorageDirectory().absolutePath
        for (dir in IncomingFile.directedLookupDirs(root)) {
            val entries = FileRepository.listDir(dir)
            // listDir 失败（目录不存在 / 列不出）时只剩 ".." 伪条目或为空，如实记录
            val real = entries.filter { it.name != ".." }
            notes.add(
                "定向查找 $dir：列出 ${real.size} 个条目（通道=${FileRepository.shellLevelLabel()}）"
            )
            for (entry in real) {
                if (entry.isDirectory || entry.name != name) {
                    continue
                }
                val matched = IncomingFile.matchesDirectedHit(
                    meta.displayName, entry.name, meta.size, entry.size
                )
                if (!matched) {
                    // 名字相符但大小不符：这正是「同名不同文件」，记进诊断供排查
                    notes.add(
                        "  · ${entry.name} 大小=${entry.size} → 名字相符但大小不符（期望 ${meta.size}）"
                    )
                    continue
                }
                notes.add("  · ${entry.name} 大小=${entry.size} → 名字与大小都相符")
                // 候选路径 = File(dir, name)，再走既有「可见性校验」这道闸
                val path = FileRepository.joinPath(dir, entry.name)
                if (findVisibleEntry(path) != null) {
                    return path
                }
                attempts.add("(定向) $path → 以当前身份列不出该文件")
            }
        }
        return null
    }

    /**
     * 反解失败后的补充策略：**按「显示名 + 大小」在存储里找**。
     *
     * 先搜已知的 QQ / 下载目录（快，且正是外部应用最常落盘的地方），再限时全盘搜一次
     * `/sdcard`（`Android/data` 只有 root / Shizuku 身份进得去 —— 走 `find` 才有戏）。
     * 命中的每一条都要再复核（见 [verifyHits]）才敢用。
     *
     * 只做**精确匹配**：名字逐字符相同、大小逐字节相同。DISPLAY_NAME 可能被 provider 改名，
     * 那种情况搜不到就搜不到 —— 不猜「可能的前后缀」。
     */
    private fun searchOriginal(
        meta: Meta,
        attempts: MutableList<String>,
        notes: MutableList<String>
    ): String? {
        val name = IncomingFile.lastSegment(meta.displayName)
        if (!IncomingFile.canSearchByNameAndSize(meta.displayName, meta.size)) {
            notes.add("跳过搜索：显示名或大小缺失（名称=\"$name\"，大小=${meta.size}）")
            return null
        }
        // 真要搜了：先把身份探出来。搜索通道按身份分流（有 shell 用 find、能进 Android/data），
        // 不探就会拿默认值走错通道。**这也是「反解成功就不探测」的落点** —— 走到这里才探第一次。
        ensureShellLevel()
        // 首选内置 busybox 的 timeout（一定在），系统 timeout 只是次选；惰性取，只在搜索时付成本
        val busybox = runCatching { FileRepository.busyboxTimeoutPath(applicationContext) }
            .getOrNull()
        val root = Environment.getExternalStorageDirectory().absolutePath

        // ① 已知目录（QQ / 下载 / 文档）—— 命中面窄、速度快
        val known = IncomingFile.knownSearchDirs(root)
        val knownOutcome = FileRepository.searchByNameAndSize(
            known, name, meta.size, busybox, IncomingFile.SEARCH_TIMEOUT_KNOWN_SECONDS
        )
        notes.add(knownOutcome.describe("已知目录 ${known.size} 个（${known.joinToString("、")}）"))
        verifyHits(knownOutcome.paths, meta, attempts)?.let { return it }

        // ② 限时全盘搜（兜底；/sdcard 下 Android/data 需 root/Shizuku）
        val fullOutcome = FileRepository.searchByNameAndSize(
            listOf(root), name, meta.size, busybox, IncomingFile.SEARCH_TIMEOUT_FULL_SECONDS
        )
        notes.add(fullOutcome.describe("全盘搜索 $root"))
        return verifyHits(fullOutcome.paths, meta, attempts)
    }

    /**
     * 复核搜索命中的候选：名字+大小相符 **且** 以当前身份列得出来，才返回它。
     *
     * 「搜到了」不等于「就是它」：find 的判据与 [FileRepository.listDir] 的可见性是两回事，
     * 而且大小要以**真实身份看到的那个值**为准（不能用 find 的结论顶替）。
     */
    private fun verifyHits(hits: List<String>, meta: Meta, attempts: MutableList<String>): String? {
        for (hit in hits) {
            val entry = findVisibleEntry(hit)
            if (entry == null) {
                attempts.add("(搜索) $hit → 以当前身份列不出该文件")
                continue
            }
            if (!IncomingFile.matchesSearchHit(meta.displayName, hit, meta.size, entry.size)) {
                attempts.add("(搜索) $hit → 与显示名或大小不符（实际大小 ${entry.size}）")
                continue
            }
            return hit
        }
        return null
    }

    /**
     * 反解候选路径，按可信度从高到低：
     *
     * 1. `file://` → path 直接就是路径；
     * 2. `content://` → 查 `_data` 列（媒体库类 provider 会给出真实路径）；
     * 3. `content://` → FileProvider 风格：path 段是「根别名 / 相对路径」，
     *    去掉第一个段后拼回 `Environment.getExternalStorageDirectory()`。
     *
     * 3 里还顺带认一种变体：provider 把绝对路径原样塞进了 path 段。
     */
    private fun candidatePaths(uri: Uri): List<String> {
        val out = LinkedHashSet<String>()

        if ("file".equals(uri.scheme, ignoreCase = true)) {
            uri.path?.takeIf { it.isNotEmpty() }?.let { out.add(it) }
            return out.toList()
        }

        // content://
        queryDataColumn(uri)?.takeIf { it.isNotEmpty() }?.let { out.add(it) }

        val rawPath = uri.path
        if (!rawPath.isNullOrEmpty()) {
            val external = Environment.getExternalStorageDirectory().absolutePath
            // 3a) 有些 provider 直接把绝对路径塞在 path 段
            if (rawPath.startsWith("$external/")) {
                out.add(rawPath)
            }
            // 3b) 去掉第一个「根别名」段，其余拼到外部存储根
            val trimmed = rawPath.trimStart('/')
            val firstSlash = trimmed.indexOf('/')
            if (firstSlash in 0 until (trimmed.length - 1)) {
                out.add("$external/${trimmed.substring(firstSlash + 1)}")
            }
        }
        return out.toList()
    }

    /**
     * 以**真正去读它的那个身份**判断这个路径可见不可见。
     *
     * 判据是「父目录能不能真的把这一项列出来」，而不是 `File.canRead()`：
     * [FileRepository.listDir] 内部会走 shell（ROOT / Shizuku）或 Java 双通道 ——
     * 普通身份进不去的 `Android/data`，在 ROOT 身份下是能列出来的。用 `canRead()`
     * 会把「用户明明能通过 App 打开」的文件误判成不可见。
     *
     * 返回命中的条目本身（拿得到真实大小，供搜索复核用），列不出则为 null。
     */
    private fun findVisibleEntry(path: String): FileItem? {
        val file = File(path)
        val parent = file.parent ?: return null
        val name = file.name
        if (name.isEmpty()) return null
        return FileRepository.listDir(parent).firstOrNull { it.name == name && !it.isDirectory }
    }

    private fun isVisible(path: String): Boolean = findVisibleEntry(path) != null

    /** 媒体库类 provider 的 `_data` 列（MediaStore.MediaColumns.DATA） */
    private fun queryDataColumn(uri: Uri): String? = try {
        contentResolver.query(uri, arrayOf("_data"), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex("_data")
                if (index >= 0 && !cursor.isNull(index)) cursor.getString(index) else null
            } else {
                null
            }
        }
    } catch (t: Throwable) {
        null
    }

    /** provider 的 MIME 类型，用于诊断（拿不到就空串）。 */
    private fun mimeOf(uri: Uri): String = try {
        contentResolver.getType(uri).orEmpty()
    } catch (t: Throwable) {
        ""
    }

    /** provider 给出的显示名与大小。size 为负数表示拿不到。 */
    private data class Meta(val displayName: String?, val size: Long)

    /**
     * 一次查询同时取 [`OpenableColumns.DISPLAY_NAME`] 与 [`OpenableColumns.SIZE`]。
     *
     * 两列各有用处且**同源**（都来自 provider 对同一个 URI 的回答），所以合并成一次
     * 查询：显示名用于核对反解出的路径与发起搜索，大小用于**核对搜索命中**（防同名不同文件）。
     * 任一列 provider 不给（`isNull`）或整个查询失败，都退回「拿不到」而不是抛异常 ——
     * 这个入口吃的是不可信输入，查询失败是很正常的一条路。
     */
    private fun queryMeta(uri: Uri): Meta = try {
        contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null, null, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val name = if (nameIndex >= 0 && !cursor.isNull(nameIndex)) {
                    cursor.getString(nameIndex)
                } else {
                    null
                }
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                val size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                    cursor.getLong(sizeIndex)
                } else {
                    -1L
                }
                Meta(name, size)
            } else {
                Meta(null, -1L)
            }
        } ?: Meta(null, -1L)
    } catch (t: Throwable) {
        Meta(null, -1L)
    }

    // ------------------------------------------------------------- 复制兜底

    /**
     * 把内容**流式**复制到 `Download/RxinDownload/`，返回落地文件；失败返回 null。
     *
     * 8KB 缓冲，绝不整份读进内存 —— 用户完全可能从 QQ 点开一个几百 MB 的文件。
     * 目标目录不存在就建（连同 `Download/` 一起，两级都建）；同名文件加 `(n)` 后缀，
     * 不覆盖用户已有的东西。
     *
     * 子目录名取自 [IncomingFile.COPY_DIR_NAME]（纯 Java 判据层的单一真源），
     * 而不是在这里写死一个字符串 —— 这样它的语义（「本应用转存副本的专属目录」）
     * 能被 `tools/incoming-check` 一起断言。
     */
    private fun copyToDownload(uri: Uri, displayName: String?): File? {
        val downloads = File(Environment.getExternalStorageDirectory(), Environment.DIRECTORY_DOWNLOADS)
        // 目标落在专属子目录里，避免和用户自己的下载物混在一起（见 COPY_DIR_NAME 的说明）。
        // mkdirs() 一次建全整条路径，父目录 Download/ 不存在也一并建出来。
        val dir = File(downloads, IncomingFile.COPY_DIR_NAME)
        if (!dir.isDirectory && !dir.mkdirs()) {
            return null
        }
        val safe = IncomingFile.safeName(displayName)
        var target: File? = null
        return try {
            val input = contentResolver.openInputStream(uri) ?: return null
            while (true) {
                val candidate = File(dir, IncomingFile.uniqueName(dir, safe))
                if (candidate.createNewFile()) {
                    target = candidate
                    break
                }
            }
            val created = target ?: return null
            input.use { from ->
                FileOutputStream(created, true).use { to ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val n = from.read(buffer)
                        if (n < 0) break
                        to.write(buffer, 0, n)
                    }
                    to.flush()
                }
            }
            created
        } catch (t: Throwable) {
            target?.delete()
            null
        }
    }

    // ------------------------------------------------------------- 诊断面板

    /**
     * 定位失败时的诊断面板。
     *
     * <h2>为什么是底部面板、而且内容全画在面板自己身上</h2>
     *
     * 原先失败只给一句 toast，而这次要交代的信息（URI 原文、scheme/authority/path 各段、
     * 显示名/大小/类型、当前身份、逐条候选与拒绝原因、搜索记录）远超一个 toast 的量。
     * 面板里的内容一律画在面板自己的窗口里（[R.layout.sheet_incoming_failure] 直接
     * `setContentView`），**绝不在其之上再开 AlertDialog 之类的第二个窗口** —— 那种叠加
     * 在不同 ROM 上层级顺序不一致，真机表现是「点了没反应、什么都看不到」（见 [SheetDialog] 的教训）。
     *
     * 正文放在可滚动区、按钮固定在下方，长文本也不会把按钮顶出屏幕。
     */
    private fun showUnresolvedSheet(outcome: Located.Unresolved) {
        val sheet = BottomSheetDialog(this)
        val content = layoutInflater.inflate(R.layout.sheet_incoming_failure, null)
        sheet.setContentView(content)
        sheet.setOnShowListener { sheet.glassSurface() }

        val title = content.findViewById<TextView>(R.id.incomingFailTitle)
        val scroll = content.findViewById<NestedScrollView>(R.id.incomingFailScroll)
        val text = content.findViewById<TextView>(R.id.incomingFailText)
        val copyBtn = content.findViewById<TextView>(R.id.incomingFailCopy)
        val openBtn = content.findViewById<TextView>(R.id.incomingFailOpen)
        val closeBtn = content.findViewById<TextView>(R.id.incomingFailClose)
        val shizukuBox = content.findViewById<View>(R.id.incomingFailShizukuBox)
        val shizukuText = content.findViewById<TextView>(R.id.incomingFailShizukuText)
        val shizukuBtn = content.findViewById<TextView>(R.id.incomingFailShizukuBtn)

        title.text = "未能定位原文件位置"
        text.text = outcome.report

        // 正文再长也只占屏幕的一部分：按窗口实际宽高测量，
        // 下方的动作按钮在横屏、分屏与大字体下仍可达。
        scroll.post {
            val percent = resources.getInteger(R.integer.incoming_report_height_percent)
            val maxHeight = (resources.displayMetrics.heightPixels * percent / 100f).toInt()
            val availableWidth = scroll.width.takeIf { it > 0 }
                ?: resources.displayMetrics.widthPixels
            val unspecified = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            val widthSpec = View.MeasureSpec.makeMeasureSpec(
                availableWidth,
                View.MeasureSpec.EXACTLY
            )
            text.measure(widthSpec, unspecified)
            val wanted = if (text.measuredHeight > maxHeight) maxHeight else
                ViewGroup.LayoutParams.WRAP_CONTENT
            if (scroll.layoutParams.height != wanted) {
                scroll.layoutParams = scroll.layoutParams.apply { height = wanted }
            }
        }

        val copied = outcome.copiedPath
        if (copied == null) {
            openBtn.visibility = View.GONE
        } else {
            openBtn.visibility = View.VISIBLE
            openBtn.setOnClickListener {
                revealTo(copied, null)
                sheet.dismiss()
            }
        }
        copyBtn.setOnClickListener {
            copyToClipboard(outcome.report)
            toast("诊断信息已复制到剪贴板")
        }
        closeBtn.setOnClickListener { sheet.dismiss() }

        // 普通身份下进不去 Android/data（QQ / 微信收到的文件多在那里），于是「定位不到」
        // 看起来像我们的 bug，其实是缺一个前置条件。这里给一条可点的出路。
        //
        // 判据是 needsShellHint（**按身份分流**），不是「失败了就提示」——
        // 已有 ROOT / ADB 的用户失败原因在别处，再劝他装 Shizuku 是误导。
        if (IncomingFile.needsShellHint(outcome.shellLevel)) {
            shizukuBox.visibility = View.VISIBLE
            shizukuText.text = IncomingFile.SHIZUKU_HINT_TEXT
            shizukuBtn.setOnClickListener { openShizukuDownload(sheet) }
        } else {
            shizukuBox.visibility = View.GONE
        }

        // 这个入口只是中转站：面板一关就退场
        sheet.setOnDismissListener { if (!isFinishing) finish() }

        sheet.show()
    }

    private fun copyToClipboard(text: String) {
        val manager = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        manager.setPrimaryClip(ClipData.newPlainText("RxinFiles-Tool 定位诊断", text))
    }

    /**
     * 打开 Shizuku 的下载页。
     *
     * 用 `ACTION_VIEW` 交给系统浏览器，**不硬编码任何应用包名** —— 用户装什么浏览器是他自己的事，
     * 指定包名一旦没装会 `ActivityNotFoundException`。链接本身收在 [IncomingFile.SHIZUKU_DOWNLOAD_URL]，
     * 判据层能断言它是个 https 地址（而不是被顺手改成别的）。
     *
     * 没有可用浏览器时**明确说一句**，而不是静默什么都不发生（点了没反应是最糟的失败形态）。
     */
    private fun openShizukuDownload(sheet: BottomSheetDialog) {
        val url = IncomingFile.SHIZUKU_DOWNLOAD_URL
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        try {
            startActivity(intent)
            sheet.dismiss()
        } catch (t: Throwable) {
            Log.w(TAG, "无法打开 Shizuku 下载页: $url", t)
            toast("没有可用的浏览器，请手动访问：$url")
        }
    }

    // ------------------------------------------------------------- 转交

    /** 把路径交给主界面导航 + 高亮，然后自己退场（只是个中转站）。 */
    private fun revealTo(path: String, note: String?) {
        if (note != null) {
            toast(note)
        }
        startActivity(
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_REVEAL_PATH, path)
                // 三个标志缺一不可（否则主界面接收 EXTRA_REVEAL_PATH 的 onNewIntent
                // 分支永远是死代码 —— 「onCreate 与 onNewIntent 都处理」就成了半句空话）：
                //  · NEW_TASK：本入口是别的应用拉起来的，可能落在对方任务的栈里；
                //    带上它，主界面才会回到**本应用自己的任务**，而不是寄生在对方任务里。
                //  · CLEAR_TOP：主界面已在栈中（不一定在顶）时，把它之上清掉并复用它。
                //  · SINGLE_TOP：复用已在顶的实例时走 onNewIntent，而不是再开一个。
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK
                        or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        or Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
        )
        finish()
    }

    // ------------------------------------------------------------- 杂项

    /**
     * 从 Intent 里取出要打开的 URI。
     *
     * 先看 `data`；有些应用（多选分享）只把 URI 塞在 `clipData` 里，取第一条 ——
     * **至少要能处理，不能崩**。
     */
    private fun resolveIncomingUri(intent: Intent?): Uri? {
        if (intent == null) return null
        intent.data?.let { return it }
        val clip = intent.clipData
        if (clip != null && clip.itemCount > 0) {
            return clip.getItemAt(0).uri
        }
        return null
    }

    private fun failAndFinish(message: String) {
        toast(message)
        finish()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private companion object {
        const val TAG = "IncomingActivity"
    }
}
