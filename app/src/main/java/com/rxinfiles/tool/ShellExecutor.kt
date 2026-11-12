package com.rxinfiles.tool

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * shell 执行级别。按能力由高到低降级。
 */
enum class ShellLevel(val label: String) {
    /** 完整 root，通过 su 获取 */
    ROOT("ROOT"),

    /** adb shell 级别（uid 2000），通过 Shizuku 获取 */
    ADB("ADB"),

    /** 无特权，退化为 Java 文件 API */
    NORMAL("普通")
}

/**
 * 三级 shell 执行器。
 *
 *   ROOT (su)  ->  ADB (Shizuku UserService)  ->  NORMAL (Java API)
 *
 * - ROOT 沿用原始实现的「持久 su 进程 + marker 定界」模式，避免反复 fork。
 * - ADB 走 Shizuku 用户服务：命令在 shell 身份进程中执行，等价于 adb shell。
 * - NORMAL 无 shell 能力，由调用方降级为 Java 文件 API。
 *
 * 级别完全由 [detect] 自动探测，**不提供手动指定入口**：
 * 用户看到的永远只是一个探测结果，而不是一个需要自己判断该选哪个的开关。
 * 终端身份不在此列 —— 终端固定为普通用户（见 TerminalActivity）。
 */
object ShellExecutor {

    private const val TAG = "ShellExecutor"
    private const val MARKER_PREFIX = "___RX_"
    private const val STATUS_PREFIX = "___RXS_"

    private val lock = ReentrantLock()
    private var counter = 0L

    // 仅 ROOT 模式使用的持久 su 进程
    private var process: Process? = null
    private var reader: BufferedReader? = null
    private var writer: OutputStream? = null

    @Volatile
    var level: ShellLevel = ShellLevel.NORMAL
        private set

    /** 当前级别是否具备 shell 能力 */
    val hasShell: Boolean
        get() = level != ShellLevel.NORMAL

    // ---------------------------------------------------------------- 探测

    /**
     * 探测可用的最高权限级别。
     * 会执行外部进程，必须在 IO 线程调用。
     */
    fun detect(): ShellLevel {
        val detected = when {
            canUseSu() -> ShellLevel.ROOT
            ShizukuBridge.isGranted() -> ShellLevel.ADB
            else -> ShellLevel.NORMAL
        }
        if (detected != level) {
            destroyInternal()
        }
        level = detected
        Log.i(TAG, "detected shell level: $detected")
        return detected
    }

    private fun canUseSu(): Boolean {
        val p = try {
            Runtime.getRuntime().exec(arrayOf("su", "-c", "echo ok"))
        } catch (t: Throwable) {
            return false
        }
        return try {
            if (!p.waitFor(4, TimeUnit.SECONDS)) {
                false
            } else {
                p.inputStream.bufferedReader().use { it.readText() }.contains("ok")
            }
        } catch (t: Throwable) {
            false
        } finally {
            if (p.isAlive) p.destroy()
            if (p.isAlive) p.destroyForcibly()
        }
    }

    // ---------------------------------------------------------------- 执行

    fun exec(cmd: String): String? = when (level) {
        ShellLevel.ROOT -> execViaPersistentRoot(cmd)
        ShellLevel.ADB -> ShizukuBridge.exec(cmd)
        ShellLevel.NORMAL -> null
    }

    /**
     * 一次 ROOT 命令的「退出码 + 输出」。
     *
     * 为什么需要它：持久 `su` 本身靠 marker 定界，只能拿到输出、拿不到退出码。
     * 若把 `printf '$?'` 直接拼进命令里，命令中途 `exit` 就会把整个持久 shell 带走，
     * marker 与退出码一起丢失（见 BUG日志-20261110 的 BUG-001）。
     *
     * 这里改成：命令**放进子 shell** 执行，退出码由**外层** shell 在子 shell 结束后打印。
     * 于是：
     *  - 命令里的 `exit N` 只结束那一条命令，不会杀掉持久 su；
     *  - 无论命令怎么退出，退出码行与 marker 一定打印得出来；
     *  - 调用方拿到的 code 就是命令真实的退出状态。
     *
     * 仅 ROOT 级别可用（ADB 的 Shizuku 服务只回传输出，没有退出码）。
     */
    fun execWithStatus(cmd: String): RootResult? {
        if (level != ShellLevel.ROOT) return null
        lock.withLock {
            return try {
                if (!ensureRootShell()) return null
                val marker = nextMarker()
                val statusToken = nextStatusToken()
                val w = writer ?: return null
                val r = reader ?: return null

                // 子 shell：内部的 exit 只结束这条命令；外层 shell 继续打印退出码与 marker
                w.write("(\n$cmd\n) 2>&1\n".toByteArray(Charsets.UTF_8))
                w.write("printf '\\n$statusToken%d\\n' \"\$?\"\n".toByteArray(Charsets.UTF_8))
                w.write("printf '%s\\n' '$marker'\n".toByteArray(Charsets.UTF_8))
                w.flush()

                val task = java.util.concurrent.FutureTask { readUntilMarker(r, marker) }
                Thread(task, "RxRoot-Marker").apply {
                    isDaemon = true
                    start()
                }
                val raw = try {
                    task.get(30, TimeUnit.SECONDS)
                } catch (t: Throwable) {
                    task.cancel(true)
                    throw t
                }
                val result = parseStatus(raw, statusToken)
                if (result == null) {
                    Log.w(TAG, "root command returned no status marker")
                    destroyInternal()
                }
                result
            } catch (t: Throwable) {
                Log.w(TAG, "execWithStatus failed: $cmd", t)
                destroyInternal()
                null
            }
        }
    }

    /** 带退出码的 ROOT 命令结果 */
    data class RootResult(val code: Int, val output: String)

    /** 持久 su 进程 + marker 定界；读取有界，超时后整只 shell 作废。 */
    private fun execViaPersistentRoot(cmd: String): String? {
        lock.withLock {
            return try {
                if (!ensureRootShell()) return null
                val marker = nextMarker()
                val w = writer ?: return null
                val r = reader ?: return null

                // 子 shell 包装：命令里即便误用了 exit，也只结束这一条命令，
                // 持久 su 与尾部 marker 都还能正常工作（BUG-001 的根因就在这里）。
                w.write("(\n$cmd\n) 2>&1\n".toByteArray(Charsets.UTF_8))
                w.write("printf '%s\\n' '$marker'\n".toByteArray(Charsets.UTF_8))
                w.flush()

                val task = java.util.concurrent.FutureTask { readUntilMarker(r, marker) }
                Thread(task, "RxRoot-Marker").apply {
                    isDaemon = true
                    start()
                }
                try {
                    task.get(30, TimeUnit.SECONDS)
                } catch (t: Throwable) {
                    task.cancel(true)
                    throw t
                }
            } catch (t: Throwable) {
                Log.w(TAG, "exec failed: $cmd", t)
                destroyInternal()
                null
            }
        }
    }

    private fun ensureRootShell(): Boolean {
        val p = process
        if (p != null && p.isAlive()) return true

        destroyInternal()

        return try {
            val newProcess = Runtime.getRuntime().exec("su")
            process = newProcess
            writer = newProcess.outputStream
            reader = BufferedReader(InputStreamReader(newProcess.inputStream, Charsets.UTF_8), 8192)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "ensureRootShell failed", t)
            false
        }
    }

    private fun nextMarker(): String {
        counter += 1
        return "$MARKER_PREFIX$counter"
    }

    /** 退出码行的前缀（与 marker 共用同一个自增计数，保证一次调用内唯一） */
    private fun nextStatusToken(): String {
        counter += 1
        return "$STATUS_PREFIX$counter"
    }

    /**
     * 从“命令输出 + 尾部退出码行”里拆出 (code, output)。
     *
     * 退出码行由外层 shell 在子 shell 结束后打印，所以它是**最后**出现的独有 token；
     * 命令自己的输出哪怕碰巧包含同样的字样，也只会出现在它前面。
     */
    private fun parseStatus(raw: String, statusToken: String): RootResult? {
        val index = raw.lastIndexOf(statusToken)
        if (index < 0) return null
        val code = raw.substring(index + statusToken.length)
            .lineSequence()
            .firstOrNull()
            ?.trim()
            ?.toIntOrNull()
            ?: return null
        return RootResult(code, raw.substring(0, index).removeSuffix("\n"))
    }

    private fun readUntilMarker(r: BufferedReader, marker: String): String {
        val sb = StringBuilder()
        while (true) {
            val line = r.readLine() ?: break
            if (line.trim() == marker) break
            sb.append(line).append('\n')
        }
        return sb.toString()
    }

    private fun destroyInternal() {
        try {
            process?.destroy()
            if (process?.isAlive == true) process?.destroyForcibly()
        } catch (_: Throwable) {
        }
        try {
            writer?.close()
        } catch (_: Throwable) {
        }
        try {
            reader?.close()
        } catch (_: Throwable) {
        }
        writer = null
        reader = null
        process = null
    }

    fun destroy() {
        lock.withLock { destroyInternal() }
    }

    /**
     * 以**应用自身身份**运行一次性命令（`sh -c`），不干扰持久会话。
     *
     * 终端固定为普通用户模式，因此凡是「与终端环境保持一致」的探测
     * （例如脚本解释器是否存在）都应该走这里，而不是走 [exec]。
     */
    fun execLocal(cmd: String): String? = oneShot(arrayOf("/system/bin/sh", "-c", cmd))

    private fun oneShot(command: Array<String>): String? {
        var p: Process? = null
        return try {
            val started = ProcessBuilder(*command).redirectErrorStream(true).start()
            p = started
            val task = java.util.concurrent.FutureTask {
                started.inputStream.bufferedReader().use { it.readText() }
            }
            Thread(task, "RxLocal-Reader").apply {
                isDaemon = true
                start()
            }
            val finished = started.waitFor(20, TimeUnit.SECONDS)
            if (!finished) started.destroyForcibly()
            val output = try {
                task.get(2, TimeUnit.SECONDS)
            } catch (_: Throwable) {
                ""
            }
            if (finished) output else output + "\n[命令超时]"
        } catch (t: Throwable) {
            Log.w(TAG, "oneShot failed", t)
            null
        } finally {
            if (p?.isAlive == true) p?.destroyForcibly()
        }
    }
}
