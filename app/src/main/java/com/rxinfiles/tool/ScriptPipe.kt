package com.rxinfiles.tool

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.Charset
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * **脚本管道执行器** —— 与终端**完全独立**的第二条执行通道。
 *
 * 终端的脚本执行走 PTY（交互式、`sh '<脚本路径>'`、支持脚本里的 `read`）。
 * 这一条走的是 stdin 管道：**脚本内容整份读进内存、预处理后从 stdin 喂给 sh，
 * 用户脚本的路径不作为「文件」交给 sh 打开**，日志异步回吐、退出码回调。
 * 适合后台跑、批量跑、在界面上把执行日志展示出来。
 *
 * ## 为什么不能直接 `sh < 脚本` （即 `sh -s`）
 *
 * 实测结论（见 `tools/script-check/`，mksh / dash / busybox ash 三家一致）：
 *
 * | 调用 | `$0` | `$1` |
 * |---|---|---|
 * | `sh -s -- /p/x.sh a b` | **shell 自己的名字** | `/p/x.sh` |
 * | `sh -c '. /proc/self/fd/0' /p/x.sh a b` | **`/p/x.sh`** | `a` |
 *
 * 也就是说 `-s` 模式下第一个操作数是 `$1` 而**不是** `$0` —— 这与 POSIX 文档的
 * 字面描述相反。所以「用 `sh -` 执行 + 模拟修复 `$0`」这两件事只有走 `-c` 才能同时成立：
 * **`-c` 的操作数才是 `$0`**。
 *
 * 另外两条实测硬约束：
 *
 * - **`PWD` 环境变量一定会被 shell 用 `getcwd()` 覆盖。** 所以「设置 PWD」必须
 *   靠真换目录（`ProcessBuilder.directory`），光传环境变量没用。
 * - **脚本里的 `read` 会立刻拿到 EOF**（stdin 已被脚本内容占用）。这是纯 stdin 方案的
 *   固有代价 —— 交互式脚本请走终端那条通道。
 *
 * ## 它跑不了的脚本：加密 / 编译过的
 *
 * 这条通道的前提是「脚本是文本」。**加密或编译过的 `.sh` 不是**：
 *
 * - `shc` 之类工具产出的是 **ELF 可执行文件**；
 * - 另有一些做法是「明文首行 `#!` 指向自带解密器 + 后面跟密文」。
 *
 * 它们在终端里 `./x.sh` 能跑（走内核 `execve`），`sh x.sh` 却报
 * `syntax error: unexpected ')'` —— 因为 sh 拿到的是二进制/密文。管道通道
 * 连 `execve` 的机会都没有（只把**内容**喂给 sh，不把路径交给它），
 * 所以对这类文件**必然失败**，而且失败得很难看。
 *
 * 因此 [execute] 在读入之后先做一次形态判定，命中就直接返回可读的原因，
 * 而不是把密文喂进去让用户对着语法错误猜。见 `升级说明.md` 3.11.1。
 *
 * **但「含 NUL 字节」不算这一类**（3.11.6 修正）：脚本里嵌一段二进制载荷
 * （自解压脚本、把数据塞进引号 heredoc）也会让前 8KB 出现 NUL，而 `sh 文件`
 * 本来就能跑 —— 三家 shell 实测全退 0（`tools/script-check/probe-nul-script.sh`）。
 * 所以拒收的判据是**魔数命中**，或者「含 NUL 且既没有 `#!` 也没有脚本扩展名」
 * （这两样就是文件自己给的「读者声明」，见 [ScriptRunner.interpreterByExtension]）。
 *
 * ## 预处理（[preprocess]）
 *
 * 1. 去掉 UTF-8 BOM（`EF BB BF`）—— 带 BOM 时首行变成 `\uFEFF#!`，`#!` 不再位于
 *    文件开头；
 * 2. **删除全部 `\r` 字节**（不只是 `\r\n`）—— Windows 换行会让 shell 把 `\r`
 *    当成命令的一部分，报 `not found` 或 `syntax error`；
 * 3. 附带：UTF-16 的 BOM（`FF FE` / `FE FF`）意味着整份内容是双字节的，
 *    直接删 `\r` 只会把 `0D 00` 的一半留下变成乱码，所以这种情况下先按该编码
 *    解码再以 UTF-8 重新输出（[Preprocessed.reencoded]）。
 *
 * 预处理是**字节级**的，不做「统一转 UTF-8」—— 脚本是 GBK 就原样把 GBK 送给 sh，
 * 免得把本来能跑的文件改坏。
 *
 * ## 备选解释器
 *
 * 按顺序探测，第一个真的能跑通的胜出（探测会**同时**验证喂入方式与 `$0`，见 [probe]）：
 *
 * 1. `/system/bin/sh` —— ROM 自带（Android 上是 mksh）；
 * 2. `RXin/bin/busybox sh` —— **随包分发**，不受 ROM 影响。这是真正可用的备选；
 * 3. `RXin/bin/toybox sh` —— 保留在候选表里，但 **toybox 上游的 `sh` 一直躺在
 *    `toys/pending/` 未启用**（Ubuntu 0.8.9 与 Android 系统 toybox 同源，
 *    实测 `toybox sh` 直接报 `Unknown command sh`），所以它必然优雅跳过；
 * 4. PATH 里的 `sh` —— 最后兜底。
 *
 * 探测结果缓存，只付一次代价。
 */
object ScriptPipe {

    private const val TAG = "ScriptPipe"

    /** 一次读入的字符数 */
    private const val READ_BUF = 8 * 1024

    /** 单个流最多保留的日志字符数，超出的丢弃并标记 [Result.truncated] */
    private const val MAX_CAPTURE_CHARS = 2 * 1024 * 1024

    /** 探测解释器/喂入方式的超时（秒） */
    private const val PROBE_TIMEOUT_S = 6L

    /** 探测用的 `$0`，故意做成一个不像真实路径的名字 */
    private val PROBE_S0 = "/rxin/probe.sh"

    private const val PROBE_MARK = "RXINPROBE"

    /**
     * 探测脚本正文。它做两件事：
     *  - 把 `$0` 打出来（验证 `-c` 的操作数确实成了 `$0`）；
     *  - 以固定的 42 退出（验证退出码能原样透出）。
     *
     * 两者都满足才算这个「解释器 + 喂入方式」组合可用 —— 只验其中一条会漏掉
     * 「能跑但 `$0` 是错的」这种情况，而 `$0` 恰恰是脚本里 `dirname "$0"` 的命根子。
     */
    private val PROBE_BODY =
        "printf '" + PROBE_MARK + " %s\\n' \"\$0\"\nexit 42\n"

    // ------------------------------------------------------------------ 喂入方式

    /** 把脚本内容交给 shell 的具体写法（即 `-c` 的正文） */
    private data class Feed(val prelude: String, val label: String)

    /**
     * 三种喂入写法，按可用性排序。
     *
     * 都是「让 shell 从 fd 0 读」，区别只在怎么指到 fd 0：
     *  - `/proc/self/fd/0`：内核保证存在（只要 /proc 挂着），首选；
     *  - `/dev/stdin`：常规发行版一定有，Android 上由 init 铺出来，作为次选；
     *  - `eval "$(cat)"`：完全不依赖文件系统节点，但**会把整份脚本先读进 shell 内存**
     *    再二次解析，报错行号会失真，只在前面两条都不行时才用。
     */
    private val FEEDS = listOf(
        Feed(". /proc/self/fd/0", "/proc/self/fd/0"),
        Feed(". /dev/stdin", "/dev/stdin"),
        Feed("eval \"\$(cat)\"", "eval + cat")
    )

    // ------------------------------------------------------------------ 数据结构

    /** 解释器候选 */
    private data class Candidate(val argv: List<String>, val label: String)

    /** 探测通过的组合 */
    private data class Channel(val argv: List<String>, val feed: Feed, val label: String)

    /**
     * 一次管道执行的请求。
     *
     * @param scriptPath 脚本路径。用于三处：推导工作目录、作为 `$0`、读取内容。
     * @param args       传给脚本的位置参数（成为 `$1` `$2` …）
     * @param asRoot     以 root 身份执行（外面套一层 `su -c`）
     * @param workDir    显式指定工作目录；null = 用脚本所在目录（不可进入时会逐级退让）
     * @param stdinText  直接给脚本内容（非 null 时**不读文件**，`scriptPath` 只当 `$0`）
     * @param timeoutMs  超时毫秒；0 = 不超时
     */
    data class Request(
        val scriptPath: String,
        val args: List<String> = emptyList(),
        val asRoot: Boolean = false,
        val workDir: String? = null,
        val stdinText: String? = null,
        val timeoutMs: Long = 0L
    )

    /** 预处理结果 */
    data class Preprocessed(
        val bytes: ByteArray,
        /** 去掉了 UTF-8 BOM */
        val strippedBom: Boolean,
        /** 删掉的 `\r` 字节个数 */
        val strippedCr: Int,
        /** 因 UTF-16 BOM 而重编码为 UTF-8 */
        val reencoded: Boolean,
        val originalBytes: Int
    ) {
        // data class 里有 ByteArray，必须手写这两个，否则是引用比较
        override fun equals(other: Any?): Boolean = this === other
        override fun hashCode(): Int = System.identityHashCode(this)
    }

    /**
     * 执行结果。
     *
     * @param exitCode 进程退出码（被信号终止时是 `128 + 信号`）
     * @param signal   推断出的信号号；为 null 表示正常退出。
     *                 注意**推断**：脚本自己 `exit 130` 与真的被 SIGINT 杀掉，
     *                 Java 侧拿到的都是 130，无法区分。
     * @param workDir  实际生效的工作目录（请求的目录进不去时会退让）
     */
    data class Result(
        val exitCode: Int,
        val signal: Int?,
        val timedOut: Boolean,
        val cancelled: Boolean,
        val stdout: String,
        val stderr: String,
        val truncated: Boolean,
        val interpreter: String,
        val feed: String,
        val workDir: String,
        val workDirAdjusted: Boolean,
        val originalBytes: Int,
        val scriptBytes: Int,
        val strippedBom: Boolean,
        val strippedCr: Int,
        val reencoded: Boolean,
        val durationMs: Long,
        val error: String? = null
    ) {
        val ok: Boolean
            get() = error == null && signal == null && !timedOut && !cancelled && exitCode == 0

        /** 给界面/日志用的一句话摘要 */
        fun summary(): String {
            error?.let { return "启动失败：$it" }
            val head = when {
                cancelled -> "已取消"
                timedOut -> "超时终止"
                signal != null -> "被动终止：信号 $signal"
                else -> "退出码 $exitCode"
            }
            return buildString {
                append(head)
                append(" · ")
                append(interpreter)
                append("（")
                append(feed)
                append("）")
                if (workDirAdjusted) append(" · 工作目录已退让到 ${workDir}")
                if (strippedBom || strippedCr > 0 || reencoded) {
                    append(" · 预处理：")
                    val bits = mutableListOf<String>()
                    if (strippedBom) bits += "去 BOM"
                    if (strippedCr > 0) bits += "删 ${strippedCr} 个 CR"
                    if (reencoded) bits += "UTF-16 转 UTF-8"
                    append(bits.joinToString("、"))
                }
                append(" · ")
                append(durationMs)
                append(" ms")
            }
        }
    }

    /** 日志回调。两个方法都会在**后台线程**被调用，实现方自己负责切线程。 */
    interface Sink {
        fun onStdout(line: String)
        fun onStderr(line: String)
    }

    /** 可取消的句柄 */
    class Handle internal constructor() {
        internal val process = AtomicReference<Process?>(null)
        private val cancelled = AtomicBoolean(false)

        internal val cancelRequested: Boolean get() = cancelled.get()

        val isRunning: Boolean get() = process.get()?.isAlive == true

        /** 请求终止。返回 false 表示已经取消过。 */
        fun cancel(): Boolean {
            if (!cancelled.compareAndSet(false, true)) return false
            process.get()?.let { p -> runCatching { p.destroy() } }
            return true
        }
    }

    // ------------------------------------------------------------------ 预处理

    /**
     * 字节级预处理。详见类文档。
     *
     * 注意 `\r` 与 UTF-8 / GBK 的相容性：UTF-8 的续字节都 ≥ 0x80，GBK 的尾字节
     * 范围是 0x40–0xFE —— 0x0D 都不是合法的尾字节，所以逐字节删除不会破坏字符。
     */
    fun preprocess(raw: ByteArray): Preprocessed {
        // UTF-16：先解码再以 UTF-8 输出，否则删 \r 只会把 0D 00 拆成半个字符
        if (raw.size >= 2 &&
            ((raw[0] == 0xFF.toByte() && raw[1] == 0xFE.toByte()) ||
                (raw[0] == 0xFE.toByte() && raw[1] == 0xFF.toByte()))
        ) {
            val charset: Charset =
                if (raw[0] == 0xFF.toByte()) Charsets.UTF_16LE else Charsets.UTF_16BE
            val text = String(raw, 2, raw.size - 2, charset)
            val cleaned = text.replace("\r", "")
            return Preprocessed(
                bytes = cleaned.toByteArray(Charsets.UTF_8),
                strippedBom = true,
                strippedCr = text.length - cleaned.length,
                reencoded = true,
                originalBytes = raw.size
            )
        }

        var start = 0
        var bom = false
        if (raw.size >= 3 &&
            raw[0] == 0xEF.toByte() && raw[1] == 0xBB.toByte() && raw[2] == 0xBF.toByte()
        ) {
            start = 3
            bom = true
        }

        val out = ByteArray(raw.size - start)
        var n = 0
        var cr = 0
        var i = start
        while (i < raw.size) {
            val b = raw[i]
            if (b == '\r'.code.toByte()) {
                cr++
            } else {
                out[n++] = b
            }
            i++
        }
        return Preprocessed(
            bytes = if (n == out.size) out else out.copyOf(n),
            strippedBom = bom,
            strippedCr = cr,
            reencoded = false,
            originalBytes = raw.size
        )
    }

    // ------------------------------------------------------------------ 通道探测

    @Volatile
    private var channel: Channel? = null

    @Volatile
    private var channelError: String? = null

    private val probeLock = Any()

    /** 上一次探测失败的原因；成功则为 null */
    val lastChannelError: String? get() = channelError

    /** 已经选定的通道描述（未探测则返回 null） */
    fun channelLabel(): String? = channel?.let { "${it.label} · ${it.feed.label}" }

    /** 换过 busybox / 换过 ROM 之后强制重探 */
    fun resetChannel() {
        synchronized(probeLock) {
            channel = null
            channelError = null
        }
    }

    private fun candidates(context: Context): List<Candidate> = buildList {
        add(Candidate(listOf(RxinEnv.SHELL_PATH), "系统 sh"))
        RxinToolbox.busybox?.let { add(Candidate(listOf(it, "sh"), "内置 busybox sh")) }
        // toybox 上游没有启用 sh（`toys/pending/sh.c`），这里仍然留着：
        // 探测会失败并优雅跳过，将来上游补上就能直接用
        val toybox = File(RxinToolbox.binDir(context), "toybox")
        if (toybox.isFile) add(Candidate(listOf(toybox.absolutePath, "sh"), "toybox sh"))
        add(Candidate(listOf("sh"), "PATH 里的 sh"))
    }

    /**
     * 逐个候选 × 逐个喂入方式做真执行探测，返回第一个通过的组合。
     *
     * 探测必须走**和真实执行完全相同的命令行形状**（同 argv、同环境、同喂入方式），
     * 否则「探测通过」不等价于「命令可用」—— 3.10.2 就是栽在这上面
     * （探测用 `.so` 实体路径，用户走符号链接，basename 不同导致 applet 查不到）。
     */
    private fun selectChannel(context: Context): Channel {
        channel?.let { return it }
        synchronized(probeLock) {
            channel?.let { return it }
            val env = RxinEnv.build(context, "/")
            val tried = mutableListOf<String>()
            for (candidate in candidates(context)) {
                if (!exists(candidate.argv.first())) {
                    tried += "${candidate.label}（不存在）"
                    continue
                }
                for (feed in FEEDS) {
                    if (probe(candidate, feed, env)) {
                        val ch = Channel(candidate.argv, feed, candidate.label)
                        channel = ch
                        channelError = null
                        Log.i(TAG, "channel = ${candidate.label} / ${feed.label}")
                        return ch
                    }
                }
                tried += "${candidate.label}（三种喂入方式都不可用）"
            }
            val msg = "没有可用的 sh 解释器：" + tried.joinToString("；")
            channelError = msg
            throw IOException(msg)
        }
    }

    private fun exists(path: String): Boolean =
        if (path == "sh") true else File(path).let { it.isFile && it.canExecute() }

    private fun probe(candidate: Candidate, feed: Feed, env: Array<String>): Boolean = try {
        val pb = ProcessBuilder(candidate.argv + listOf("-c", feed.prelude, PROBE_S0))
            .redirectErrorStream(true)
        applyEnv(pb, env)
        val p = pb.start()
        p.outputStream.use { it.write(PROBE_BODY.toByteArray(Charsets.UTF_8)) }
        val out = p.inputStream.bufferedReader().use { it.readText() }
        val done = p.waitFor(PROBE_TIMEOUT_S, TimeUnit.SECONDS)
        if (!done) {
            runCatching { p.destroy() }
            false
        } else {
            p.exitValue() == 42 && out.contains("$PROBE_MARK $PROBE_S0")
        }
    } catch (t: Throwable) {
        Log.w(TAG, "probe failed: ${candidate.label} / ${feed.label}", t)
        false
    }

    // ------------------------------------------------------------------ 执行

    /**
     * 启动一次管道执行。立即返回句柄，结果通过 [onFinish] 回吐（主线程）。
     *
     * @param sink 逐行日志回调，可为 null（只关心最终结果时）
     * @param onFinish 结束回调，**保证在主线程**调用一次
     */
    fun run(
        context: Context,
        request: Request,
        sink: Sink? = null,
        onFinish: (Result) -> Unit
    ): Handle {
        val handle = Handle()
        val appContext = context.applicationContext ?: context
        val main = Handler(Looper.getMainLooper())
        val started = System.currentTimeMillis()

        val thread = Thread({
            val result = try {
                execute(appContext, request, sink, handle, started)
            } catch (t: Throwable) {
                Log.w(TAG, "run failed", t)
                failure(describe(t), started)
            }
            main.post { onFinish(result) }
        }, "rxin-script-pipe")
        thread.isDaemon = true
        thread.start()
        return handle
    }

    private fun execute(
        context: Context,
        request: Request,
        sink: Sink?,
        handle: Handle,
        started: Long
    ): Result {
        RxinToolbox.ensure(context)

        // ---- 1. 读入内存（或直接用调用方给的内容） ----
        val scriptFile = File(request.scriptPath)
        val raw = request.stdinText?.toByteArray(Charsets.UTF_8)
            ?: readScript(scriptFile, request.asRoot)
            ?: return failure("无法读取脚本 ${scriptFile.name}", started)

        // ---- 2. 预处理 ----
        val pre = preprocess(raw)

        // ---- 2b. 形态检查：sh 读不了的东西，早点说清楚 ----
        // 这条通道的全部前提是「脚本是文本、能交给 sh 解析」。加密/编译过的脚本
        // （ELF，或首行 #! 指向自带解密器）不满足这个前提：喂进 sh 只会换来
        // 满屏 `syntax error: unexpected ')'`，用户完全看不出是文件形态的问题。
        //
        // 判据**收窄到魔数**（3.11.6）：含 NUL 的文件**未必**不是脚本 ——
        // 脚本里嵌一段二进制载荷很常见（自解压脚本、引号 heredoc 里塞数据），
        // `sh 文件` 本来就能跑（`probe-nul-script.sh` 实测三家 shell 全退 0）。
        // 3.11.1 把「含 NUL」一并拦下，误伤了这类脚本 —— 与终端那条通道同一个错。
        val head = if (raw.size > ScriptFormat.SNIFF_BYTES) {
            raw.copyOf(ScriptFormat.SNIFF_BYTES)
        } else {
            raw
        }
        ScriptFormat.magicKind(head)?.let { kind ->
            return failure(
                "这不是文本脚本（$kind），管道通道执行不了。\n" +
                    "它的内容要由内核装载（ELF），或者由首行 #! 指的那个程序去读取解密，" +
                    "而不是当脚本文本喂给 sh —— 那样只会得到一堆语法错误。\n" +
                    "请在文件列表里直接执行它（终端那条通道会把文件交给内核）。",
                started
            )
        }

        val scriptFile0 = scriptFile.absolutePath
        val pf = runCatching { ScriptRunner.preflight(scriptFile0, request.asRoot) }.getOrNull()
        val foreign = pf?.shebangInterpreter?.takeIf {
            it.isNotBlank() && !ScriptRunner.isKnownInterpreter(it)
        }
        if (foreign != null) {
            return failure(
                "首行 #! 指向 $foreign —— 它不是 shell 解释器（多半是脚本自带的解密器）。\n" +
                    "这条通道只把**内容**喂给 sh、不把文件路径交给它，那个程序无从下手。" +
                    "请在文件列表里直接执行它。",
                started
            )
        }

        // 含 NUL、又没有任何「读者声明」（首行没有 #!、扩展名也不是脚本）：
        // 这条通道连**路径**都不交给 shell，判不出来的东西只能拒。
        // 终端那条通道会把这一问交给 shell 现判（`[ -x ]`），这里没有那个余地。
        if (ScriptFormat.binaryKind(head) != null &&
            pf?.shebangInterpreter == null &&
            ScriptRunner.interpreterByExtension(scriptFile0) == null
        ) {
            return failure(
                "这个文件含二进制内容（NUL 字节），首行也没有 #!、扩展名也不是脚本，\n" +
                    "管道通道判不出该不该当脚本文本喂给 sh。\n" +
                    "请在文件列表里直接执行它：终端那条通道会让 shell 现判" +
                    "（有执行位就直接跑它本身，没有才交给 sh）。",
                started
            )
        }

        // ---- 3. 工作目录（必须真的能进得去，否则 ProcessBuilder 直接抛） ----
        val requested = request.workDir ?: scriptFile.absoluteFile.parent
        val workDir = resolveWorkDir(context, requested, request.asRoot)
        val adjusted = workDir != requested

        // ---- 4. 解释器 + 喂入方式（探测走的是与真实执行相同的命令行形状） ----
        val ch = selectChannel(context)

        // ---- 5. 命令行 ----
        val scriptPath = scriptFile0
        val shebangArgs = pf?.shebangArgs
        val argv = buildArgv(ch, shebangArgs, scriptPath, request.args, request.asRoot)

        val env = RxinEnv.build(context, workDir)

        // ---- 6. 起进程 ----
        val pb = ProcessBuilder(argv)
            .redirectErrorStream(false)
            .directory(File(workDir))
        applyEnv(pb, env)

        val process = pb.start()
        handle.process.set(process)
        if (handle.cancelRequested) runCatching { process.destroy() }

        // ---- 7. 先启动输出泵，避免脚本边读 stdin 边输出时互相堵死 ----
        val charset = runCatching { TextCodec.charsetOf(TextCodec.detect(pre.bytes)) }
            .getOrDefault(Charsets.UTF_8)
        val outCap = Capture()
        val errCap = Capture()
        val tOut = pump(process.inputStream, charset, outCap, sink, true)
        val tErr = pump(process.errorStream, charset, errCap, sink, false)

        // stdin 也独立写入；进程等待与超时从进程启动后立即生效。
        val writer = Thread({
            try {
                process.outputStream.use { out ->
                    out.write(pre.bytes)
                    out.flush()
                }
            } catch (t: Throwable) {
                Log.w(TAG, "write script to stdin failed", t)
            }
        }, "rxin-pipe-stdin").apply {
            isDaemon = true
            start()
        }

        // ---- 8. 等退出 ----
        var timedOut = false
        if (request.timeoutMs > 0) {
            if (!process.waitFor(request.timeoutMs, TimeUnit.MILLISECONDS)) {
                timedOut = true
                runCatching { process.destroy() }
                process.waitFor(3, TimeUnit.SECONDS)
                if (process.isAlive) runCatching { process.destroyForcibly() }
            }
        } else {
            process.waitFor()
        }
        writer.join(2000)
        tOut.join(2000)
        tErr.join(2000)

        val code = runCatching { process.exitValue() }.getOrDefault(-1)
        val signal = if (code in 129..192) code - 128 else null

        return Result(
            exitCode = code,
            signal = signal,
            timedOut = timedOut,
            cancelled = handle.cancelRequested,
            stdout = outCap.text.toString(),
            stderr = errCap.text.toString(),
            truncated = outCap.truncated || errCap.truncated,
            interpreter = ch.label,
            feed = ch.feed.label,
            workDir = workDir,
            workDirAdjusted = adjusted,
            originalBytes = pre.originalBytes,
            scriptBytes = pre.bytes.size,
            strippedBom = pre.strippedBom,
            strippedCr = pre.strippedCr,
            reencoded = pre.reencoded,
            durationMs = System.currentTimeMillis() - started
        )
    }

    // ------------------------------------------------------------------ 命令行

    /**
     * 拼 argv。
     *
     * 普通身份：
     * ```
     * <解释器> [-e|-x…] -c '. /proc/self/fd/0' <脚本路径> <参数…>
     *                                  ^ 这一项才是 $0
     * ```
     * root 身份外面再套一层 `su -c`，整串做单引号转义后作为**一个参数**交给 su
     * —— 与终端 root 会话用的是同一套路子（`ScriptRunner.quote`）。
     */
    private fun buildArgv(
        ch: Channel,
        shebangArgs: String?,
        scriptPath: String,
        args: List<String>,
        asRoot: Boolean
    ): List<String> {
        val inner = mutableListOf<String>()
        inner += ch.argv
        // shebang 里的开关只透传白名单内的（-e / -x / -u / -v），
        // 免得把任意参数带进命令行改变执行语义
        shebangArgs?.split(Regex("\\s+"))
            ?.filter { it in ScriptRunner.SAFE_SHEBANG_ARGS }
            ?.takeIf { it.isNotEmpty() }
            ?.let { inner += it }
        inner += listOf("-c", ch.feed.prelude, scriptPath)
        inner += args

        if (!asRoot) return inner
        return listOf(RxinEnv.SU_PATH, "-c", inner.joinToString(" ") { ScriptRunner.quote(it) })
    }

    // ------------------------------------------------------------------ 读取与目录

    /**
     * 读脚本内容。
     *
     * 应用自己读不到时（`/data` 下别人的文件、权限 0600 的脚本）若走 root，
     * 就借 `su -c cat` 把内容捞出来 —— 因为后面内容是经 stdin 喂进去的，
     * root 的 shell 不必再去看那个文件。
     */
    private fun readScript(file: File, asRoot: Boolean): ByteArray? {
        if (file.isFile && file.canRead()) {
            return runCatching { file.readBytes() }.getOrNull()
        }
        if (!asRoot) return null
        val process = try {
            ProcessBuilder(RxinEnv.SU_PATH, "-c", "cat " + ScriptRunner.quote(file.absolutePath))
                .redirectErrorStream(true)
                .start()
        } catch (t: Throwable) {
            return null
        }
        return try {
            val output = CompletableFuture.supplyAsync {
                process.inputStream.use { it.readBytes() }
            }
            if (!process.waitFor(15, TimeUnit.SECONDS)) {
                null
            } else {
                val bytes = output.get(1, TimeUnit.SECONDS)
                if (process.exitValue() == 0) bytes else null
            }
        } catch (t: Throwable) {
            null
        } finally {
            if (process.isAlive) process.destroy()
            if (process.isAlive) process.destroyForcibly()
        }
    }

    /**
     * 挑一个真能进入的工作目录。
     *
     * `ProcessBuilder.directory()` 指向一个进不去的目录会让 `start()` 直接抛 IOException，
     * 所以这里先逐级退让：请求目录 → `/sdcard` → 应用私有目录 → `/`。
     * 普通身份还要求目录可执行（进入权限）；root 交给 su，不预判。
     */
    private fun resolveWorkDir(context: Context, requested: String?, asRoot: Boolean): String {
        val wanted = listOfNotNull(
            requested?.takeIf { it.isNotBlank() },
            "/sdcard",
            context.filesDir?.absolutePath,
            "/"
        ).distinct()

        for (dir in wanted) {
            val f = File(dir)
            if (!f.isDirectory) continue
            if (asRoot || f.canExecute()) return dir
        }
        return "/"
    }

    // ------------------------------------------------------------------ 日志泵

    private class Capture {
        val text = StringBuilder()

        @Volatile
        var truncated = false
    }

    /**
     * 把一个流里的内容读到 [Capture]，同时按行回调 [sink]。
     *
     * 逐行回调而不是整块回吐，是为了让界面能「边跑边出」；
     * 累积的副本另有上限，避免一个疯狂打日志的脚本把内存吃光。
     */
    private fun pump(
        stream: InputStream,
        charset: Charset,
        capture: Capture,
        sink: Sink?,
        isStdout: Boolean
    ): Thread {
        val thread = Thread({
            try {
                BufferedReader(InputStreamReader(stream, charset), READ_BUF).use { reader ->
                    val buf = CharArray(READ_BUF)
                    val line = StringBuilder()
                    var total = 0
                    while (true) {
                        val n = reader.read(buf)
                        if (n <= 0) break
                        var i = 0
                        while (i < n) {
                            val c = buf[i]
                            if (total < MAX_CAPTURE_CHARS) {
                                capture.text.append(c)
                                total++
                            } else {
                                capture.truncated = true
                            }
                            if (c == '\n') {
                                emit(sink, line.toString(), isStdout)
                                line.setLength(0)
                            } else if (c != '\r') {
                                line.append(c)
                            }
                            i++
                        }
                    }
                    if (line.isNotEmpty()) emit(sink, line.toString(), isStdout)
                }
            } catch (t: Throwable) {
                // 进程被杀时流会提前关闭，这属于正常路径，不必刷屏
                Log.d(TAG, "pump ${if (isStdout) "stdout" else "stderr"} ended: ${t.javaClass.simpleName}")
            }
        }, if (isStdout) "rxin-pipe-out" else "rxin-pipe-err")
        thread.isDaemon = true
        thread.start()
        return thread
    }

    private fun emit(sink: Sink?, line: String, isStdout: Boolean) {
        if (sink == null) return
        runCatching {
            if (isStdout) sink.onStdout(line) else sink.onStderr(line)
        }
    }

    // ------------------------------------------------------------------ 杂项

    /** 把 `KEY=VALUE` 数组灌进进程环境（在应用自身环境之上覆盖写） */
    private fun applyEnv(pb: ProcessBuilder, env: Array<String>) {
        val map = pb.environment()
        for (entry in env) {
            val eq = entry.indexOf('=')
            if (eq <= 0) continue
            map[entry.substring(0, eq)] = entry.substring(eq + 1)
        }
    }

    private fun failure(message: String, started: Long): Result = Result(
        exitCode = -1,
        signal = null,
        timedOut = false,
        cancelled = false,
        stdout = "",
        stderr = "",
        truncated = false,
        interpreter = "-",
        feed = "-",
        workDir = "-",
        workDirAdjusted = false,
        originalBytes = 0,
        scriptBytes = 0,
        strippedBom = false,
        strippedCr = 0,
        reencoded = false,
        durationMs = System.currentTimeMillis() - started,
        error = message
    )

    private fun describe(t: Throwable): String =
        t.javaClass.simpleName + (t.message?.let { "：$it" } ?: "")
}
