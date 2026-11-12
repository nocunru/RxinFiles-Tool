package com.rxinfiles.tool

/**
 * **脚本形态识别** —— 判断一个文件到底是不是「能被 sh 解析的文本脚本」。
 *
 * ## 为什么需要它
 *
 * 真机实测（见 `tools/script-check/probe-enc-forms.sh` 与 `升级说明.md` 3.11.1）：
 *
 * ```
 * $ sh 1.sh        →  syntax error: unexpected ')'      （mksh / dash / busybox ash 三家一致）
 * $ ./1.sh         →  正常执行
 * ```
 *
 * 这个组合**不可能是文本脚本**。文本脚本里真多一个右括号时，`./1.sh` 会报**同一个错**
 * （同一个解释器读同一份文本）—— 差异本身就是证据。真正的原因是 `1.sh` 是
 * **编译/加密过的产物**，`./1.sh` 走内核 `execve`（读 shebang 或直接装载 ELF），
 * 而 `sh 1.sh` 是把文件当**文本**喂给解析器，解析器看到的是二进制/密文。
 *
 * 两种常见形态：
 *
 * 1. **ELF 可执行文件**（`shc` 之类的「脚本加密」工具产出）：前 4 字节 `7F 45 4C 46`。
 *    实测三家 shell 都在**第 1 行**报 `unexpected ')'` —— ELF 头里的字节凑巧造出了这个 token。
 * 2. **首行 `#!` 指向自带的解密器**（非标准 shell 路径，如 `/data/data/xxx/files/bin/rxe`）：
 *    `./1.sh` 时内核把该程序拉起来、由它读文件解密；`sh 1.sh` 时 mksh 把 `#!` 当注释、
 *    直接解析后面的密文。
 *
 * 两种形态都**只能直接执行**，不能 `sh <路径>`。终端那条通道原来的做法偏偏是
 * 「一律 `sh '<路径>'`」（因为 `/sdcard` 没有执行位，见 [ScriptRunner] 的注释），
 * 于是对这类文件必然失败。本对象就是把「它其实不是文本」这件事判定出来，
 * 让调用方改用直接执行。
 *
 * ## 判据只用两条，但**力度完全不同**（3.11.6 起分家）
 *
 * 1. **魔数**（[magicKind]）：见 [MAGICS]。命中就是铁证 —— ELF / 压缩包 / 图片，
 *    里面**没有**任何一行是给 shell 看的，`sh` 怎么读都是乱码。**只能直接执行**。
 * 2. **NUL 字节**（[hasNul]）：前 [SNIFF_BYTES] 字节里出现 `0x00`。
 *    它只说明**这个文件不是纯文本**，**不等于** shell 读不了它 —— 见下。
 *
 * **「含 NUL」必须单独对待，不能和魔数一样判死**（3.11.6 修正，踩过）：
 * 脚本里嵌一段二进制载荷是很常见的写法（自解压安装脚本、一体化脚本、把数据
 * 塞进引号 heredoc 的那些）。这类文件 `#!/bin/sh` 就写在第一行，**sh 完全跑得动**。
 *
 * 实测（`tools/script-check/probe-nul-script.sh`，三份含 NUL 的夹具 ×
 * mksh / dash / busybox ash）：载荷放在 `exit 0` 之后、或包在引号 heredoc 里时，
 * 三家 shell **全部退出码 0、正常打印、没有把载荷当命令**；连「没有 shebang」的那份也能跑。
 *
 * 副作用也要说清楚：三家 shell 都会**悄悄丢掉载荷里的 NUL 字节**
 * （实测 355 B 的载荷落盘成 353 B）—— 所以「sh 能跑」是真的，
 * 「载荷字节百分百保真」不是。这属于 shell 自己的行为，不是本 App 能左右的。
 *
 * 由此得出两条**用途不同**的判据，别混用：
 *
 * - **能不能交给 sh** → 魔数命中才不行；只含 NUL 的要再看「文件自己声明了读者吗」
 *   （首行 `#!` 或已知脚本扩展名，见 [ScriptRunner.prepare]）。
 * - **能不能动它的字节**（编码修复）→ 含 NUL 就不许动。判据用 [binaryKind]，
 *   **它不看 shebang、也不问声明**，只认字节事实。
 *
 * **UTF-16 的文件不算二进制**：它以 `FF FE` / `FE FF` 开头，正文必然带大量 NUL，
 * 但内容是可解码的文本，由 [ScriptPipe.preprocess] 负责转成 UTF-8。
 */
object ScriptFormat {

    /** 判定只需看文件开头这么多字节 */
    const val SNIFF_BYTES = 8 * 1024

    /**
     * 「不是文本脚本」的魔数表：`十六进制（小写、无分隔）→ 可读描述`。
     *
     * 只收**无法被 shell 解析**的格式。刻意**不收** `MZ`（PE）—— 两字节太容易和
     * 文本撞车（`MZ=1` 就是个合法的 shell 赋值）；这类漏网的会由 NUL 判据兜住。
     *
     * **这份清单与 `tools/script-check/diagnose-sh.sh` 的判据必须一致**，
     * `tools/script-check/verify-scriptpipe.py` 会静态比对两处，改一处漏一处会当场失败。
     */
    private val MAGICS: List<Pair<String, String>> = listOf(
        "7f454c46" to "ELF 可执行文件",
        "cafebabe" to "Java class",
        "cffaedfe" to "Mach-O 可执行文件",
        "cefaedfe" to "Mach-O 可执行文件",
        "feedface" to "Mach-O 可执行文件",
        "feedfacf" to "Mach-O 可执行文件",
        "1f8b" to "gzip 压缩数据",
        "1f9d" to "compress(.Z) 压缩数据",
        "425a68" to "bzip2 压缩数据",
        "fd377a585a00" to "xz 压缩数据",
        "28b52ffd" to "zstd 压缩数据",
        "04224d18" to "lz4 压缩数据",
        "377abcaf271c" to "7z 压缩包",
        "526172211a07" to "RAR 压缩包",
        "504b0304" to "ZIP 压缩包",
        "504b0506" to "ZIP 压缩包（空）",
        "504b0708" to "ZIP 压缩包（分卷）",
        "25504446" to "PDF 文档",
        "89504e47" to "PNG 图片",
        "ffd8ff" to "JPEG 图片"
    )

    /** UTF-16 的 BOM —— 这类文件是文本，只是双字节存放，交给预处理去重编码 */
    private fun isUtf16Bom(b: ByteArray): Boolean =
        b.size >= 2 && ((b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte()) ||
            (b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte()))

    /** 「含 NUL 字节」这条判据的描述文案（**不等于** sh 读不了，见类注释） */
    const val NUL_DESC = "含 NUL 字节的二进制内容"

    /**
     * 命中魔数吗 —— **铁证**：ELF / 压缩包 / 图片这些里面没有一行是给 shell 看的。
     *
     * @return 命中时返回可读描述（用于提示文案）；否则 null
     */
    fun magicKind(head: ByteArray): String? {
        if (head.isEmpty() || isUtf16Bom(head)) return null
        val hex = hex(head, 6)
        for ((magic, desc) in MAGICS) {
            if (hex.startsWith(magic)) return desc
        }
        return null
    }

    /** 前 [SNIFF_BYTES] 字节里有没有 NUL —— 纯粹的字节事实，**不含任何结论** */
    fun hasNul(head: ByteArray): Boolean = head.any { it == 0.toByte() }

    /**
     * 字节层面「不是纯文本」时的描述，否则 null。
     *
     * **这条判据只回答一个问题：能不能改它的字节。** 编码修复（删 `0x0D`、去 BOM）
     * 对含二进制载荷的文件就是破坏，所以它一律照「含 NUL」拦下，不看 shebang、
     * 也不管这个文件其实是不是脚本（见类注释里的两条判据）。
     *
     * **别拿它决定「用什么跑」**：含 NUL 的脚本交给 sh 完全没问题
     * （`probe-nul-script.sh` 实测），判「能不能交给 sh」要用
     * [magicKind] + 文件自己的「读者声明」。
     */
    fun binaryKind(head: ByteArray): String? {
        if (head.isEmpty() || isUtf16Bom(head)) return null
        magicKind(head)?.let { return it }
        return if (hasNul(head)) NUL_DESC else null
    }

    /**
     * 命中魔数时可以**确定**是 ELF 之类的可执行映像吗？
     *
     * 用来区分提示语：ELF 是「本身就能执行，直接跑它」；
     * gzip/zip 之类是「这压根不是可执行文件，直接跑也会报 exec 格式错」。
     */
    fun looksExecutableImage(head: ByteArray): Boolean =
        hex(head, 4).startsWith("7f454c46")

    private fun hex(b: ByteArray, max: Int): String {
        val sb = StringBuilder(max * 2)
        var i = 0
        while (i < b.size && i < max) {
            val v = b[i].toInt() and 0xFF
            sb.append(HEX[v ushr 4]).append(HEX[v and 0xF])
            i += 1
        }
        return sb.toString()
    }

    private const val HEX = "0123456789abcdef"
}
