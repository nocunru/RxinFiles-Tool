package com.rxinfiles.tool

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException
import java.util.Locale

/**
 * 归档操作的应用层封装。
 *
 * [Archives]（纯 Java）负责格式细节，这里只加三件 Android 侧必须处理的事：
 *
 * **1. 读不到就把压缩包「借」出来。**
 * 以 root 浏览 /data 时点开一个 .zip，应用身份没有读权限，`ZipFile` 直接抛异常。
 * 这时用 `su cp` 拷一份到私有缓存再解析，用户看到的结果和平时一样。
 *
 * **2. 写不进去就先解到缓存再搬。**
 * 解压目标若是 root 才能写的目录，先在 cacheDir 里解好（纯 Java 写自家目录没问题），
 * 再用 `su cp` 搬过去 —— 比让 Java 直接往系统目录写可靠得多。
 *
 * **3. 把「读不到的文件」纳入压缩。**
 * 压缩是纯 Java 的，读不到就是读不到；同样先 stage 再压。
 */
object ArchiveRepository {

    private const val TAG = "ArchiveRepository"

    data class Entry(
        val name: String,
        val size: Long,
        val packed: Long,
        val isDirectory: Boolean,
        val time: Long,
        val unavailable: Boolean,
        /** ZIP / RAR / 7z 的内容加密条目列表看得到，但解压要密码 */
        val encrypted: Boolean = false
    )

    data class Listing(
        val kind: Archives.Kind,
        val kindLabel: String,
        val entries: List<Entry>,
        val totalSize: Long,
        val fileSize: Long,
        val charset: String?
    ) {
        val fileCount: Int get() = entries.count { !it.isDirectory }
    }

    data class Outcome(val count: Int, val target: File, val staged: Boolean)

    /** 读取结果：要么有内容，要么有一句能直接给用户看的失败原因 */
    data class LoadResult(val listing: Listing?, val error: String?)

    fun kindLabel(kind: Archives.Kind): String = when (kind) {
        Archives.Kind.ZIP -> "ZIP 压缩包"
        Archives.Kind.TAR -> "tar 归档"
        Archives.Kind.TAR_GZ -> "tar.gz 压缩包"
        Archives.Kind.TAR_BZ2 -> "tar.bz2 压缩包"
        Archives.Kind.TAR_XZ -> "tar.xz 压缩包"
        Archives.Kind.GZIP -> "gzip 单文件"
        Archives.Kind.BZIP2 -> "bzip2 单文件"
        Archives.Kind.XZ -> "xz 单文件"
        Archives.Kind.LZMA -> "lzma 单文件"
        Archives.Kind.RAR -> "RAR 压缩包"
        Archives.Kind.SEVEN_Z -> "7z 压缩包"
        Archives.Kind.UNKNOWN -> "未知格式"
    }

    /** 需要时把文件借到私有缓存，返回可读的副本；已经在可读位置则原样返回 */
    fun readableCopy(context: Context, file: File): File? = when {
        file.canRead() -> file
        else -> FileOps.stageToCache(context, file)
    }

    private fun <T> withReadableCopy(context: Context, archive: File, block: (File) -> T): T? {
        val readable = readableCopy(context, archive) ?: return null
        return try {
            block(readable)
        } finally {
            if (readable !== archive) {
                readable.parentFile?.let { cleanup(listOf(it)) }
            }
        }
    }

    /** 这个压缩包是不是要密码。 */
    fun needsPassword(context: Context, archive: File): Boolean =
        withReadableCopy(context, archive) { Archives.needsPassword(it) } ?: false

    // ------------------------------------------------------------------ 列举

    /** 列举归档内容；失败返回可读的原因 */
    fun load(context: Context, archive: File, password: CharArray? = null): LoadResult =
        withReadableCopy(context, archive) { readable ->
            loadReadable(archive, readable, password)
        } ?: unreadableArchive()

    /** 列举与密码探测共享同一份 ROOT 暂存副本。 */
    fun inspect(context: Context, archive: File, password: CharArray? = null): Pair<LoadResult, Boolean> =
        withReadableCopy(context, archive) { readable ->
            loadReadable(archive, readable, password) to Archives.needsPassword(readable)
        } ?: (unreadableArchive() to false)

    private fun loadReadable(archive: File, readable: File, password: CharArray?): LoadResult {
        return try {
            val raw = Archives.list(readable, password)
            LoadResult(
                Listing(
                    kind = raw.kind,
                    kindLabel = kindLabel(raw.kind),
                    entries = raw.items.map {
                        Entry(it.name, it.size, it.packed, it.directory, it.time, it.unavailable,
                            it.encrypted)
                    },
                    totalSize = raw.totalSize,
                    fileSize = raw.fileSize,
                    charset = raw.charsetName
                ),
                null
            )
        } catch (t: Throwable) {
            Log.w(TAG, "list failed: ${archive.path}", t)
            LoadResult(null, t.message?.takeIf { it.isNotBlank() } ?: "无法解析这个压缩包")
        }
    }

    private fun unreadableArchive() = LoadResult(
        null,
        "无法读取这个文件（权限不足）。\n" +
            "如果它在 /data 这类目录里，试试用 ROOT 身份打开，或先复制到可读目录。"
    )

    // ------------------------------------------------------------------ 解压

    /**
     * 解压到 destDir。
     *
     * @param onEntry 每个条目回调（IO 线程）
     * @param password RAR / 7z 的密码（没有就传 null）
     * @return 落地的文件数
     */
    fun extract(
        context: Context,
        archive: File,
        destDir: File,
        onEntry: (String) -> Unit = {},
        cancelled: () -> Boolean = { false },
        password: CharArray? = null
    ): Outcome = withReadableCopy(context, archive) { readable ->
        val listener = object : Archives.Listener {
            override fun onEntry(name: String, done: Long, total: Long) {
                onEntry(name)
            }

            override fun isCancelled(): Boolean = cancelled()
        }

        // 应用身份写得了就直接解，写不了就先解到缓存再搬
        if (FileOps.ensureWritableDir(destDir)) {
            val count = Archives.extract(readable, destDir, listener, password)
            return@withReadableCopy Outcome(count, destDir, false)
        }

        if (!RootShell.available) {
            throw IOException("目标目录不可写，且没有可用的 root")
        }

        val staging = FileOps.newStageDir(context, "extract")
            ?: throw IOException("无法创建临时目录")
        try {
            val count = Archives.extract(readable, staging, listener, password)
            val moved = moveStagedChildren(staging, destDir, cancelled)
            Log.i(TAG, "extracted via root: $moved entries into ${destDir.path}")
            Outcome(count, destDir, true)
        } finally {
            staging.deleteRecursively()
        }
    } ?: throw IOException("无法读取压缩包（权限不足）")

    /** 单条目解压（预览用），落到指定文件 */
    fun extractEntry(
        context: Context,
        archive: File,
        entryName: String,
        target: File,
        password: CharArray? = null
    ): Boolean = withReadableCopy(context, archive) { readable ->
        try {
            Archives.extractEntry(readable, entryName, target, password)
        } catch (t: Throwable) {
            Log.w(TAG, "extractEntry failed: $entryName", t)
            false
        }
    } ?: false

    /**
     * 解压「选中的条目」（目录条目会带上整棵子树）。
     *
     * @return 落地的文件数
     */
    fun extractSelected(
        context: Context,
        archive: File,
        entryName: String,
        destDir: File,
        onEntry: (String) -> Unit = {},
        cancelled: () -> Boolean = { false },
        password: CharArray? = null
    ): Int = withReadableCopy(context, archive) { readable ->
        val listener = object : Archives.Listener {
            override fun onEntry(name: String, done: Long, total: Long) {
                onEntry(name)
            }

            override fun isCancelled(): Boolean = cancelled()
        }
        if (FileOps.ensureWritableDir(destDir)) {
            return@withReadableCopy Archives.extractSelected(readable, destDir, entryName, listener, password)
        }
        if (!RootShell.available) {
            throw IOException("目标目录不可写，且没有可用的 root")
        }
        val staging = FileOps.newStageDir(context, "entry")
            ?: throw IOException("无法创建临时目录")
        try {
            val count = Archives.extractSelected(readable, staging, entryName, listener, password)
            moveStagedChildren(staging, destDir, cancelled)
            count
        } finally {
            staging.deleteRecursively()
        }
    } ?: throw IOException("无法读取压缩包（权限不足）")

    private fun moveStagedChildren(
        staging: File,
        destDir: File,
        cancelled: () -> Boolean
    ): Int {
        val children = staging.listFiles() ?: throw IOException("无法读取解压暂存目录")
        for (child in children) {
            if (cancelled()) throw Archives.Cancelled()
            moveStagedChild(child, File(destDir, child.name))
        }
        return children.size
    }

    private fun moveStagedChild(child: File, destination: File) {
        val parent = destination.parentFile ?: throw IOException("目标没有父目录")
        var backup: File? = null
        var copyAttempted = false
        var committed = false
        try {
            if (RootShell.succeeded("test -e ${RootShell.quote(destination.absolutePath)}")) {
                val candidate = File(parent, ".rxin_old_extract_${System.nanoTime()}_${child.name}")
                if (!RootShell.succeeded(
                        "mv ${RootShell.quote(destination.absolutePath)} " +
                            RootShell.quote(candidate.absolutePath)
                    )
                ) {
                    throw IOException("无法备份已有目标 ${destination.name}")
                }
                backup = candidate
            }
            copyAttempted = true
            if (!RootShell.succeeded(
                    "cp -rf ${RootShell.quote(child.absolutePath)} " +
                        RootShell.quote(destination.absolutePath)
                )
            ) {
                throw IOException("无法将 ${child.name} 搬运到目标目录")
            }
            backup?.let {
                if (!RootShell.succeeded("rm -rf ${RootShell.quote(it.absolutePath)}")) {
                    throw IOException("无法清理旧目标备份 ${it.name}")
                }
            }
            committed = true
        } finally {
            if (!committed) {
                var rollbackError: String? = null
                if (copyAttempted &&
                    !RootShell.succeeded("rm -rf ${RootShell.quote(destination.absolutePath)}")
                ) {
                    rollbackError = "无法清理失败后的目标 ${destination.name}"
                }
                backup?.let {
                    if (!RootShell.succeeded(
                            "mv ${RootShell.quote(it.absolutePath)} " +
                                RootShell.quote(destination.absolutePath)
                        )
                    ) {
                        Log.w(TAG, "extract rollback failed: ${it.path}")
                        rollbackError = "无法恢复 ${destination.name}，备份保留在 ${it.name}"
                    }
                }
                rollbackError?.let { throw IOException(it) }
            }
        }
    }

    /**
     * 把一个归档条目解到私有缓存里做预览。
     *
     * 预览文件放在 [FileOps.newStageDir] 给的**进程独占目录**下
     * （`cacheDir/rxin_stage/<pid>_<nano>_preview/`）：每次预览一个独占目录，既不会和
     * 其他预览/后台任务的副本撞名，跨进程兜底清理也能凭 `<pid>_` 前缀判断归属，
     * 不会误删别的进程正在用的目录。[previewEntry] 只负责**创建并返回**，
     * 目录的所有权随 Intent 的 `EXTRA_STAGED_DIR` 交给查看器，
     * 由查看器在 `isFinishing` 的 onDestroy 里释放；非正常退出遗留的条目
     * 由冷启动的兜底清理（[FileOps.sweepStaleStage]）扫掉。**不要在这里递归清根目录。**
     *
     * @return 解出来的本地文件；失败返回 null
     */
    fun previewEntry(
        context: Context,
        archive: File,
        entryName: String,
        password: CharArray? = null
    ): File? {
        val dir = FileOps.newStageDir(context, "preview") ?: return null
        val target = File(dir, File(entryName).name)
        val ok = extractEntry(context, archive, entryName, target, password)
        if (!ok || !target.isFile) {
            dir.deleteRecursively()
            return null
        }
        return target
    }

    // ------------------------------------------------------------------ 压缩

    /**
     * 打包。格式由**目标文件名的后缀**决定：`.7z` 出 7z，其余一律出 ZIP。
     *
     * 只有这两种格式能被创建：它们是跨平台最通用的两种，又都有成熟的纯 Java 实现。
     * tar / rar 只读 —— Android 上没有可信赖的 tar 二进制，rar 更没有合法的压缩器。
     *
     * 读不到的文件会先 stage 到缓存再用缓存里的副本打包，条目名仍用原名，
     * 所以解出来的结构和用户预期一致。
     */
    fun compress(
        context: Context,
        sources: List<File>,
        output: File,
        onEntry: (String) -> Unit = {},
        cancelled: () -> Boolean = { false }
    ): Outcome {
        val outputPath = output.canonicalPath
        sources.forEach { source ->
            if (source.canonicalPath == outputPath) {
                throw IOException("输出压缩包不能与源文件相同：${output.name}")
            }
        }
        val listener = object : Archives.Listener {
            override fun onEntry(name: String, done: Long, total: Long) {
                onEntry(name)
            }

            override fun isCancelled(): Boolean = cancelled()
        }

        // 把不可读的来源换成缓存副本
        val resolved = mutableListOf<File>()
        val stagedDirs = mutableListOf<File>()
        try {
            for (source in sources) {
                if (source.canRead()) {
                    resolved.add(source)
                    continue
                }
                val staged = FileOps.stageToCache(context, source)
                    ?: throw IOException("无法读取 ${source.name}")
                staged.parentFile?.let { stagedDirs.add(it) }
                resolved.add(staged)
            }
        } catch (t: Throwable) {
            cleanup(stagedDirs)
            throw t
        }

        val sevenZip = output.name.lowercase(Locale.US).endsWith(".7z")

        if (FileOps.ensureWritableDir(output.parentFile ?: output)) {
            try {
                val count = if (sevenZip) {
                    Archives.sevenZip(resolved, output, listener)
                } else {
                    Archives.zip(resolved, output, listener)
                }
                return Outcome(count, output, false)
            } finally {
                cleanup(stagedDirs)
            }
        }

        if (!RootShell.available) {
            cleanup(stagedDirs)
            throw IOException("目标目录不可写，且没有可用的 root")
        }

        // ROOT 落地暂存：用 [FileOps.newStageDir] 的进程独占目录
        // （`cacheDir/rxin_stage/<pid>_<nano>_compress/`）。每次压缩一个独占目录，
        // 并发压缩同名输出时不会互相覆盖，也便于跨进程兜底清理判断归属。
        val localDir = FileOps.newStageDir(context, "compress")
        if (localDir == null) {
            cleanup(stagedDirs)
            throw IOException("无法创建临时目录")
        }
        try {
            val local = File(localDir, output.name)
            val count = if (sevenZip) {
                Archives.sevenZip(resolved, local, listener)
            } else {
                Archives.zip(resolved, local, listener)
            }
            val ok = RootShell.succeeded(
                "cp -f ${RootShell.quote(local.absolutePath)} ${RootShell.quote(output.absolutePath)}"
            )
            if (!ok) throw IOException("写入目标目录失败")
            return Outcome(count, output, true)
        } finally {
            // 本次操作的独占目录由自己释放；不碰共享的 rxin_stage 根目录
            localDir.deleteRecursively()
            cleanup(stagedDirs)
        }
    }

    // ------------------------------------------------------------------ 工具

    /** 清理 stage 出来的临时目录（失败也不影响主流程） */
    private fun cleanup(dirs: List<File>) {
        dirs.forEach {
            try {
                it.deleteRecursively()
            } catch (t: Throwable) {
                Log.w(TAG, "cleanup failed: ${it.path}", t)
            }
        }
    }

    /**
     * 从文件名推断默认解压目录名：`foo.tar.gz` → `foo`、`bar.7z` → `bar`。
     *
     * 双后缀要整体去掉，只砍最后一个点会把 `foo.tar.gz` 变成 `foo.tar`。
     */
    fun baseNameOf(archiveName: String): String {
        val lower = archiveName.lowercase(Locale.US)
        for (suffix in DOUBLE_SUFFIXES) {
            if (lower.endsWith(suffix)) {
                return archiveName.substring(0, archiveName.length - suffix.length)
            }
        }
        val dot = archiveName.lastIndexOf('.')
        val stem = if (dot > 0) archiveName.substring(0, dot) else archiveName
        return stem.ifEmpty { "解压结果" }
    }

    /** 打包时的默认文件名；ext 不带点（zip / 7z） */
    fun defaultArchiveName(sources: List<File>, stamp: String, ext: String = "zip"): String {
        val base = if (sources.size == 1) sources[0].name else "压缩包_$stamp"
        val stem = base.substringBeforeLast('.', base)
        return "${stem.ifEmpty { "压缩包" }}.$ext"
    }

    private val DOUBLE_SUFFIXES = listOf(
        ".tar.gz", ".tar.gzip", ".tar.bz2", ".tar.xz", ".tar.lzma",
        ".tgz", ".tbz2", ".tbz", ".txz", ".gzip"
    )
}
