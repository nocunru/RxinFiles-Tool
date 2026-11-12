package com.rxinfiles.tool

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * 文本编码探测与编解码工具。
 *
 * Android 上的文本文件基本是三种来源，编码各不相同：
 *  - 系统/应用生成 → UTF-8（无 BOM）
 *  - Windows 记事本存过 → GBK（简体）或 UTF-8 带 BOM
 *  - 少量遗留脚本 → UTF-16 带 BOM
 *
 * 探测顺序刻意分成「先看 BOM，再做合法性判定」两步：
 * BOM 是明确声明，优先采信；没有 BOM 就用「是不是合法 UTF-8」来区分
 * UTF-8 与 GBK —— 合法 UTF-8 的多字节序列与 GBK 的双字节序列分布几乎不重叠，
 * 这个判据在实践中非常稳（唯一模糊地带是纯 ASCII，而两者对纯 ASCII 结果一致）。
 *
 * ## 本类**不**负责"把文件读进来"
 *
 * 早先这里还有个 `read()`：文件超过 `MAX_FULL_BYTES`（4MB）就只读前 512KB，
 * 并打上 `truncated`。那套策略已经废掉了 —— 它把"文件多大"和"一次给控件多少字符"
 * 绑成了同一件事，结果是 7.8MB 的文本连编辑模式都进不去。
 *
 * 现在读文件是 [TextSlicer] 的事：按**字符数**切片、按需读片、逐片拼接写回，
 * 所以"能编辑"与"文件体积"彻底脱钩。配套的 `write()` / `writeWithRoot()`
 * 也一并删掉了 —— 覆盖写现在由 [TextFileRepository.write] 统一负责，
 * 它多了一道"拼接完之后核对行数"的闸门，查看器保存使用。
 *
 * 留下的这些是真被用到的：[detect] / [charsetOf] / [SUPPORTED]（查看器与
 * [ScriptPipe] 都在用），以及 [bomLength] / [hasBom] / [countLines] ——
 * 后面三个是"字节判据"与"行数口径"的**权威版本**，[TextSlicer] 里那份
 * 是照着它们写的（`tools/textslice-check/verify-textslice.py` 会跨文件比对，
 * 改了一边忘另一边会当场失败）。
 */
object TextCodec {

    /** 可选编码（保存时让用户能改） */
    val SUPPORTED = listOf("UTF-8", "GBK", "UTF-16LE", "UTF-16BE", "ISO-8859-1")

    /** 只有不带 BOM 的 UTF-8 才可能被误判，所以 BOM 判定要放在最前面 */
    fun detect(bytes: ByteArray): String {
        if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
        ) {
            return "UTF-8"
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            // FF FE 00 00 是 UTF-32LE，Android 上极罕见，这里不区分
            return "UTF-16LE"
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return "UTF-16BE"
        }
        detectUtf16WithoutBom(bytes)?.let { return it }
        if (isValidUtf8(bytes)) {
            return "UTF-8"
        }
        return "GBK"
    }

    private fun detectUtf16WithoutBom(bytes: ByteArray): String? {
        val pairs = bytes.size / 2
        if (pairs < 2) return null
        var evenNul = 0
        var oddNul = 0
        for (i in 0 until pairs) {
            if (bytes[i * 2] == 0.toByte()) evenNul++
            if (bytes[i * 2 + 1] == 0.toByte()) oddNul++
        }
        return when {
            oddNul * 4 >= pairs * 3 && evenNul == 0 -> "UTF-16LE"
            evenNul * 4 >= pairs * 3 && oddNul == 0 -> "UTF-16BE"
            else -> null
        }
    }

    fun hasBom(bytes: ByteArray): Boolean {
        if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
        ) {
            return true
        }
        if (bytes.size >= 2) {
            val b0 = bytes[0]
            val b1 = bytes[1]
            if (b0 == 0xFF.toByte() && b1 == 0xFE.toByte()) return true
            if (b0 == 0xFE.toByte() && b1 == 0xFF.toByte()) return true
        }
        return false
    }

    fun bomLength(bytes: ByteArray): Int = when {
        bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() &&
            bytes[2] == 0xBF.toByte() -> 3
        bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() -> 2
        bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> 2
        else -> 0
    }

    /**
     * 严格校验 UTF-8。
     *
     * 数据末尾被截断时视为「当前为止合法」—— 这是有意的：只读文件头一段
     * 做编码判定时，尾巴正好落在一个多字节字符中间是**正常现象**，
     * 不该因此把整个文件判成 GBK（而 GBK 文本几乎总在更早的位置就露馅）。
     */
    private fun isValidUtf8(bytes: ByteArray): Boolean {
        var i = 0
        val n = bytes.size
        while (i < n) {
            val b = bytes[i].toInt() and 0xFF
            when {
                b < 0x80 -> i++
                b in 0xC2..0xDF -> {
                    if (i + 1 >= n) return true
                    if (!isContinuation(bytes[i + 1])) return false
                    i += 2
                }
                b in 0xE0..0xEF -> {
                    if (i + 2 >= n) return true
                    if (!isContinuation(bytes[i + 1]) || !isContinuation(bytes[i + 2])) return false
                    i += 3
                }
                b in 0xF0..0xF4 -> {
                    if (i + 3 >= n) return true
                    if (!isContinuation(bytes[i + 1]) || !isContinuation(bytes[i + 2]) ||
                        !isContinuation(bytes[i + 3])
                    ) {
                        return false
                    }
                    i += 4
                }
                else -> return false
            }
        }
        return true
    }

    private fun isContinuation(b: Byte): Boolean {
        val v = b.toInt() and 0xFF
        return v in 0x80..0xBF
    }

    fun charsetOf(name: String): Charset = try {
        Charset.forName(name)
    } catch (t: Throwable) {
        Charsets.UTF_8
    }

    /**
     * 统计行数。
     *
     * 口径：换行符个数 + 1，空串为 0。**这是全工程"第几行"的唯一定义**
     * （[TextSlicer] 按字节扫出来的行数、还有保存前那次行数核对，
     * 都按这个口径算）。
     */
    fun countLines(text: String): Int {
        if (text.isEmpty()) return 0
        var lines = 1
        for (c in text) {
            if (c == '\n') lines++
        }
        return lines
    }

    /** 仅在需要严格判断时使用：能完整解码才返回 true（不做 REPLACE） */
    fun isStrictlyDecodable(bytes: ByteArray, charsetName: String): Boolean = try {
        charsetOf(charsetName).newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
        true
    } catch (t: CharacterCodingException) {
        false
    }
}
