package com.rxinfiles.tool

import android.content.Context
import android.system.Os
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files

/**
 * 文件操作：复制 / 移动 / 删除 / 重命名。
 *
 * ## 四条设计原则
 *
 * **1. 覆盖是「先备份再替换」，不是「先删再写」。**
 * 覆盖同名文件时，先把旧文件 rename 成同目录下的隐藏备份（同一文件系统，瞬间完成、
 * 不产生数据拷贝），复制成功后再删掉备份；复制失败则把备份改回原名。
 * 这样中途失败/取消至少不会出现「旧的没了、新的没写完」这种半截状态。
 *
 * **2. 应用身份失败时自动降级到 root。**
 * 本应用平时以自身 uid 运行，碰 /data 这类目录必然 EACCES。若 [ShellExecutor]
 * 已探测到 root，单个文件的复制/移动/删除会自动改用 `su` 下的 `cp/mv/rm` 重试一次，
 * 不影响整体流程，也不需要用户手动切换权限。
 *
 * **3. 存在性必须按「真正去操作它的身份」判定。**
 * `File.exists()` 以应用自身 uid `stat`：ROOT-only 路径会得到 `false`（父目录不可穿越），
 * 而悬空符号链接也会得到 `false`（它跟随链接看目标）。把这种 `false` 当成「不存在」
 * 会让复制冲突策略、重命名、删除全部失效 —— 于是有了 [entryExists] / [isDirectory] /
 * [ensureDirectory] 这组统一身份判定：Java 看得见就用 Java，看不见且 ROOT 可用时才问
 * ROOT 的 `test -e` / `test -L` / `test -d` 复核。所有「目标是否已被占用」的判断都走它。
 *
 * **4. 所有长任务都响应取消。**
 * 每个文件之前检查一次取消标记，取消时抛 [Cancelled]，由调用方统一收拾。
 */
object FileOps {

    private const val TAG = "FileOps"
    private const val BUF = 128 * 1024

    /**
     * 暂存根目录。**每个使用者独占一个子目录**（名字带 pid 与创建时间），
     * 由创建者用完释放；[sweepStaleStage] 只在冷启动时兜底清理确认无进程使用的遗留条目。
     */
    private const val STAGE_ROOT = "rxin_stage"

    /** 兜底清理：进程已退出、但条目还很新时先放过，避免误删刚创建、尚未开始使用的目录 */
    private const val STAGE_MIN_AGE_MS = 10 * 60 * 1000L

    /** 超过这个时长的暂存条目一律视为遗留（正常文本/归档/视频操作不会挂这么久） */
    private const val STAGE_MAX_AGE_MS = 24 * 60 * 60 * 1000L

    /** 同名冲突处理策略 */
    enum class Conflict(val label: String) {
        OVERWRITE("覆盖同名文件"),
        RENAME("自动重命名（两份都留）"),
        SKIP("跳过同名文件")
    }

    /** 用户取消（继承 IOException，调用方可以一并捕获） */
    class Cancelled : IOException("已取消")

    /** 操作结果统计 */
    data class Outcome(
        val done: Int,
        val skipped: Int,
        val failed: Int,
        val error: String?
    ) {
        val ok: Boolean get() = failed == 0

        fun summary(action: String): String = buildString {
            append(action)
            append(if (ok) "完成" else "完成（部分失败）")
            append("：成功 ").append(done)
            if (skipped > 0) append("，跳过 ").append(skipped)
            if (failed > 0) append("，失败 ").append(failed)
            if (!error.isNullOrBlank()) append(" · ").append(error)
        }
    }

    private class Counter {
        var done = 0
        var skipped = 0
        var failed = 0
        var error: String? = null

        fun out() = Outcome(done, skipped, failed, error)

        fun fail(message: String?) {
            failed++
            if (error == null) {
                error = message
            }
        }
    }

    // ------------------------------------------- 统一身份的存在性 / 类型判定

    /**
     * 目录项是否已被占用：存在**或**是一个（可能是悬空的）符号链接。
     *
     * 判定顺序：Java 可见（`exists()` 或 `isSymbolicLink()`）→ 直接算存在；
     * Java 看不见且父目录普通身份进不去（ROOT-only 场景）→ 用 ROOT 的
     * `test -e || test -L` 复核。父目录普通身份进得去时不再问 ROOT：
     * 那时 `exists()==false` 就是真的不存在，没必要为每个文件多 fork 一次。
     */
    fun entryExists(file: File): Boolean {
        val visible = try {
            file.exists() || Files.isSymbolicLink(file.toPath())
        } catch (_: Throwable) {
            false
        }
        if (visible) return true
        return rootMaySeeMore(file) && rootEntryExists(file)
    }

    /**
     * 目录判定，同样按统一身份：Java 看得见就先信 Java；
     * **只有 Java 完全看不见**（可能是 ROOT-only 路径）时，才让 ROOT `test -d` 复核。
     */
    fun isDirectory(file: File): Boolean {
        val direct = try {
            file.isDirectory
        } catch (_: Throwable) {
            false
        }
        if (direct) return true
        val visible = try {
            file.exists() || Files.isSymbolicLink(file.toPath())
        } catch (_: Throwable) {
            false
        }
        // Java 能看见这个目录项却不是目录（普通文件 / 悬空链接），结论就是可信的，不必问 ROOT
        if (visible) return false
        if (!RootShell.available) return false
        return RootShell.succeeded("test -d ${RootShell.quote(file.absolutePath)}")
    }

    /**
     * 确保目标目录可用：Java 能建就 Java 建，建不出来再做一次 ROOT `mkdir -p` 复核。
     *
     * 不能直接 `if (!dir.isDirectory && !dir.mkdirs()) return false`：ROOT-only 目录
     * 两个判断都会失败，于是 ROOT 复制/移动在执行任何 ROOT 命令之前就被挡住了
     * （BUG日志-20261110 的 BUG-004）。
     */
    fun ensureDirectory(dir: File): Boolean {
        val direct = try {
            dir.isDirectory
        } catch (_: Throwable) {
            false
        }
        if (direct) return true
        val created = try {
            dir.mkdirs()
        } catch (_: Throwable) {
            false
        }
        if (created) return true
        if (!RootShell.available) return false
        val quoted = RootShell.quote(dir.absolutePath)
        return RootShell.succeeded("mkdir -p $quoted && test -d $quoted")
    }

    /** ROOT 身份复核某个目录项是否存在（含悬空符号链接） */
    private fun rootEntryExists(file: File): Boolean = RootShell.succeeded(
        "test -e ${RootShell.quote(file.absolutePath)} || " +
            "test -L ${RootShell.quote(file.absolutePath)}"
    )

    /**
     * Java 看不见某个路径，是否**可能**只是因为它藏在普通身份进不去的目录里。
     *
     * 只在这种时候才值得再花一次 ROOT 命令：正常目录里 `exists()==false` 就真的不存在。
     */
    private fun rootMaySeeMore(file: File): Boolean {
        if (!RootShell.available) return false
        val parent = file.parentFile ?: return true
        return try {
            !parent.exists() || !parent.canExecute()
        } catch (_: Throwable) {
            true
        }
    }

    // ------------------------------------------------------------------ 复制

    /**
     * 复制若干文件/目录到 destDir。
     *
     * @param onEntry 每处理一个文件回调一次（在调用线程上，通常是 IO 线程）
     */
    fun copy(
        sources: List<File>,
        destDir: File,
        conflict: Conflict,
        onEntry: (String) -> Unit,
        cancelled: () -> Boolean = { false }
    ): Outcome {
        val counter = Counter()
        if (!targetsAreSafe(sources, destDir, conflict, counter)) return counter.out()
        if (!ensureDirectory(destDir)) {
            counter.fail("无法创建目标目录")
            counter.failed = sources.size
            return counter.out()
        }
        for (source in sources) {
            if (cancelled()) throw Cancelled()
            copyOne(source, destDir, conflict, counter, onEntry, cancelled)
        }
        return counter.out()
    }

    private fun targetsAreSafe(
        sources: List<File>,
        destDir: File,
        conflict: Conflict,
        counter: Counter
    ): Boolean {
        for (source in sources) {
            val finalTarget = File(destDir, source.name)
            val target = if (conflict == Conflict.RENAME && entryExists(finalTarget)) {
                uniqueTarget(destDir, source.name)
            } else {
                finalTarget
            }
            try {
                if (FileSafety.isSameOrInside(source, target)) {
                    counter.fail("不能把 ${source.name} 复制或移动到它自身内部")
                    return false
                }
            } catch (t: IOException) {
                counter.fail("无法校验目标路径：${t.message}")
                return false
            }
        }
        return true
    }

    private fun copyOne(
        source: File,
        destDir: File,
        conflict: Conflict,
        counter: Counter,
        onEntry: (String) -> Unit,
        cancelled: () -> Boolean
    ) {
        val finalTarget = File(destDir, source.name)
        var backup: File? = null

        if (entryExists(finalTarget)) {
            when (conflict) {
                Conflict.SKIP -> {
                    counter.skipped++
                    return
                }
                Conflict.OVERWRITE -> {
                    backup = backupExisting(finalTarget, destDir)
                    if (backup == null) {
                        counter.fail("无法备份已有目标 ${finalTarget.name}")
                        return
                    }
                }
                Conflict.RENAME -> Unit
            }
        }

        val target = if (conflict == Conflict.RENAME && entryExists(finalTarget)) {
            uniqueTarget(destDir, source.name)
        } else {
            finalTarget
        }

        var ok = false
        try {
            ok = copyEntry(source, target, counter, onEntry, cancelled)
        } finally {
            finishReplacement(ok, backup, finalTarget, target, counter)
        }
    }

    /** 把已存在的目标改成同目录隐藏备份；Java 改不动时用 ROOT `mv`。 */
    private fun backupExisting(finalTarget: File, destDir: File): File? {
        val candidate = File(destDir, ".rxin_old_${System.nanoTime()}_${finalTarget.name}")
        if (renameEntry(finalTarget, candidate)) return candidate
        return null
    }

    private fun finishReplacement(
        ok: Boolean,
        backup: File?,
        finalTarget: File,
        target: File,
        counter: Counter
    ) {
        if (backup == null) return
        if (ok) {
            deleteRecursive(backup, null, counter, null)
            return
        }
        if (entryExists(target)) {
            deleteRecursive(target, null, Counter(), null)
        }
        if (!renameEntry(backup, finalTarget)) {
            Log.w(TAG, "rollback failed: ${backup.path}")
            counter.fail("无法恢复已有文件 ${finalTarget.name}，备份保留在 ${backup.name}")
        }
    }

    /** @return 是否成功（失败会写入 counter） */
    private fun copyEntry(
        source: File,
        target: File,
        counter: Counter,
        onEntry: (String) -> Unit,
        cancelled: () -> Boolean
    ): Boolean {
        if (cancelled()) throw Cancelled()

        if (Files.isSymbolicLink(source.toPath())) {
            onEntry(source.absolutePath)
            return try {
                target.parentFile?.mkdirs()
                val linkTarget = Os.readlink(source.absolutePath)
                if (entryExists(target) && !target.delete()) {
                    throw IOException("无法替换已有目标 ${target.name}")
                }
                Os.symlink(linkTarget, target.absolutePath)
                counter.done++
                true
            } catch (t: Throwable) {
                Log.w(TAG, "symlink copy failed: ${source.path} -> ${target.path}", t)
                if (copyWithRoot(source, target)) {
                    counter.done++
                    true
                } else {
                    counter.fail(t.message ?: t.javaClass.simpleName)
                    false
                }
            }
        }

        if (source.isDirectory) {
            if (entryExists(target) && !target.isDirectory) {
                deleteRecursive(target, null, Counter(), null)
            }
            if (!target.isDirectory && !target.mkdirs()) {
                // 目标目录建不出来（例如 /data 下需要 root）：整棵子树交给 cp -r
                if (copyWithRoot(source, target)) {
                    counter.done++
                    return true
                }
                counter.fail("无法创建目录 ${target.name}")
                return false
            }
            val children = source.listFiles()
            if (children == null) {
                // 列不出内容（多为读取权限不足）：整棵子树交给 cp -r
                if (copyWithRoot(source, target)) {
                    counter.done++
                    return true
                }
                counter.fail("无法读取目录 ${source.name}")
                return false
            }
            var allOk = true
            for (child in children) {
                if (!copyEntry(child, File(target, child.name), counter, onEntry, cancelled)) {
                    allOk = false
                }
            }
            if (allOk) {
                copyMeta(source, target)
                counter.done++
            }
            return allOk
        }

        onEntry(source.absolutePath)
        return try {
            target.parentFile?.mkdirs()
            // 悬空符号链接也是「已占用的目录项」：必须替换链接本身，
            // 不能 FileOutputStream 跟着链接写到它指向的位置（BUG-007）。
            if (Files.isSymbolicLink(target.toPath()) && !target.delete()) {
                throw IOException("无法替换已有符号链接 ${target.name}")
            }
            FileInputStream(source).use { input ->
                FileOutputStream(target).use { output ->
                    pipe(input, output, cancelled)
                }
            }
            copyMeta(source, target)
            counter.done++
            true
        } catch (t: Throwable) {
            if (t is Cancelled) {
                // 半截文件留着没意义
                target.delete()
                throw t
            }
            Log.w(TAG, "copy failed: ${source.path} -> ${target.path}", t)
            if (copyWithRoot(source, target)) {
                counter.done++
                true
            } else {
                target.delete()
                counter.fail(t.message ?: t.javaClass.simpleName)
                false
            }
        }
    }

    /**
     * 用 ROOT 身份重试一次复制。
     *
     * 源是文件 / 目录 / 符号链接、目标是文件还是目录，全部由 ROOT 自己 `test` 判定 ——
     * 普通身份 stat 失败时 `File.isDirectory` 不可信，照 Java 的结论去 `cp` 会把目录
     * 当文件、或把目录套进目录里。目标先删再写，保证「覆盖」语义、也不会写穿符号链接。
     */
    private fun copyWithRoot(source: File, target: File): Boolean {
        if (!RootShell.available) return false
        val s = RootShell.quote(source.absolutePath)
        val t = RootShell.quote(target.absolutePath)
        val parent = target.parentFile ?: return false
        val p = RootShell.quote(parent.absolutePath)
        val command = buildString {
            append("mkdir -p ").append(p).append(" && ")
            append("if [ -L ").append(s).append(" ]; then ")
            append("rm -f ").append(t).append(" && cp -Pf ").append(s).append(' ').append(t)
            append("; elif [ -d ").append(s).append(" ]; then ")
            append("rm -rf ").append(t).append(" && mkdir -p ").append(t)
            append(" && cp -rdf ").append(s).append("/. ").append(t).append("/")
            append("; else ")
            append("rm -rf ").append(t).append(" && cp -df ").append(s).append(' ').append(t)
            append("; fi && { [ -e ").append(t).append(" ] || [ -L ").append(t).append(" ]; }")
        }
        val ok = RootShell.succeeded(command)
        if (!ok) {
            Log.w(TAG, "root copy failed: ${source.path}")
        }
        return ok
    }

    // ------------------------------------------------------------------ 移动

    /**
     * 移动：同盘先试 rename（瞬间完成），跨盘或失败则退化为「复制 + 删除」。
     */
    fun move(
        sources: List<File>,
        destDir: File,
        conflict: Conflict,
        onEntry: (String) -> Unit,
        cancelled: () -> Boolean = { false }
    ): Outcome {
        val counter = Counter()
        if (!targetsAreSafe(sources, destDir, conflict, counter)) return counter.out()
        if (!ensureDirectory(destDir)) {
            counter.fail("无法创建目标目录")
            counter.failed = sources.size
            return counter.out()
        }
        for (source in sources) {
            if (cancelled()) throw Cancelled()
            val finalTarget = File(destDir, source.name)
            var backup: File? = null

            if (entryExists(finalTarget)) {
                when (conflict) {
                    Conflict.SKIP -> {
                        counter.skipped++
                        continue
                    }
                    Conflict.OVERWRITE -> {
                        backup = backupExisting(finalTarget, destDir)
                        if (backup == null) {
                            counter.fail("无法备份已有目标 ${finalTarget.name}")
                            continue
                        }
                    }
                    Conflict.RENAME -> Unit
                }
            }
            val target = if (conflict == Conflict.RENAME && entryExists(finalTarget)) {
                uniqueTarget(destDir, source.name)
            } else {
                finalTarget
            }
            var copied = false
            try {
                if (source.renameTo(target)) {
                    onEntry(source.absolutePath)
                    counter.done++
                    copied = true
                } else if (moveWithRoot(source, target)) {
                    onEntry(source.absolutePath)
                    counter.done++
                    copied = true
                } else {
                    copied = copyEntry(source, target, counter, onEntry, cancelled)
                    if (copied) {
                        // 删源失败时目标副本必须保留；finishReplacement 只回滚复制失败。
                        deleteRecursive(source, null, counter, null)
                    }
                }
            } finally {
                finishReplacement(copied, backup, finalTarget, target, counter)
            }
        }
        return counter.out()
    }

    /**
     * ROOT 身份移动：执行前用 ROOT 复核「目标未被占用」，执行后再用 ROOT 复核
     * 「目标已出现、源已消失」。**不用 `mv -f`** —— `-f` 会静默覆盖已存在的目标，
     * 而目标是否真的不存在必须由同一个身份确认（BUG-003）。
     */
    private fun moveWithRoot(source: File, target: File): Boolean {
        if (!RootShell.available) return false
        val s = RootShell.quote(source.absolutePath)
        val t = RootShell.quote(target.absolutePath)
        val command = buildString {
            append("if [ -e ").append(t).append(" ] || [ -L ").append(t).append(" ]; then false; ")
            append("else mv ").append(s).append(' ').append(t).append("; fi && ")
            append("{ [ -e ").append(t).append(" ] || [ -L ").append(t).append(" ]; } && ")
            append("! { [ -e ").append(s).append(" ] || [ -L ").append(s).append(" ]; }")
        }
        val ok = RootShell.succeeded(command)
        if (!ok) {
            Log.w(TAG, "root move failed: ${source.path} -> ${target.path}")
        }
        return ok
    }

    // ------------------------------------------------------------------ 删除

    fun delete(
        files: List<File>,
        onEntry: (String) -> Unit,
        cancelled: () -> Boolean = { false }
    ): Outcome {
        val counter = Counter()
        for (file in files) {
            if (cancelled()) throw Cancelled()
            onEntry(file.absolutePath)
            // 删除是否成功由 deleteRecursive 按「同一身份复核」给出，不再看 Java 的 exists()
            if (deleteRecursive(file, onEntry, counter, cancelled)) {
                counter.done++
            }
        }
        return counter.out()
    }

    /** @return 该条目是否已被清除（已按同一身份复核，不是只看 Java exists） */
    private fun deleteRecursive(
        file: File,
        onEntry: ((String) -> Unit)?,
        counter: Counter,
        cancelled: (() -> Boolean)?
    ): Boolean {
        if (cancelled != null && cancelled()) throw Cancelled()
        val symlink = Files.isSymbolicLink(file.toPath())
        if (!file.exists() && !symlink) {
            // 普通身份不存在的，未必真的不存在：ROOT-only 路径的父目录进不去时 exists() 恒 false。
            // 只有 ROOT 复核也确认不存在，才能当成「已经删干净」（BUG-002）。
            if (!RootShell.available || !rootEntryExists(file)) return true
            onEntry?.invoke(file.absolutePath)
            if (rootRemove(file)) return true
            counter.fail("无法删除 ${file.name}")
            return false
        }

        if (!symlink && file.isDirectory) {
            val children = file.listFiles()
            if (children != null) {
                for (child in children) {
                    try {
                        deleteRecursive(child, onEntry, counter, cancelled)
                    } catch (t: Throwable) {
                        if (t is Cancelled) throw t
                        Log.w(TAG, "delete failed: ${child.path}", t)
                    }
                }
            }
        }
        if (file.delete()) return true

        // 普通身份删不掉：交给 root
        if (RootShell.available) {
            onEntry?.invoke(file.absolutePath)
            if (rootRemove(file)) return true
        }
        counter.fail("无法删除 ${file.name}")
        return false
    }

    /** 用 ROOT 删一次，并用 ROOT 复核确实消失（不跟随符号链接、不把删不掉的目录当成功） */
    private fun rootRemove(file: File): Boolean =
        RootShell.succeeded("rm -rf ${RootShell.quote(file.absolutePath)}") &&
            !rootEntryExists(file)

    // ---------------------------------------------------------------- 重命名

    /**
     * 重命名（同目录改名）。
     *
     * 目标是否已被占用必须与执行移动的**同一个身份**确认：ROOT-only 目录里普通
     * `target.exists()` 会返回 false，随后无条件 `mv -f` 就会覆盖已有文件（BUG-003）。
     *
     * @return 成功返回新文件；目标已存在或改名失败返回 null
     */
    fun rename(file: File, newName: String): File? {
        val name = newName.trim()
        if (name.isEmpty() || name.contains('/')) return null
        if (name == file.name) return file

        val parent = file.parentFile ?: return null
        val target = File(parent, name)
        if (entryExists(target)) return null

        if (file.renameTo(target)) return target
        if (!RootShell.available) return null

        // 普通身份看不见的路径，源与目标都让 ROOT 复核一遍再动手
        if (rootEntryExists(target)) return null
        if (!rootEntryExists(file)) return null

        val s = RootShell.quote(file.absolutePath)
        val t = RootShell.quote(target.absolutePath)
        val command = buildString {
            append("if [ -e ").append(t).append(" ] || [ -L ").append(t).append(" ]; then false; ")
            append("else mv ").append(s).append(' ').append(t).append("; fi && ")
            append("{ [ -e ").append(t).append(" ] || [ -L ").append(t).append(" ]; }")
        }
        if (!RootShell.succeeded(command)) {
            Log.w(TAG, "root rename failed: ${file.path} -> ${target.path}")
            return null
        }
        return target
    }

    // ------------------------------------------------------------------ 杂项

    /** 同目录下取一个不冲突的名字：`name (1).ext`（悬空符号链接也算被占用） */
    fun uniqueTarget(dir: File, name: String): File {
        if (!entryExists(File(dir, name))) return File(dir, name)
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        for (i in 1 until 10000) {
            val candidate = File(dir, "$base ($i)$ext")
            if (!entryExists(candidate)) return candidate
        }
        return File(dir, "$base (${System.currentTimeMillis()})$ext")
    }

    /** 找一个不冲突的目录名（不带扩展名概念），用于解压目标 */
    fun uniqueDir(parent: File, base: String): File {
        if (!entryExists(File(parent, base))) return File(parent, base)
        for (i in 1 until 10000) {
            val candidate = File(parent, "$base ($i)")
            if (!entryExists(candidate)) return candidate
        }
        return File(parent, "$base (${System.currentTimeMillis()})")
    }

    /** 目录是否真的可写（用一个探针文件实测，比 canWrite() 可靠） */
    fun ensureWritableDir(dir: File): Boolean {
        if (!dir.isDirectory && !dir.mkdirs()) return false
        val probe = File(dir, ".rxin_probe_${System.nanoTime()}")
        return try {
            if (probe.createNewFile()) {
                probe.delete()
                true
            } else {
                false
            }
        } catch (t: Throwable) {
            false
        }
    }

    fun existsAndReadable(file: File): Boolean = try {
        file.isFile && file.canRead()
    } catch (t: Throwable) {
        false
    }

    // ------------------------------------------------------------------ 暂存

    /**
     * 新建一个本进程独占的暂存目录（`cacheDir/rxin_stage/<pid>_<nano>_<label>/`）。
     *
     * 目录名带 pid：跨进程兜底清理时靠它判断「这个目录的创建者还活着吗」。
     * **调用方用完后必须 deleteRecursively() 释放自己拿到的目录** —— 暂存资源按
     * 所有者生命周期管理，任何界面都不允许清空整个 [STAGE_ROOT]（BUG-005）。
     *
     * @return 新建的目录；失败返回 null
     */
    fun newStageDir(context: Context, label: String): File? {
        val root = File(context.cacheDir, STAGE_ROOT)
        if (!root.isDirectory && !root.mkdirs()) return null
        val safeLabel = label.map { if (it.isLetterOrDigit() || it == '_' || it == '-') it else '_' }
            .joinToString("")
        val dir = File(
            root,
            "${android.os.Process.myPid()}_${System.nanoTime()}_${safeLabel.ifEmpty { "stage" }}"
        )
        return if (dir.mkdirs()) dir else null
    }

    /**
     * 把应用读不到的文件「借」到私有缓存里再处理。
     *
     * 典型场景：以 root 身份浏览 /data 时想看一眼某个文本/图片，
     * 或者要把它压缩进 ZIP —— ZIP 是纯 Java 实现的，读不到就是读不到。
     * 于是先用 `su cp` 拷一份到 cacheDir/rxin_stage/<独占目录>/，之后所有处理都在
     * 自家目录里进行。
     *
     * @return 本地副本（位于一个独占暂存目录中）；失败返回 null。
     *         调用方用完后应删除该文件的 parentFile。
     */
    fun stageToCache(context: Context, source: File): File? {
        if (!RootShell.available) return null
        val stage = newStageDir(context, "stage") ?: return null
        val target = File(stage, source.name)
        val ok = RootShell.succeeded(
            "cp -rf ${RootShell.quote(source.absolutePath)} ${RootShell.quote(target.absolutePath)}"
        )
        if (!ok || !target.exists()) {
            stage.deleteRecursively()
            return null
        }
        Log.i(TAG, "staged ${source.path} -> ${target.path}")
        return target
    }

    /**
     * 冷启动兜底清理遗留暂存条目（**不是**「清空整个暂存目录」）。
     *
     * 正常路径由资源所有者自己释放；这里只处理上次进程崩溃/被杀留下的条目，判据：
     *  - 超过 [STAGE_MAX_AGE_MS] → 一律视为遗留；
     *  - 目录名带 pid 且该进程已不在 → 只有超过 [STAGE_MIN_AGE_MS] 才删（避开创建瞬间的竞态）；
     *  - 目录名带 pid 且就是本进程 → 同样要求超过 [STAGE_MIN_AGE_MS]：
     *    本进程刚启动时理论上还没有自己的条目，但清理线程与界面首帧是并行的，
     *    不能让「刚建出来、马上要用」的目录被自己的 sweep 删掉；
     *  - 认不出 pid 的旧命名条目 → 保守起见只按最大年龄处理。
     *
     * @return 实际清理的条目数
     */
    fun sweepStaleStage(context: Context): Int {
        val root = File(context.cacheDir, STAGE_ROOT)
        val entries = try {
            root.listFiles()
        } catch (t: Throwable) {
            Log.w(TAG, "sweepStaleStage list failed", t)
            null
        } ?: return 0
        val now = System.currentTimeMillis()
        val myPid = android.os.Process.myPid()
        var removed = 0
        for (entry in entries) {
            val age = (now - entry.lastModified()).coerceAtLeast(0L)
            val pid = entry.name.substringBefore('_').toIntOrNull()
            val stale = when {
                age > STAGE_MAX_AGE_MS -> true
                pid == null -> false
                pid == myPid -> age > STAGE_MIN_AGE_MS
                !processAlive(pid) && age > STAGE_MIN_AGE_MS -> true
                else -> false
            }
            if (!stale) continue
            try {
                if (entry.deleteRecursively()) {
                    removed++
                    Log.i(TAG, "swept stale stage entry: ${entry.name}")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "sweep failed: ${entry.path}", t)
            }
        }
        try {
            if (root.listFiles()?.isEmpty() == true) root.delete()
        } catch (_: Throwable) {
        }
        return removed
    }

    /** 同 UID 的另一个进程是否还活着（同名进程的 /proc 条目对本应用可见） */
    private fun processAlive(pid: Int): Boolean = try {
        File("/proc/$pid").exists()
    } catch (_: Throwable) {
        true
    }

    private fun copyMeta(source: File, target: File) {
        try {
            if (source.lastModified() > 0) {
                target.setLastModified(source.lastModified())
            }
        } catch (_: Throwable) {
        }
    }

    private fun pipe(input: java.io.InputStream, output: java.io.OutputStream, cancelled: () -> Boolean) {
        val buffer = ByteArray(BUF)
        while (true) {
            if (cancelled()) throw Cancelled()
            val read = input.read(buffer)
            if (read <= 0) break
            output.write(buffer, 0, read)
        }
    }

    /** 用同一身份把 from 改名成 to（Java 改不动时走 ROOT `mv`） */
    private fun renameEntry(from: File, to: File): Boolean {
        val direct = try {
            from.renameTo(to)
        } catch (_: Throwable) {
            false
        }
        if (direct) return true
        if (!RootShell.available) return false
        return RootShell.succeeded(
            "mv ${RootShell.quote(from.absolutePath)} ${RootShell.quote(to.absolutePath)}"
        )
    }
}

/**
 * root 一次性命令。
 *
 * 与 [ShellExecutor.exec] 的区别：这里要拿到**退出码**，用来判断 cp/mv/rm 是否真的成功。
 * 做法是让 [ShellExecutor.execWithStatus] 把命令放进子 shell 执行，再由外层 shell
 * 打印子 shell 的退出状态 —— 这样命令内部即使写了 `exit`，也只会结束这一条命令，
 * 不会连持久 su 一起带走（BUG日志-20261110 的 BUG-001）。
 */
object RootShell {

    private const val TAG = "RootShell"

    data class Res(val code: Int, val output: String) {
        val ok: Boolean get() = code == 0
    }

    /** 只有在自动探测到 root 级别时可用 */
    val available: Boolean
        get() = ShellExecutor.level == ShellLevel.ROOT

    fun run(command: String): Res? {
        if (!available) return null
        val result = ShellExecutor.execWithStatus(command) ?: run {
            Log.w(TAG, "root command returned no exit status")
            return null
        }
        return Res(result.code, result.output)
    }

    fun succeeded(command: String): Boolean {
        val res = run(command)
        if (res == null) {
            Log.w(TAG, "root unavailable: $command")
            return false
        }
        return res.ok
    }

    fun quote(path: String): String = ScriptRunner.quote(path)
}
