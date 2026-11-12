package com.rxinfiles.tool

import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

object TextFileRepository {
    private const val TAG = "TextFileRepository"
    private const val SLICE_CHARS = 100_000
    private const val HEAD_BYTES = 64 * 1024

    const val WRITE_FAILED = 0
    const val WRITE_OK = 1
    const val WRITE_ROOT = 2
    const val WRITE_VERIFY_FAILED = 3
    const val WRITE_CONFLICT = 4

    /** ROOT 提交命令约定：4 表示「目标版本已变化，拒绝覆盖」 */
    private const val ROOT_EXIT_CONFLICT = 4

    data class Version(val size: Long, val modified: Long)

    class Session(
        val source: File,
        val layout: TextSlicer.Layout,
        val targetVersion: Version
    ) {
        constructor(source: File, layout: TextSlicer.Layout) : this(source, layout, snapshot(source))
    }

    fun snapshot(file: File): Version {
        val direct = Version(file.length(), file.lastModified())
        if (!RootShell.available || direct.modified > 0L) return direct
        val result = RootShell.run("stat -c '%s %Y' ${RootShell.quote(file.absolutePath)}")
        if (result?.ok != true) return direct
        val values = result.output.trim().split(Regex("\\s+"))
        if (values.size != 2) return direct
        val size = values[0].toLongOrNull() ?: return direct
        val modified = values[1].toLongOrNull() ?: return direct
        return Version(size, modified * 1000L)
    }

    fun open(file: File, target: File = file, expectedVersion: Version? = null): Session {
        val head = ByteArray(minOf(HEAD_BYTES.toLong(), file.length()).toInt())
        FileInputStream(file).use { input ->
            var offset = 0
            while (offset < head.size) {
                val read = input.read(head, offset, head.size - offset)
                if (read < 0) break
                offset += read
            }
        }
        val charset = TextCodec.detect(head)
        return Session(
            file,
            TextSlicer.scan(file, charset, SLICE_CHARS),
            expectedVersion ?: snapshot(target)
        )
    }

    fun read(session: Session, index: Int): String {
        if (index !in session.layout.slices.indices) throw IOException("文本分片越界")
        val skip = if (index == 0) session.layout.bomLength else 0
        return TextSlicer.readSlice(
            session.source,
            session.layout.charset,
            session.layout.slices[index],
            skip
        )
    }

    fun write(
        cacheDir: File,
        target: File,
        session: Session,
        edits: Map<Int, String>
    ): Int {
        val temp = File(cacheDir, "rxin_merge_${System.nanoTime()}_${target.name}")
        temp.parentFile?.mkdirs()
        try {
            if (snapshot(target) != session.targetVersion) return WRITE_CONFLICT
            TextSlicer.merge(session.source, temp, session.layout, edits)
            val expected = TextSlicer.expectedLines(session.layout, edits)
            val actual = try {
                TextSlicer.countLines(temp, session.layout.charset)
            } catch (t: Throwable) {
                -1
            }
            if (actual != expected) {
                Log.w(TAG, "merge verify failed: expected $expected lines, got $actual")
                return WRITE_VERIFY_FAILED
            }

            val direct = commitDirect(target, temp, session.targetVersion)
            if (direct != WRITE_FAILED) return direct
            if (RootShell.available) {
                return commitWithRoot(target, temp, session.targetVersion)
            }
            return WRITE_FAILED
        } catch (t: Throwable) {
            Log.w(TAG, "merge failed", t)
            return WRITE_FAILED
        } finally {
            temp.delete()
        }
    }

    private fun commitDirect(target: File, merged: File, expected: Version): Int {
        val parent = target.parentFile ?: return WRITE_FAILED
        if (!parent.isDirectory) return WRITE_FAILED
        val staged = File(parent, ".rxin_save_${System.nanoTime()}_${target.name}")
        val backup = File(parent, target.name + ".bak")
        var backedUp = false
        try {
            FileInputStream(merged).use { input ->
                FileOutputStream(staged).use { output ->
                    input.copyTo(output)
                    output.fd.sync()
                }
            }
            if (snapshot(target) != expected) return WRITE_CONFLICT
            if (target.exists()) {
                if (backup.exists() && !backup.delete()) return WRITE_FAILED
                if (!target.renameTo(backup)) return WRITE_FAILED
                backedUp = true
            }
            if (!staged.renameTo(target)) {
                if (backedUp) backup.renameTo(target)
                return WRITE_FAILED
            }
            return WRITE_OK
        } catch (t: Throwable) {
            Log.w(TAG, "atomic overwrite failed", t)
            if (backedUp && !target.exists()) backup.renameTo(target)
            return WRITE_FAILED
        } finally {
            staged.delete()
        }
    }

    /**
     * ROOT 提交：命令末尾用 `exit 0 / 4 / 1` 明确回传结果，由 [RootShell.run] 取真实退出码。
     *
     * 持久 `su` 里命令如果直接 `exit`，整个持久 shell 会被带走、尾部 marker 打不出来 ——
     * [ShellExecutor.execWithStatus] 因此把命令放进子 shell 执行，再由外层 shell 打印退出码，
     * 命令内部的 `exit` 只结束这一条命令（BUG日志-20261110 的 BUG-001）。
     *
     * 即便结果真的丢了（shell 超时/被系统杀掉），也不能把「可能已提交」简单当成失败：
     * 用内容复核一次，确认已提交就返回 [WRITE_ROOT]，否则再按版本判断冲突。
     *
     * @return [WRITE_ROOT] / [WRITE_CONFLICT] / [WRITE_VERIFY_FAILED] / [WRITE_FAILED]
     */
    private fun commitWithRoot(target: File, merged: File, expected: Version): Int {
        if (snapshot(target) != expected) return WRITE_CONFLICT
        val parent = target.parentFile ?: return WRITE_FAILED
        val staged = File(parent, ".rxin_save_${System.nanoTime()}_${target.name}")
        val backup = File(parent, target.name + ".bak")
        val command = buildString {
            append("cp -f ").append(RootShell.quote(merged.absolutePath)).append(' ')
                .append(RootShell.quote(staged.absolutePath)).append(" || exit 1; ")
            append("chmod --reference=").append(RootShell.quote(target.absolutePath)).append(' ')
                .append(RootShell.quote(staged.absolutePath)).append(" 2>/dev/null || true; ")
            append("chown --reference=").append(RootShell.quote(target.absolutePath)).append(' ')
                .append(RootShell.quote(staged.absolutePath)).append(" 2>/dev/null || true; ")
            append("sync; ")
            append("[ \"\$(stat -c '%s %Y' ").append(RootShell.quote(target.absolutePath))
                .append(")\" = ")
                .append(RootShell.quote("${expected.size} ${expected.modified / 1000L}"))
                .append(" ] || exit 4; ")
            append("if [ -e ").append(RootShell.quote(backup.absolutePath)).append(" ] || [ -L ")
                .append(RootShell.quote(backup.absolutePath)).append(" ]; then rm -f ")
                .append(RootShell.quote(backup.absolutePath)).append(" || exit 1; fi; ")
            append("if [ -e ").append(RootShell.quote(target.absolutePath)).append(" ] || [ -L ")
                .append(RootShell.quote(target.absolutePath)).append(" ]; then mv ")
                .append(RootShell.quote(target.absolutePath)).append(' ')
                .append(RootShell.quote(backup.absolutePath)).append(" || exit 1; fi; ")
            append("if mv ").append(RootShell.quote(staged.absolutePath)).append(' ')
                .append(RootShell.quote(target.absolutePath)).append("; then exit 0; fi; ")
            append("if [ -e ").append(RootShell.quote(backup.absolutePath)).append(" ]; then mv ")
                .append(RootShell.quote(backup.absolutePath)).append(' ')
                .append(RootShell.quote(target.absolutePath)).append("; fi; ")
            append("rm -f ").append(RootShell.quote(staged.absolutePath)).append("; exit 1")
        }
        val result = RootShell.run(command)
        // 失败/结果未知时清掉可能残留的暂存文件（成功路径上它已经被 mv 走了）
        val cleanup = {
            RootShell.succeeded("rm -f ${RootShell.quote(staged.absolutePath)}")
            try {
                staged.delete()
            } catch (_: Throwable) {
            }
        }
        if (result == null) {
            // 结果未知：命令可能已经提交成功。用内容复核，不能把已提交的写入当成未提交。
            if (contentMatches(target, merged)) {
                cleanup()
                return WRITE_ROOT
            }
            cleanup()
            return if (snapshot(target) != expected) WRITE_CONFLICT else WRITE_FAILED
        }
        if (result.ok) {
            // 提交后复核一次：目标大小必须等于拼好的内容，否则不能对上层报成功
            val committedSize = snapshot(target).size
            return if (committedSize == merged.length()) {
                WRITE_ROOT
            } else {
                Log.w(TAG, "root commit size mismatch: $committedSize != ${merged.length()}")
                WRITE_VERIFY_FAILED
            }
        }
        cleanup()
        return if (result.code == ROOT_EXIT_CONFLICT) WRITE_CONFLICT else WRITE_FAILED
    }

    /** 目标内容是否与合并结果逐字节一致（ROOT-only 目标走 ROOT sha256） */
    private fun contentMatches(target: File, merged: File): Boolean {
        val expectedSize = merged.length()
        if (snapshot(target).size != expectedSize) return false
        val readable = try {
            target.canRead()
        } catch (_: Throwable) {
            false
        }
        if (readable) {
            return try {
                FileInputStream(target).use { actual ->
                    FileInputStream(merged).use { expected -> sameBytes(actual, expected) }
                }
            } catch (_: Throwable) {
                false
            }
        }
        val expected = sha256(merged) ?: return false
        return expected == rootSha256(target)
    }

    private fun sameBytes(actual: java.io.InputStream, expected: java.io.InputStream): Boolean {
        val left = ByteArray(64 * 1024)
        val right = ByteArray(64 * 1024)
        while (true) {
            val leftRead = readFully(actual, left)
            val rightRead = readFully(expected, right)
            if (leftRead != rightRead) return false
            if (leftRead <= 0) return true
            for (i in 0 until leftRead) {
                if (left[i] != right[i]) return false
            }
        }
    }

    private fun readFully(input: java.io.InputStream, buffer: ByteArray): Int {
        var offset = 0
        while (offset < buffer.size) {
            val read = input.read(buffer, offset, buffer.size - offset)
            if (read < 0) break
            offset += read
        }
        return offset
    }

    private fun sha256(file: File): String? = try {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
    } catch (_: Throwable) {
        null
    }

    private fun rootSha256(file: File): String? {
        val result = RootShell.run("sha256sum ${RootShell.quote(file.absolutePath)}") ?: return null
        if (!result.ok) return null
        val hash = result.output.trim().substringBefore(' ').trim()
        return hash.takeIf { it.length == 64 && it.all { c -> c in "0123456789abcdef" } }
    }
}
