package com.rxinfiles.tool

import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * sh 脚本执行支持。
 *
 * ## Android 上执行脚本的三个坑
 *
 * **1. `/sdcard` 不支持执行位。**
 * `/storage/emulated/0` 是 FUSE 挂载，权限位是虚拟的，`chmod +x` 不会生效
 * （所有文件永远是 `-rw-rw----`）。因此**绝不能直接执行脚本路径**，
 * 否则必然得到 `Permission denied`。正确做法是交给解释器：
 * `sh '/path/script.sh'`。这样既不依赖执行位，也不依赖 shebang 正确。
 *
 * **2. CRLF 换行会让 mksh 报语法错误。**
 * Windows 上编辑过的脚本带 `\r\n`，mksh 会把 `\r` 当作命令的一部分，
 * 报 `not found` 或 `syntax error: unexpected end of file`。
 *
 * **3. UTF-8 BOM 会破坏 shebang。**
 * 带 BOM 时首行变成 `\uFEFF#!/system/bin/sh`，`#!` 不再位于文件开头，
 * 内核的 shebang 识别失效，报 `not found`。
 *
 * **4. 「脚本」未必是文本 —— 加密/编译过的 `.sh` 只能直接执行。**
 * 真机实测（`tools/script-check/probe-enc-forms.sh`）：
 *
 * ```
 * $ sh 1.sh   →  syntax error: unexpected ')'   （mksh / dash / busybox ash 三家一致）
 * $ ./1.sh    →  正常执行
 * ```
 *
 * 这个组合**排除**了「文本脚本里多一个右括号」——那会让 `./1.sh` 报同一个错。
 * 真正的原因是文件是 `shc` 之类的「脚本加密」产物（**ELF**），或者首行 `#!`
 * 指向**自带解密器**：`./1.sh` 走内核 `execve`，`sh 1.sh` 是把二进制/密文
 * 当**文本**喂给解析器。见 [ScriptFormat]。
 *
 * 因此第 1 条的「一律交给解释器」必须留一个例外：**魔数命中**（ELF / 压缩包 / 图片，
 * 见 [ScriptFormat.magicKind]）、或 shebang 指向非解释器程序时，改为**直接执行文件本身**
 * （[Prepared.directExec]），否则这类脚本在 App 里永远跑不起来。
 *
 * **但例外不能扩大**（3.11.6 修正，踩过）：文件里出现 NUL 字节**不等于** sh 读不了它 ——
 * 脚本里嵌一段二进制载荷（自解压脚本、把数据塞进引号 heredoc 的脚本）就长这样，
 * 首行照样写着 `#!/bin/sh`，三家 shell 实测全都能正常跑完。3.11.1 把「含 NUL」
 * 和「魔数命中」当成了同一件事，于是这类**本来 `sh 文件` 就能跑**的脚本反而
 * 被强行改道，在共享存储上只剩一句「没有执行位」。判据必须分开两档，
 * 见 [ScriptFormat] 的类注释。
 *
 * 本对象负责在执行前做静态预检（只读，不改文件），并在用户确认后才修复换行符。
 */
object ScriptRunner {

    private const val TAG = "ScriptRunner"

    /** 预检只读前 8KB，避免大文件被整体读入内存 */
    private const val SNIFF_BYTES = 8 * 1024

    /** 换行符修复的最大文件尺寸，超过则不处理（脚本正常不会这么大） */
    private const val FIX_MAX_BYTES = 8 * 1024 * 1024

    /** 本机 sh 一定能跑的解释器，统一收敛到 `sh`，避免解释器不存在 */
    private val SHELL_LIKE = setOf("sh", "ash", "dash", "mksh", "ksh", "busybox", "toybox")

    /** [parseProbe] 认得的十六进制字符 —— 只认这些，别的行一律不当数据 */
    private const val HEX_CHARS = "0123456789abcdefABCDEF"

    /**
     * 「真的是某个语言的解释器」的名字集合 —— 在 [SHELL_LIKE] 之外再加上
     * [EXT_INTERPRETER] 里那些语言。
     *
     * 它的作用只有一个：**区分「解释器缺失」和「首行 #! 指的根本不是解释器」**。
     * 前者（如 `#!/usr/bin/python3` 而机器上没有 python3）回退 `sh` 尚可 —— 顶多
     * 是语法报错；后者（如 `#!/data/data/xxx/files/bin/rxe`，一个自带解密器）
     * 回退 `sh` 等于把密文交给 shell 解析，只会得到一堆 `unexpected ')'`。
     * 后者必须改为**直接执行文件本身**，让内核去拉起那个程序。
     */
    private val KNOWN_INTERPRETERS = SHELL_LIKE + setOf(
        "bash", "zsh", "python", "python3", "node", "perl", "ruby", "lua", "php"
    )

    /** shebang 中允许透传给解释器的开关（白名单）。[ScriptPipe] 复用同一份。 */
    internal val SAFE_SHEBANG_ARGS = setOf("-e", "-x", "-u", "-v")

    /** 依赖外部解释器的脚本扩展名 → 推断解释器 */
    private val EXT_INTERPRETER = mapOf(
        ".sh" to "sh",
        ".bash" to "bash",
        ".zsh" to "zsh",
        ".ash" to "sh",
        ".py" to "python3",
        ".js" to "node",
        ".mjs" to "node",
        ".pl" to "perl",
        ".rb" to "ruby",
        ".lua" to "lua"
    )

    // ------------------------------------------------------------------ 数据结构

    /** 预检结果（全部为静态信息，未修改任何文件） */
    data class Preflight(
        val path: String,
        /** 脚本所在目录，作为终端的初始工作目录 */
        val workDir: String,
        val hasBom: Boolean,
        val hasCrlf: Boolean,
        /** shebang 声明的解释器 basename，未声明则为 null */
        val shebangInterpreter: String?,
        /** shebang 的附加参数，例如 `-e` */
        val shebangArgs: String?,
        /** shebang 里那一项的**原样路径**（如 `/data/data/xxx/bin/rxe`），未声明则为 null */
        val shebangPath: String? = null,
        /**
         * 魔数命中（ELF / 压缩包 / 图片）—— **铁证**：sh 一定读不了它，只能由内核装载。
         *
         * 注意它**不是**「文件坏了」：加密/编译过的脚本本来就是这个样子。
         */
        val magicKind: String? = null,
        /**
         * 含 NUL，但文件**没有任何「读者声明」**（首行没有 `#!`，扩展名也不是脚本）。
         *
         * 这种文件两种可能都真实存在：脚本里嵌了载荷（`sh` 读得动），
         * 或者是编译/加密产物（只能直接执行）。App **不替用户拍板**，
         * 把判断交给真正执行它的身份（见 [Prepared.deferToShell] 与 [buildCommand]）。
         */
        val ambiguousPayload: Boolean = false,
        /**
         * 字节层面「不是纯文本」（魔数命中 或 含 NUL）。
         *
         * 它**只**回答一件事：能不能动这个文件的字节。[fixEncoding] 的动作是
         * 「删掉文件里的 `0x0D`」，对 ELF、对脚本里的二进制载荷都是**破坏**，
         * 所以这里一律照 [ScriptFormat.binaryKind] 拦下，不看 shebang、不问声明。
         *
         * **别拿它决定「用什么跑」**：含 NUL 的脚本交给 sh 完全没问题
         * （`probe-nul-script.sh` 实测三家 shell 都退 0），见 [ScriptFormat] 的类注释。
         */
        val binaryContent: Boolean = false,
        /**
         * App 读不到这个文件（`canRead()` 为假）。
         *
         * 常见于 `/data` 下由 root 建的目录 —— App 的普通身份连打开都不行。
         * **这一位很重要**：读不到就嗅探不了，[magicKind] 必然是 null，
         * 于是「编译/加密过的脚本要走直接执行」这条判断**整个失效**，
         * 又退回到 `sh <路径>` 那条老路（一屏语法错）。
         * 所以 [prepare] 必须知道「没嗅到」和「嗅出来是文本」是两回事。
         */
        val unreadable: Boolean = false,
        /**
         * 头部是**借执行身份**读来的（App 自己打不开这个文件，见 [prepare]）。
         *
         * 读到的东西照样能判形态，所以 [magicKind] / shebang 都可信；唯一不能用的是
         * **需要写回文件**的动作 —— App 写不了它，因此不能提供「修复编码」。
         */
        val sniffedViaShell: Boolean = false
    ) {
        /**
         * 是否存在会影响执行的编码问题。
         *
         * **二进制内容一律不算**（3.11.3 起，判据在 3.11.6 收紧为 [binaryContent]）。
         * ELF / 压缩包 / **脚本里的二进制载荷** 里的 `0x0D 0x0A` 是**数据**，不是 Windows
         * 换行 —— 而它们出现 `0D 0A` 完全是随机的。一旦在这里放行，用户会收到一个
         * 「脚本编码需要处理」的确认框，点了「修复并执行」就等于让 [fixEncoding]
         * 把文件里**所有** `0x0D` 删掉，那不是修复，是把用户的文件改坏。
         *
         * 判据因此是「**先得是纯文本**，再看有没有 CRLF / BOM」。
         *
         * **[sniffedViaShell] 的也要排除**（3.11.5）：App 连读都读不到的文件，
         * 必然也写不进去 —— 弹出「修复并执行」只会换来一句「修复失败」。
         */
        val hasEncodingIssue: Boolean
            get() = !binaryContent && !sniffedViaShell && (hasBom || hasCrlf)

        /** 供 UI 展示的问题描述 */
        fun issueText(): String = buildString {
            if (hasBom) {
                append("• 文件开头有 UTF-8 BOM，会让首行 #! 失效，报 “not found”\n")
            }
            if (hasCrlf) {
                append("• 使用了 Windows 换行符 (CRLF)，会让 shell 报语法错误\n")
            }
        }
    }

    /**
     * 一个可执行的准备结果。
     *
     * @param runner     实际用于执行脚本的解释器命令
     * @param usedFallback 解释器不可用、已回退为 `sh`
     * @param directExec 直接执行文件本身（内核按 shebang 或 ELF 装载），
     *                   不经过 `sh <路径>`。见 [ScriptFormat] 的说明。
     * @param directExecReason 走直接执行的原因描述，用于给用户的提示
     * @param deferToShell 形态**完全判不出来**（App 与执行身份都没能读到头部）时，
     *                     把「直接执行还是交给 sh」这个判断**交给 shell 现做**。
     *                     见 [buildCommand] 的对应分支。
     * @param readNotice App 读不到文件时的提醒（形态判不出来）；不需要提醒则为 null
     */
    data class Prepared(
        val preflight: Preflight,
        val runner: String,
        val usedFallback: Boolean,
        val requestedInterpreter: String?,
        val directExec: Boolean = false,
        val directExecReason: String? = null,
        val deferToShell: Boolean = false,
        val readNotice: String? = null
    ) {
        val scriptPath: String get() = preflight.path
        val workDir: String get() = preflight.workDir
    }

    // ------------------------------------------------------------------ 预检

    /**
     * 静态检查脚本。只读文件，不产生任何副作用。
     *
     * @param asRoot 该脚本将以 root 身份执行；仅用于决定「工作目录是否可达」的判断
     * @return 文件不存在或完全无法读取时返回 null
     */
    fun preflight(path: String, asRoot: Boolean = false): Preflight? {
        val file = File(path)
        val visibleFile = file.exists() && !file.isDirectory
        val rootFile = !visibleFile && asRoot && RootShell.succeeded("test -f ${quote(path)}")
        if (!visibleFile && !rootFile) {
            Log.w(TAG, "preflight: not a file: $path")
            return null
        }
        val workDir = resolveWorkDir(file, asRoot)
        if (!file.canRead()) {
            // 应用读不到不代表 shell 读不到（root 会话下 su 读得到，例如 /data 里的脚本）。
            // 这里只**如实记下「没读到」**，一个字都不猜 —— 换个身份去读是 [prepare] 的事，
            // 因为只有它知道这次到底用哪个身份执行（见 [probeAsIdentity]）。
            Log.w(TAG, "preflight: app cannot read, skip sniffing: $path")
            return Preflight(
                path = path,
                workDir = workDir,
                hasBom = false,
                hasCrlf = false,
                shebangInterpreter = null,
                shebangArgs = null,
                unreadable = true
            )
        }

        val head = readHead(file) ?: return Preflight(
            path, workDir, false, false, null, null
        )
        return analyze(path, workDir, head, viaShell = false)
    }

    /**
     * 由**已经读到的头部字节**构造预检结果。
     *
     * 抽出来是为了让两条路共用同一套判定：
     *  - App 自己读得到 → [preflight] 直接调；
     *  - App 读不到（`/data` 下 root 建的脚本）→ [prepare] 借执行身份读来同样的字节再调。
     *
     * **两条路必须走完全相同的逻辑**，否则「App 读得到」与「App 读不到」会得到
     * 两套结论，而后者正是编译器产物被误当文本脚本的根源。
     */
    private fun analyze(
        path: String,
        workDir: String,
        head: ByteArray,
        viaShell: Boolean
    ): Preflight {
        // 形态判据**分两档**，力度完全不同（见 [ScriptFormat] 的类注释）：
        //   魔数 = 铁证（ELF / 压缩包 / 图片里没有一行是给 shell 看的）；
        //   含 NUL = 只说明「不是纯文本」，脚本里嵌二进制载荷时也长这样，**sh 照样读得动**。
        val magic = ScriptFormat.magicKind(head)
        val nul = magic == null && ScriptFormat.hasNul(head)
        val binaryContent = magic != null || nul

        // **先判形态，再判文本层面的问题。**
        // 不是纯文本的文件下面这三样一概不适用：BOM / CRLF 是文本才有的概念，
        // shebang 更只是一行乱码……**不过最后这条只对魔数成立** —— 含 NUL 的脚本
        // 首行照样是给人看的 `#!`，所以 shebang 只在魔数命中时才跳过。
        val hasBom = !binaryContent && head.size >= 3 &&
            head[0] == 0xEF.toByte() && head[1] == 0xBB.toByte() && head[2] == 0xBF.toByte()

        var hasCrlf = false
        if (!binaryContent) {
            for (i in 0 until head.size - 1) {
                if (head[i] == '\r'.code.toByte() && head[i + 1] == '\n'.code.toByte()) {
                    hasCrlf = true
                    break
                }
            }
        }

        val parsed = if (magic == null) parseShebang(firstLine(head, hasBom)) else null

        // 「读者声明」：首行 `#!`，或扩展名是已知脚本类型（`.sh` / `.py`…）。
        // 有声明就说明作者是写给解释器看的，不该因为「含 NUL」被当成编译产物（3.11.6）。
        val declared = parsed != null || interpreterByExtension(path) != null

        return Preflight(
            path = path,
            workDir = workDir,
            hasBom = hasBom,
            hasCrlf = hasCrlf,
            shebangInterpreter = parsed?.interpreter,
            shebangArgs = parsed?.args,
            shebangPath = parsed?.path,
            magicKind = magic,
            ambiguousPayload = nul && !declared,
            binaryContent = binaryContent,
            sniffedViaShell = viaShell
        )
    }

    /**
     * 扩展名声明的解释器（`.sh` → `sh`、`.py` → `python3`），没有则 null。
     *
     * 它和 shebang 一起构成**「读者声明」** —— 文件自己说了「用谁读我」，
     * 就不该因为「含 NUL」被当成编译产物（3.11.6）。[ScriptPipe] 用的是同一份判断。
     */
    internal fun interpreterByExtension(path: String): String? =
        EXT_INTERPRETER[File(path).name.substringAfterLast('.', "").lowercase().let { ".$it" }]

    /**
     * 完整准备：预检 + 决定解释器 + 必要时探测解释器是否存在。
     *
     * 需要在 IO 线程调用（可能执行 `command -v`）。
     *
     * @param asRoot 与终端将要采用的执行身份一致 —— 探测必须和实际执行同一个身份，
     *               否则会出现「探测说没有 python，只能回退 sh」而实际上 root 下有的错位。
     */
    fun prepare(path: String, asRoot: Boolean = false): Prepared? {
        var pf = preflight(path, asRoot) ?: return null
        var readNotice: String? = null

        // ⓪ App 读不到这个文件 —— **换成借执行身份去读**，不是猜。
        //
        //    `preflight` 在读不到时会跳过全部嗅探，于是 `magicKind` 必然是 null。
        //    如果就这么往下走，判断会退化成「当它是文本脚本」→ 又回到 `sh <路径>`，
        //    把编译/加密过的脚本交给 shell 解析（一屏 `unexpected ')'`）——
        //    3.11.1 那条「二进制改为直接执行」的修正**整个失效**。
        //    根子在「没嗅到」与「嗅出来是文本」被当成了同一件事。
        //
        //    3.11.4 曾用 Java 的 `File.canExecute()` 兜底，那是错的：它判的是
        //    **App 自己**的身份，而真正执行脚本的可能是 su 里的 root。于是
        //    「root 明明跑得动」被判成「跑不动」，又走回 `sh`。
        //    真机案例：`/data/1/驱动.sh`（root 建的 shc 产物，App 打不开）——
        //    root 下 `./驱动.sh` 正常，App 里却只报 `syntax error: unexpected ')'`。
        //
        //    正确做法是让**执行身份自己**把那 8KB 读出来（[probeAsIdentity]）：
        //    读到了就照常判形态，而且走的是与「App 自己读得到」**完全相同**的
        //    [analyze]；读不到（真没权限，或连 `od` 都没有）才退到让 shell 现判。
        if (pf.unreadable) {
            val probe = probeAsIdentity(path, asRoot)
            val sniffed = probe?.head
            if (sniffed != null && sniffed.isNotEmpty()) {
                pf = analyze(path, pf.workDir, sniffed, viaShell = true)
                if (pf.hasBom || pf.hasCrlf) {
                    // 形态读出来了，但这个文件 App 写不了 —— 不能给「修复并执行」，
                    // 否则用户点完只会收到一句「修复失败」。
                    readNotice = "App 读不到这个文件（权限不足），没法替它修 BOM / 换行符，只能按原样执行。"
                }
            } else {
                // 连执行身份都读不出来：形态彻底未知，把判断权交给 shell。
                return Prepared(
                    preflight = pf,
                    runner = "sh",
                    usedFallback = false,
                    requestedInterpreter = null,
                    deferToShell = true,
                    readNotice = unreadableNotice(path, probe)
                )
            }
        }

        // ① 魔数命中（ELF / 压缩包 / 图片）：**铁证**，sh 一定读不了它 —— 只能由内核装载。
        //    把二进制当脚本文本解析的后果就是满屏 `syntax error: unexpected ')'`。
        pf.magicKind?.let { kind ->
            return Prepared(
                preflight = pf,
                runner = "sh",
                usedFallback = false,
                requestedInterpreter = null,
                directExec = true,
                directExecReason = kind,
                readNotice = readNotice
            )
        }

        // ② 含 NUL、又没有「读者声明」（首行没有 `#!`，扩展名也不是脚本）：形态真的判不出来。
        //    两种可能都实在 —— 脚本里嵌了载荷（sh 读得动），或编译/加密产物（只能直接执行）。
        //    App **不替用户拍板**，把 `[ -x ]` 那一问交给真正执行它的身份现判（3.11.6）。
        //
        //    3.11.1~3.11.5 在这里一律判成「编译产物 → 直接执行」，于是「本来 `sh 文件`
        //    就能跑的脚本」在 App 里反而跑不了 —— 有执行位的还能直接跑，没有的
        //    （比如 /sdcard 上的）只剩一句「没有执行位，无法执行」。用户的反馈正是
        //    「有些本来就可以直接 sh 执行的非纯文本脚本，sh 又执行不了了」。
        if (pf.ambiguousPayload) {
            return Prepared(
                preflight = pf,
                runner = "sh",
                usedFallback = false,
                requestedInterpreter = null,
                deferToShell = true,
                readNotice = readNotice ?: payloadNotice(path)
            )
        }

        // ③ 其余都按「脚本」处理 —— **包括「含 NUL 但声明了读者」的那些**（3.11.6）：
        //    首行 `#!` / `.sh` 扩展名就是作者写给 shell 看的，sh 读得动
        //    （`tools/script-check/probe-nul-script.sh` 实测三家 shell 全退 0）。
        val requested = pf.shebangInterpreter
            ?: interpreterByExtension(path)

        return when {
            requested == null -> Prepared(pf, "sh", false, null, readNotice = readNotice)
            requested.lowercase() in SHELL_LIKE ->
                Prepared(pf, "sh", false, requested, readNotice = readNotice)
            interpreterAvailable(requested, asRoot) ->
                Prepared(pf, requested, false, requested, readNotice = readNotice)
            // ② 解释器探测不到，分两种：
            //    是已知解释器家族（python3 / node / perl…）→ 回退 sh，维持原有行为；
            //    其它名字 → 首行 #! 指的**不是解释器**（自带解密器之类），
            //    回退 sh 只会把密文喂给 shell，改为直接执行文件本身。
            requested.lowercase() in KNOWN_INTERPRETERS ->
                Prepared(pf, "sh", true, requested, readNotice = readNotice)
            else -> Prepared(
                preflight = pf,
                runner = "sh",
                usedFallback = true,
                requestedInterpreter = requested,
                directExec = true,
                directExecReason = "首行 #! 指向 $requested，它不是 shell 解释器",
                readNotice = readNotice
            )
        }
    }

    /**
     * 借**脚本将要采用的那个执行身份**去读文件头部与权限位。
     *
     * 为什么必须借身份：能读/能执行这个文件的，是真正去跑它的那个身份，不是 App。
     * App 的普通身份读不到 `/data` 下 root 建的文件，而用户选的 ROOT 会话读得到 ——
     * 这正是本函数存在的唯一理由。**判据要跟执行者一致**（与 [interpreterAvailable]
     * 同一条道理，那里也是按身份探测的）。
     *
     * 一条命令取回三样东西：
     *  - `##r1/##r0` 当前身份读得到吗
     *  - `##x1/##x0` 当前身份的执行位（只用来写提示文案，真正的判断仍在 shell 里现做）
     *  - 头部 [SNIFF_BYTES] 字节的十六进制流（`od` 的输出只有十六进制与空格，好解析）
     *
     * **刻意不依赖专用二进制**：`head`/`od` 都是 toybox 自带的，缺失时只是读不到头部，
     * 不影响 `##r?##x?` 两行，调用方会退到「让 shell 现判」那条路，不会误判成文本。
     */
    private fun probeAsIdentity(path: String, asRoot: Boolean): IdentityProbe? {
        val q = quote(path)
        val cmd = withSandboxPath(
            "[ -r $q ] && printf '##r1\\n' || printf '##r0\\n'; " +
                "[ -x $q ] && printf '##x1\\n' || printf '##x0\\n'; " +
                "head -c $SNIFF_BYTES $q 2>/dev/null | od -An -v -tx1 2>/dev/null"
        )
        val out = try {
            if (asRoot) ShellExecutor.exec(cmd) else ShellExecutor.execLocal(cmd)
        } catch (t: Throwable) {
            Log.w(TAG, "probeAsIdentity failed: $path", t)
            null
        }
        if (out == null) {
            Log.w(TAG, "probeAsIdentity: no output (root=${asRoot}): $path")
            return null
        }
        return parseProbe(out)
    }

    /** 借执行身份探出来的文件事实；**每项都可能为 null**（读不出来的就不猜） */
    private class IdentityProbe(
        /** 头部字节；`head`/`od` 缺失或读不到时为 null */
        val head: ByteArray?,
        /** 当前身份读得到吗 */
        val readable: Boolean?,
        /** 当前身份的执行位 */
        val executable: Boolean?
    )

    /**
     * 解析 [probeAsIdentity] 的输出。
     *
     * 严格只认两种行：`##r?` / `##x?` 标记，以及**全部由十六进制字符与空格组成**的行
     * （`od` 的输出）。别的行（`command not found`、SELinux 的拒绝话术……）一律丢弃 ——
     * 宁可不认，也不能把一句错误提示的字节当成文件头去判形态。
     *
     * 十六进制行还有第二道门槛：**必须出现在标记行之后**。su 的持久会话有可能把命令回显
     * 出来（命令里就带着 `##r1` 这些字面量），只靠「整行像十六进制」挡不住那种回显。
     * 标记行是 printf 打出来的、必然晚于回显，所以「先见标记、后收十六进制」能把回显整段排除。
     *
     * 一行都没认出来就返回 null，表示「这次探测什么也没拿到」。
     */
    private fun parseProbe(out: String): IdentityProbe? {
        var readable: Boolean? = null
        var executable: Boolean? = null
        var recognized = false
        var seenMarker = false
        val hex = StringBuilder()

        for (raw in out.lineSequence()) {
            val line = raw.trim()
            when (line) {
                "##r1" -> { readable = true; recognized = true; seenMarker = true }
                "##r0" -> { readable = false; recognized = true; seenMarker = true }
                "##x1" -> { executable = true; recognized = true; seenMarker = true }
                "##x0" -> { executable = false; recognized = true; seenMarker = true }
                "" -> Unit
                else -> {
                    if (!seenMarker) continue
                    if (!line.all { it in HEX_CHARS || it == ' ' }) continue
                    hex.append(line.filter { it in HEX_CHARS })
                    recognized = true
                }
            }
        }
        if (!recognized) return null

        var bytes: ByteArray? = parseHex(hex.toString())
        if (bytes != null && bytes.isEmpty()) bytes = null
        return IdentityProbe(bytes, readable, executable)
    }

    /** 十六进制串 → 字节数组；出现非十六进制字符或长度为奇数时返回 null */
    private fun parseHex(hex: String): ByteArray? {
        if (hex.length % 2 != 0) return null
        val out = ByteArray(hex.length / 2)
        var i = 0
        while (i < out.size) {
            val hi = Character.digit(hex[i * 2], 16)
            val lo = Character.digit(hex[i * 2 + 1], 16)
            if (hi < 0 || lo < 0) return null
            out[i] = ((hi shl 4) or lo).toByte()
            i += 1
        }
        return out
    }

    /**
     * 形态彻底判不出来时的提醒文案。
     *
     * 这里只说**已经确知的事实**（读没读到、有没有执行位），不编造形态结论；
     * 最后一句把「接下来是谁在判」讲清楚，免得用户以为 App 又在瞎猜。
     */
    private fun unreadableNotice(path: String, probe: IdentityProbe?): String {
        val name = File(path).name
        val sb = StringBuilder("App 读不到 ${name}（权限不足），没法判断它是文本还是编译产物。")
        when {
            probe == null -> sb.append("借执行身份去读也没成功，只能交给 sh 试。")
            probe.executable == false ->
                sb.append("它在当前身份下也没有执行位 —— 若下一行报 Permission denied，请改用 ROOT 身份再试。")
            else ->
                sb.append("执行时会在同一个身份下用 [ -x ] 现判：有执行位就直接跑它本身，没有才交给 sh。")
        }
        return sb.toString()
    }

    /**
     * 含 NUL 又没有任何「读者声明」时的提醒文案（见 [Prepared.deferToShell]）。
     *
     * 说的是实话：形态**确实**判不出来，所以执行方式交给 shell 现判，
     * 不编造一个「它一定是编译产物」的结论 —— 3.11.1~3.11.5 就是那么判死的。
     */
    private fun payloadNotice(path: String): String {
        return "判不出 ${File(path).name} 是脚本还是编译产物：它含 NUL 字节（不是纯文本），" +
            "但首行没有 #!、扩展名也不是脚本。执行时会现判 —— 有执行位就直接跑它本身，" +
            "没有就交给 sh。"
    }

    /**
     * 探测解释器是否存在。
     *
     * 必须**与终端将要采用的身份一致**：
     *  - 普通会话是应用自身 uid，走 [ShellExecutor.execLocal]；
     *  - 用户显式选了「以 ROOT 执行」时终端是 su 会话，就走 [ShellExecutor.exec]。
     *
     * 若统一按自动探测到的最高级别来探，会出现
     * 「探测说有 python，脚本一跑却 command not found」的错位。
     *
     * 还必须**带上终端的 PATH**（见 [withSandboxPath]）：内置工具箱往沙箱里
     * 铺了 `bash` 之类的垫片，终端里敲得到，探测要是看不见就会白白回退成 `sh`。
     *
     * `command -v` 是 mksh 内置，老式 shell 上没有时退回 `which`。
     */
    private fun interpreterAvailable(name: String, asRoot: Boolean): Boolean {
        if (!name.matches(Regex("[A-Za-z0-9_.+-]+"))) return false
        val probe = withSandboxPath("command -v $name 2>/dev/null || which $name 2>/dev/null")
        return try {
            val out = if (asRoot) ShellExecutor.exec(probe) else ShellExecutor.execLocal(probe)
            if (out == null) return false
            val trimmed = out.trim()
            trimmed.isNotEmpty() && !trimmed.contains("not found", ignoreCase = true)
        } catch (t: Throwable) {
            Log.w(TAG, "interpreterAvailable($name, root=$asRoot) failed", t)
            false
        }
    }

    /**
     * 给一次性探测命令套上沙箱 PATH。
     *
     * `PATH='<沙箱bin>:<沙箱extra>':"$PATH"` —— 单引号段与 `"$PATH"` 直接拼接，
     * 这是合法的 shell 赋值；沙箱路径里出现空格也不会散架。
     * 工具箱不可用时原样返回，探测结果与终端保持一致。
     */
    private fun withSandboxPath(cmd: String): String {
        val prefix = RxinToolbox.pathPrefix ?: return cmd
        return "PATH=${quote(prefix)}:\"\$PATH\"; export PATH; $cmd"
    }

    // ------------------------------------------------------------------ 命令构造

    /**
     * 构造写入终端的执行命令。
     *
     * 普通形态：
     * ```
     * sh '/sdcard/RouXin/demo.sh' arg1 ; __rxin_rc=$?; printf '\n[RouXin] 脚本已结束，退出码 %s\n' "$__rxin_rc"
     * ```
     *
     * 直接执行形态（[Prepared.directExec]，见 [ScriptFormat]）：
     * ```
     * if [ -x '/sdcard/RouXin/x.sh' ]; then '/sdcard/RouXin/x.sh' arg1; else printf '…没有执行位…'; fi ; __rxin_rc=$?; …
     * ```
     * 多出来的 `[ -x ]` 一层是必要的：内核装载必须有执行位，共享存储上永远没有，
     * 与其让用户对着 `Permission denied` 发懵，不如直接说明原因。
     * 判断**放在 shell 里做**而不是用 Java 的 `File.canExecute()` —— 它要按
     * **真正执行它的那个身份**来判，而 root 会话与普通会话看到的执行位可能不同。
     *
     * 形态判不出来时（[Prepared.deferToShell]）走第三种形态，连「用什么跑」都由
     * shell 现判：
     * ```
     * if [ -x '/data/1/x.sh' ]; then '/data/1/x.sh'; else sh '/data/1/x.sh'; fi ; __rxin_rc=$?; …
     * ```
     * 这一支是 3.11.5 补的：`/data` 下 root 建的编译产物 App 读不到，只能让
     * 真正执行它的身份去判。`fi` 之后 `$?` 就是所走分支的退出码，原样透出。
     *
     * 结尾的提示**在脚本退出之后**才输出，因此不会介入脚本自身的交互；
     * 它的作用是让用户明确知道脚本已结束（脚本里的 `read` 阻塞时不会有任何额外输出）。
     *
     * 退出码先存进变量再打印：`printf` 的格式化参数虽然先于它自身求值，
     * 但脚本很可能自己就动过 `$?`，显式把结果留在 `__rxin_rc` 里最稳，
     * 也便于事后在终端里 `echo $__rxin_rc` 复查。
     *
     * **末尾绝不 `exit` 外层 shell**（3.11.2 起，3.13.1 仍然守这条）：脚本跑在交互式
     * shell 的**子进程**里，命令尾一 `exit`，PTY 就随会话一起被清理，而清理会把输出
     * 队列关掉 —— reader 线程还没来得及送出去的最后几 KB 会**静默丢掉**，
     * 那恰好是用户最想看的几行。所以让脚本**自然结束、停在提示符上**，
     * 由 App 侧在收到 [ScriptDone] 标记之后再把会话收掉
     * （那时输出早已全部渲染上屏，见 `TerminalActivity.markScriptFinished`）。
     *
     * 原生 App 的命令尾带 [ScriptDone.TAIL]：一条 OSC 2（设置窗口标题）转义序列，
     * 屏幕上不显示任何字符，只为让 App 知道「脚本跑完了，退出码是多少」。
     * 网页终端没有原生标题回调，调用方可关闭标记并保留会话继续交互。
     *
     * @param doneMarker 是否追加原生终端结束标记；默认保持 App 原有行为
     */
    fun buildCommand(prepared: Prepared, extraArgs: String?, doneMarker: Boolean = true): String {
        val sb = StringBuilder()
        val args = extraArgs?.trim().orEmpty()

        if (prepared.deferToShell) {
            // 形态未知（App 与执行身份都没读到头部）：**把判据交给 shell**。
            // 能不能执行取决于**真正执行它的那个身份**，Java 侧永远判不准 ——
            // App 判「没有执行位」而 root 明明能跑，就是这样错开的。
            // 有执行位就直接跑它本身（编译/加密过的脚本只能这样跑），
            // 没有就照旧交给 sh（`/sdcard` 上的普通脚本永远走这一支）。
            // `$?` 在 `fi` 之后就是所走分支最后一条命令的退出码，能原样透出。
            val q = quote(prepared.scriptPath)
            sb.append("if [ -x ").append(q).append(" ]; then ").append(q)
            if (args.isNotEmpty()) sb.append(' ').append(args)
            sb.append("; else sh ").append(q)
            if (args.isNotEmpty()) sb.append(' ').append(args)
            sb.append("; fi")
        } else if (prepared.directExec) {
            val q = quote(prepared.scriptPath)
            sb.append("if [ -x ").append(q).append(" ]; then ").append(q)
            if (args.isNotEmpty()) sb.append(' ').append(args)
            sb.append("; else printf '\\n[RouXin] %s 没有执行位，无法执行。\\n' ")
                .append(quote(File(prepared.scriptPath).name))
            sb.append("; printf '[RouXin] 这个文件是编译/加密过的，只能由内核装载。\\n'")
            sb.append("; printf '[RouXin] 共享存储（/sdcard）不支持执行位，chmod 也无效，需放到支持执行位的目录。\\n'")
            sb.append("; printf '[RouXin] 若它本来就在 /data 这类能记权限的目录里，用 ROOT 身份把执行位加上（chmod 755）即可。\\n'")
            // 收在一个失败状态上：末尾那段会把 $? 原样变成「退出码」，不能让它假装成功
            sb.append("; false; fi")
        } else {
            sb.append(runnerCommand(prepared)).append(' ').append(quote(prepared.scriptPath))
            if (args.isNotEmpty()) sb.append(' ').append(args)
        }

        sb.append(
            "; __rxin_rc=\$?; printf '\\n[RouXin] 脚本已结束，退出码 %s\\n' " +
                "\"\$__rxin_rc\""
        )
        // 126 / 127 这两个退出码在 Android 上几乎总是**环境问题**，不是脚本问题 ——
        // 可用户看到的是内核或壳层甩出来的一句话（有时连一句都没有）。
        // 判据放在 shell 里，按**真正执行它的那个身份**判：同一份文件，ROOT 跑得动、
        // App 的普通身份被 SELinux 拦住，是这片环境里最常见的组合。
        sb.append(
            "; case \$__rxin_rc in" +
                " 126) printf '[RouXin] 126 = 文件在，但内核或安全策略拒绝执行它（Permission denied）。\\n" +
                "[RouXin] 最可能的原因：SELinux 不允许「当前身份」执行这个文件 —— App 的普通身份限制最多。\\n" +
                "[RouXin] 回到列表页长按它，改用 ROOT 身份再试一次。\\n' ;;" +
                " 127) printf '[RouXin] 127 = 找不到命令，或它的解释器不存在（首行 #! 指向的路径没有）。\\n' ;;" +
                " esac"
        )

        // 原生终端用结束标记收掉 PTY；网页终端保留会话供后续交互，不输出控制序列。
        if (doneMarker) sb.append(ScriptDone.TAIL)
        return sb.toString()
    }

    /**
     * 解释器命令，附上 shebang 里允许透传的开关。
     *
     * 只放行白名单内的开关（`-e` 出错即停、`-x` 回显命令等），
     * 避免把 shebang 里的任意参数带进命令行改变执行语义。
     */
    private fun runnerCommand(prepared: Prepared): String {
        val raw = prepared.preflight.shebangArgs ?: return prepared.runner
        val safe = raw.split(Regex("\\s+")).filter { it in SAFE_SHEBANG_ARGS }
        return if (safe.isEmpty()) prepared.runner else prepared.runner + " " + safe.joinToString(" ")
    }

    /** 回退提示：解释器不存在时，在终端里先说明再执行 */
    fun fallbackNotice(prepared: Prepared): String? {
        if (prepared.usedFallback && prepared.directExec) return null
        if (!prepared.usedFallback) return null
        val requested = prepared.requestedInterpreter ?: return null
        return "printf '[RouXin] 未找到 %s，已改用 sh 执行（脚本若依赖 %s 语法可能报错）\\n' " +
            "${quote(requested)} ${quote(requested)}"
    }

    /**
     * 直接执行的说明：为什么不用 `sh <路径>`。
     *
     * 用户看到的原始现象往往是「`sh 1.sh` 报一堆语法错误、`./1.sh` 却正常」，
     * 这里把原因一句话讲清楚，免得又去查括号配平。
     */
    fun directExecNotice(prepared: Prepared): String? {
        if (!prepared.directExec) return null
        val reason = prepared.directExecReason ?: return null
        return "printf '[RouXin] 改为直接执行该文件本身。原因：%s\\n' " + quote(reason)
    }

    /** 读不到文件（形态判不出来）的提醒 */
    fun readNoticeOf(prepared: Prepared): String? {
        val text = prepared.readNotice ?: return null
        return "printf '[RouXin] 提示：%s\\n' " + quote(text)
    }

    /**
     * 写入终端的那行说明。
     *
     * 三条分支互斥、按信息量排序：读不到（形态未知）→ 直接执行（为什么不用 sh）
     * → 解释器回退。一条都不命中就返回 null，终端里不多打一行废话。
     */
    fun notice(prepared: Prepared): String? = when {
        prepared.readNotice != null -> readNoticeOf(prepared)
        prepared.directExec -> directExecNotice(prepared)
        else -> fallbackNotice(prepared)
    }

    /** 这个 shebang 名字是「某个语言的解释器」吗（区分「解释器缺失」与「压根不是解释器」） */
    internal fun isKnownInterpreter(name: String): Boolean =
        name.lowercase() in KNOWN_INTERPRETERS

    /** POSIX 单引号转义，返回可直接放进命令行的字面量 */
    fun quote(raw: String): String = "'" + raw.replace("'", "'\\''") + "'"

    // ------------------------------------------------------------------ 编码修复

    /**
     * 修复换行符与 BOM。
     *
     * 会把当前原文件备份为 `<path>.bak`，再通过同目录临时文件原子替换为
     * 去掉 BOM、统一为 LF 的内容。
     *
     * 这是**唯一**会修改用户文件的动作，且只应在本对象报告
     * [Preflight.hasEncodingIssue] 且用户明确同意后调用。
     *
     * **二进制文件一律拒绝**（3.11.3 起）：本函数的动作是「删掉文件里的 `0x0D`」，
     * 对 ELF 来说那就是在破坏文件。上层 [Preflight.hasEncodingIssue] 已经拦了一道，
     * 这里再拦一道是为了防住「别处直接调用」—— 宁可什么都不做，也不能改坏文件。
     *
     * @return 成功返回 true；文件过大、是二进制或读写失败返回 false
     */
    fun fixEncoding(path: String): Boolean {
        val file = File(path)
        if (!file.isFile) return false
        if (file.length() > FIX_MAX_BYTES) {
            Log.w(TAG, "fixEncoding: file too large: ${file.length()}")
            return false
        }
        if (isBinaryFile(file)) {
            Log.w(TAG, "fixEncoding: refuse to modify binary file: $path")
            return false
        }

        val parent = file.absoluteFile.parentFile ?: return false
        val temporary = File(parent, ".rxin_encoding_${System.nanoTime()}_${file.name}")
        return try {
            val raw = file.readBytes()
            val withoutBom = if (raw.size >= 3 &&
                raw[0] == 0xEF.toByte() && raw[1] == 0xBB.toByte() && raw[2] == 0xBF.toByte()
            ) raw.copyOfRange(3, raw.size) else raw

            val fixed = normalizeNewlines(withoutBom)
            if (fixed.contentEquals(raw)) return true

            Files.copy(
                file.toPath(), temporary.toPath(),
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES
            )
            FileOutputStream(temporary, false).use { output ->
                output.write(fixed)
                output.fd.sync()
            }

            val backup = File(path + ".bak")
            Files.copy(
                file.toPath(), backup.toPath(),
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES
            )
            Files.move(
                temporary.toPath(), file.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING
            )
            Log.i(TAG, "fixEncoding: normalized ${file.name}, backup=${backup.name}")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "fixEncoding failed: $path", t)
            false
        } finally {
            temporary.delete()
        }
    }

    /**
     * **能不能动这个文件的字节**的闸门 —— 不是「能不能跑它」的判据。
     *
     * 判据用 [ScriptFormat.binaryKind]（魔数命中 或 含 NUL），**故意不含 shebang / 扩展名**：
     * 一个「`#!/bin/sh` + 二进制载荷」的脚本当然能跑，可它同样**经不起**
     * 「删掉所有 `0x0D`、去掉 BOM」这种改写 —— [fixEncoding] 的动作就是那个。
     * 说白了：能跑 ≠ 能改。两者在这里分别是 [Preflight.ambiguousPayload]/[Preflight.magicKind]
     * 与 [Preflight.binaryContent]，别把判据挪过来。
     *
     * 只读开头 [ScriptFormat.SNIFF_BYTES] 字节，无副作用。
     * 读不到时按「不是二进制」处理 —— 读不到的文件本来也改不了。
     */
    private fun isBinaryFile(file: File): Boolean {
        val head = readHead(file) ?: return false
        return ScriptFormat.binaryKind(head) != null
    }

    /** `\r\n` 与孤立的 `\r` 统一成 `\n` */
    private fun normalizeNewlines(raw: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(raw.size)
        var i = 0
        while (i < raw.size) {
            val b = raw[i]
            if (b == '\r'.code.toByte()) {
                out.write('\n'.code)
                if (i + 1 < raw.size && raw[i + 1] == '\n'.code.toByte()) {
                    i += 2
                    continue
                }
            } else {
                out.write(b.toInt())
            }
            i += 1
        }
        return out.toByteArray()
    }

    // ------------------------------------------------------------------ 内部工具

    private fun readHead(file: File): ByteArray? = try {
        RandomAccessFile(file, "r").use { raf ->
            val len = minOf(file.length(), SNIFF_BYTES.toLong()).toInt()
            val buf = ByteArray(len)
            raf.readFully(buf)
            buf
        }
    } catch (t: Throwable) {
        Log.w(TAG, "readHead failed: ${file.path}", t)
        null
    }

    /**
     * 取第一行内容（跳过 BOM），已去掉行尾 \r。
     *
     * 遇到 NUL 就停：含 NUL 的脚本里 `#!` 那一行后面可能紧跟载荷，
     * 而 NUL 从来不是 shebang 的一部分。不停的话 `File(head).name` 会变成
     * `sh\u0000\u0000…`，解释器就认不出来了（3.11.6）。
     */
    private fun firstLine(bytes: ByteArray, hasBom: Boolean): String {
        val start = if (hasBom) 3 else 0
        val sb = StringBuilder()
        var i = start
        while (i < bytes.size) {
            val c = bytes[i].toInt().toChar()
            if (c == '\n' || c == '\r' || c == '\u0000') break
            sb.append(c)
            i += 1
        }
        return sb.toString()
    }

    /**
     * shebang 的解析结果。
     *
     * @param interpreter 解释器的 basename（`/usr/bin/env python3` 取 `python3`）
     * @param path        原样保留的第一项，用于提示文案与排查
     * @param args        除第一项之外的其余参数
     */
    private data class Shebang(val interpreter: String, val path: String, val args: String?)

    /**
     * 解析 shebang。
     *
     * - `#!/system/bin/sh`          → ("sh", "/system/bin/sh", null)
     * - `#!/system/bin/sh -e`       → ("sh", "/system/bin/sh", "-e")
     * - `#!/usr/bin/env python3 -u` → ("python3", "python3", "-u")
     * - 普通首行                     → null
     */
    private fun parseShebang(line: String): Shebang? {
        if (!line.startsWith("#!")) return null
        val body = line.substring(2).trim()
        if (body.isEmpty()) return null

        val parts = body.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null

        val head = parts[0]
        val headName = File(head).name

        // env 只消费自己的选项；解释器后面的 -u 等参数必须原样保留。
        if (headName == "env") {
            var cursor = 1
            while (cursor < parts.size) {
                val token = parts[cursor]
                if (token == "--") {
                    cursor++
                    break
                }
                if (!token.startsWith("-")) break
                cursor += if (token == "-u" || token == "--unset" || token == "-C" ||
                    token == "--chdir"
                ) 2 else 1
            }
            if (cursor >= parts.size) return null
            val interpreterPath = parts[cursor]
            val interp = File(interpreterPath).name
            val args = parts.drop(cursor + 1).joinToString(" ").takeIf { it.isNotEmpty() }
            return Shebang(interp, interpreterPath, args)
        }

        val args = parts.drop(1).joinToString(" ").takeIf { it.isNotEmpty() }
        return Shebang(headName, head, args)
    }

    /**
     * 计算脚本可信的初始工作目录。
     *
     * 普通用户会话以应用自身 uid 运行：可以进入应用**自己的**私有目录，
     * 但进不了别的应用目录、/data/system 这类地方 —— 把这样的目录作为 cwd
     * 会让 shell 启动即报 permission denied，所以遇到非自身的私有目录时退回到外部存储根目录。
     *
     * root 会话则不需要这层退让：su 能进任何目录，脚本所在目录就是最自然的 cwd
     * （脚本里常见的 `./xxx`、`source ./lib.sh` 才不会指向别处）。
     */
    private fun resolveWorkDir(file: File, asRoot: Boolean): String {
        val parent = try {
            file.canonicalFile.parentFile?.absolutePath
        } catch (t: Throwable) {
            file.absoluteFile.parent
        } ?: "/sdcard"

        if (asRoot) return parent

        val ownDataDirs = listOf(
            "/data/data/${BuildConfig.APPLICATION_ID}",
            "/data/user/0/${BuildConfig.APPLICATION_ID}"
        )
        if (ownDataDirs.any { parent.startsWith(it) }) return parent

        return if (PRIVATE_PREFIXES.any { parent.startsWith(it) }) "/sdcard" else parent
    }

    private val PRIVATE_PREFIXES = listOf(
        "/data/data/",
        "/data/user/",
        "/data/misc/",
        "/data/system/",
        "/data/app/"
    )

    /** 是否是可以直接交给终端执行的脚本类型 */
    fun isExecutableScriptName(name: String): Boolean {
        val lower = name.lowercase()
        if (lower.endsWith(".shinit")) return true
        val ext = "." + lower.substringAfterLast('.', "")
        return EXT_INTERPRETER.containsKey(ext)
    }
}
