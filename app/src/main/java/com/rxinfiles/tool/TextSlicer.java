package com.rxinfiles.tool;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 大文本的「切片」访问层：把文件切成若干片，每次只把**一片**交给界面控件。
 *
 * <h2>为什么要切片</h2>
 *
 * 界面控件（TextView / EditText）的开销**只与"一次塞进去多少字符"有关**，与文件多大无关：
 * TextView 会构建 {@code StaticLayout}（{@code generate()} 是逐行把整篇排完的大循环，
 * 不懒加载），EditText 更狠 —— 它的 {@code DynamicLayout} 在**构造时**就要对整篇
 * 做一次 reflow。所以"这个文件能不能编辑"的正确判据是**字符数**，不是字节数、
 * 更不是"文件有多大"。
 *
 * 原来的实现把两者绑死了（文件超过 4MB 就直接禁编辑），结果是：
 * 7.8MB 的文本**一个字节都改不了** —— 明明只需要让控件一次少拿一点字符就行。
 *
 * 这里把判据解耦：文件按字符数切成片，界面永远只拿一片，
 * 于是"编辑能力"与"文件体积"不再有任何关系。
 *
 * <h2>为什么按字符数切，不按字节数</h2>
 *
 * 控件的开销看的是字符数。UTF-8 下一个汉字占 3 字节却只有 1 个字符，
 * 按字节切会让中文片只有纯 ASCII 片的 1/3 —— 同一台机器上，中文文件莫名其妙
 * 少看 2/3 的内容。按字符数切才能让"每一片的代价"真正恒定。
 *
 * <h2>片边界为什么落在行首</h2>
 *
 * 行号、跳转、查找、编辑全都以"行"为单位思考。让片边界 = 行边界，
 * "这个片覆盖第几行到第几行"才有意义，用户也不会在片首看到半行。
 * 唯一的例外是**超长行**（几 MB 没有换行，比如压缩过的 JSON）：
 * 那种情况下等不到换行就必须硬切，否则一片就是整个文件，等于没切。
 *
 * <h2>保存为什么必须"逐片拼接"</h2>
 *
 * 保存时**没有"全文"，只有若干片**。所以只能：
 *
 * <ul>
 *   <li><b>没碰过的片</b> —— 从源文件按**原始字节范围**原样复制，一个字节都不重新编码。
 *       这一条很重要：编码探测万一猜错了，只要用户没编辑那一片，那一片出去时
 *       仍与进来时逐字节相同，绝不可能因为"探测错了"把文件写坏；</li>
 *   <li><b>改过的片</b> —— 用编辑后的文本按同一编码重新编码写入。</li>
 * </ul>
 *
 * 按片顺序拼接后的结果，语义上就是"原文件 + 用户的改动"，
 * 并且**片的大小变了也不影响**（拼接是顺序的，片边界不需要保留）。
 *
 * <h2>BOM</h2>
 *
 * 首片的字节范围**包含** BOM（{@code startByte} 为 0），读取时由调用方跳过。
 * 这样"没编辑首片"就是「原样复制 [0, endByte)」，BOM 自然保留；
 * "编辑了首片"则先单独复制 [0, bomLength) 那几字节（**BOM 的原始字节，
 * 本类不需要知道它是 EF BB BF 还是 FF FE**），再写新内容。
 *
 * <h2>本类必须保持纯 Java</h2>
 *
 * 一行 Android API 都不许有 —— 这样它可以用 {@code javac}/{@code java} 直接跑断言
 * （见 {@code tools/textslice-check/}）。切片的正确性全是"字节与字符的换算"，
 * 是那种最容易写错、又最难在真机上复现的一类逻辑，必须能离线验证。
 */
public final class TextSlicer {

    private TextSlicer() {
    }

    // ---------------------------------------------------------------- 编码形态

    // 包内可见：Scanner 与离线断言（tools/textslice-check）都要用这几个常量比对
    static final int K_UTF8 = 0;
    static final int K_GBK = 1;
    static final int K_UTF16LE = 2;
    static final int K_UTF16BE = 3;
    static final int K_SINGLE = 4;

    /**
     * 把编码名归成"扫描时要知道的那几类"。
     *
     * 扫描时真正要回答的只有两个问题：
     *  ① 换行符在字节上长什么样；② 一个字符占几个字节。
     * 所以只需要区分这五类，不需要认识所有编码。
     */
    static int kindOf(String charsetName) {
        if (charsetName == null) return K_UTF8;
        String name = charsetName.trim().toUpperCase();
        if (name.startsWith("UTF-16LE")) return K_UTF16LE;
        if (name.startsWith("UTF-16BE")) return K_UTF16BE;
        // Java 里裸的 "UTF-16" 是"带 BOM 的大端"，与 BE 的换行字节形态一致
        if (name.startsWith("UTF-16")) return K_UTF16BE;
        if (name.startsWith("UTF-8")) return K_UTF8;
        // GB18030 的 4 字节部分本状态机会算成两个双字节，但项目只提供 GBK，
        // 且探测也只会给出 GBK，这里不引入更大的复杂度
        if (name.startsWith("GBK") || name.startsWith("GB2312") || name.startsWith("GB18030")) {
            return K_GBK;
        }
        return K_SINGLE;
    }

    /** UTF-8 里这个字节打头的一个字符占几字节（非法字节按 1 处理，和 REPLACE 解码的语义一致） */
    static int utf8Step(int b) {
        if (b < 0x80) return 1;
        if (b >= 0xC0 && b <= 0xDF) return 2;
        if (b >= 0xE0 && b <= 0xEF) return 3;
        if (b >= 0xF0 && b <= 0xF7) return 4;
        return 1;
    }

    /** GBK 首字节范围 */
    static boolean isGbkLead(int b) {
        return b >= 0x81 && b <= 0xFE;
    }

    /**
     * GBK 尾字节范围。
     *
     * 注意 0x0A（换行）**不在**这个区间里，所以"按字节找 \n"在 GBK 上永远安全 ——
     * 换行符不可能出现在一个双字节字符的后半截。
     */
    static boolean isGbkTrail(int b) {
        return b >= 0x40 && b <= 0xFE && b != 0x7F;
    }

    /**
     * 文件开头的 BOM 长度。
     *
     * 与 {@code TextCodec.bomLength} 是同一套判据（那边是 Kotlin 的权威版本）。
     * 两处必须逐条一致 —— {@code verify-textslice.py} 会跨文件比对，
     * 改了一边而忘了另一边会当场失败。
     */
    static int bomLength(byte[] head) {
        if (head.length >= 3 && (head[0] & 0xFF) == 0xEF && (head[1] & 0xFF) == 0xBB
                && (head[2] & 0xFF) == 0xBF) {
            return 3;
        }
        if (head.length >= 2 && (head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0xFE) {
            return 2;
        }
        if (head.length >= 2 && (head[0] & 0xFF) == 0xFE && (head[1] & 0xFF) == 0xFF) {
            return 2;
        }
        return 0;
    }

    // ---------------------------------------------------------------- 数据结构

    /** 一片：文件中的一个字节区间，且（正常情况下）起止都落在行首 */
    public static final class Slice {
        /** 起始字节偏移；首片恒为 0（**含 BOM**） */
        public final long startByte;
        /** 结束字节偏移（不含） */
        public final long endByte;
        /** 这一片的第一个字符属于第几行（从 1 数） */
        public final int startLine;
        /** 这一片里有多少个换行符 */
        public final int newlines;
        /** 这一片有多少个字符（String.length 口径） */
        public final int chars;

        Slice(long startByte, long endByte, int startLine, int newlines, int chars) {
            this.startByte = startByte;
            this.endByte = endByte;
            this.startLine = startLine;
            this.newlines = newlines;
            this.chars = chars;
        }

        public long byteLength() {
            return endByte - startByte;
        }

        @Override
        public String toString() {
            return "Slice[" + startByte + "," + endByte + ") line=" + startLine
                    + " nl=" + newlines + " chars=" + chars;
        }
    }

    /** 整个文件切完之后的布局 */
    public static final class Layout {
        public final List<Slice> slices;
        /** 全文行数（与 TextCodec.countLines 同口径：换行数 + 1，空文件为 0） */
        public final int totalLines;
        public final long totalBytes;
        /** 文件头的 BOM 长度（0 / 2 / 3） */
        public final int bomLength;
        public final String charset;

        Layout(List<Slice> slices, int totalLines, long totalBytes, int bomLength, String charset) {
            this.slices = slices;
            this.totalLines = totalLines;
            this.totalBytes = totalBytes;
            this.bomLength = bomLength;
            this.charset = charset;
        }

        public int size() {
            return slices.size();
        }

        /** 这一片覆盖到第几行（含） */
        public int endLineOf(int index) {
            if (index < 0 || index >= slices.size()) return 0;
            if (index == slices.size() - 1) return totalLines;
            return slices.get(index + 1).startLine - 1;
        }
    }

    // ---------------------------------------------------------------- 扫描

    /**
     * 扫描文件，切出所有片。
     *
     * 只读字节、**不解码文本** —— 所以再大的文件这一步也只是"过一遍字节"，
     * 内存占用是个固定大小的缓冲区。行号与字符数在这里一次算清，
     * 之后翻片时就不必再回头扫。
     *
     * @param maxChars 单片字符数上限。到达上限后会在**最近的换行之后**切开；
     *                 若这一段根本没有换行（超长行），则在当前位置硬切。
     */
    public static Layout scan(File file, String charsetName, int maxChars) throws IOException {
        if (maxChars < 1) maxChars = 1;
        int kind = kindOf(charsetName);
        long total = file.length();

        int bomLen;
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            byte[] head = new byte[(int) Math.min(4L, total)];
            raf.readFully(head);
            bomLen = bomLength(head);
        }

        List<Slice> slices = new ArrayList<>();

        // 当前片的起点
        long sliceStartByte = 0;
        int sliceStartChars = 0;
        int sliceStartLine = 1;
        int sliceNl = 0;

        // 最近一个换行符"之后"的位置 —— 到达字符上限时就切在这里
        long nlAfterByte = -1;
        int nlAfterChars = -1;
        int sliceNlAtNl = -1;

        byte[] buf = new byte[1 << 20];
        int filled = 0;
        int i = 0;
        long base = 0;
        long bytePos = 0;
        int chars = 0;
        int nl = 0;

        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            // 首片从 BOM 之后开始扫；片本身仍从字节 0 起算（BOM 属于首片）
            if (bomLen > 0) {
                raf.seek(bomLen);
                bytePos = bomLen;
                base = bomLen;
                i = 0;
            }

            while (true) {
                // 保证缓冲里至少够判断一个字符（最长 4 字节），不够就再读一段
                if (filled - i < 8) {
                    int keep = filled - i;
                    if (keep > 0) {
                        System.arraycopy(buf, i, buf, 0, keep);
                    }
                    base += i;
                    filled = keep;
                    i = 0;
                    int got = raf.read(buf, filled, buf.length - filled);
                    if (got > 0) {
                        filled += got;
                    } else if (filled == 0) {
                        break;
                    }
                }
                if (i >= filled) break;

                int b0 = buf[i] & 0xFF;
                int step;
                int charWidth;   // 这段字节解码后占几个 char（String.length 口径）
                boolean isNl;
                switch (kind) {
                    case K_UTF16LE:
                        if (i + 2 > filled) {
                            step = filled - i;
                            charWidth = 1;
                            isNl = false;
                        } else {
                            step = 2;
                            charWidth = 1;
                            isNl = b0 == 0x0A && (buf[i + 1] & 0xFF) == 0x00;
                        }
                        break;
                    case K_UTF16BE:
                        if (i + 2 > filled) {
                            step = filled - i;
                            charWidth = 1;
                            isNl = false;
                        } else {
                            step = 2;
                            charWidth = 1;
                            isNl = b0 == 0x00 && (buf[i + 1] & 0xFF) == 0x0A;
                        }
                        break;
                    case K_GBK:
                        if (isGbkLead(b0) && i + 1 < filled && isGbkTrail(buf[i + 1] & 0xFF)) {
                            step = 2;
                            charWidth = 1;
                            isNl = false;
                        } else {
                            step = 1;
                            charWidth = 1;
                            isNl = b0 == 0x0A;
                        }
                        break;
                    case K_SINGLE:
                        step = 1;
                        charWidth = 1;
                        isNl = b0 == 0x0A;
                        break;
                    default: // K_UTF8
                        step = utf8Step(b0);
                        // 4 字节序列是 BMP 之外的码位，解码后在 Java 的 String 里是**代理对**，
                        // 占 2 个 char —— 按 1 算会让片字符数比 readSlice().length() 少一半，
                        // 而片大小控制正是靠这个数
                        charWidth = step == 4 ? 2 : 1;
                        isNl = b0 == 0x0A;
                        break;
                }
                if (step < 1) step = 1;
                if (i + step > filled) {
                    step = filled - i;
                    charWidth = 1;
                    isNl = false;
                }

                if (isNl) {
                    nl++;
                    sliceNl++;
                    nlAfterByte = base + i + step;
                    nlAfterChars = chars + charWidth;
                    sliceNlAtNl = sliceNl;
                }
                chars += charWidth;
                bytePos = base + i + step;
                i += step;

                // 到上限了：优先切在最近的换行之后；这一整段没有换行就硬切
                if (chars - sliceStartChars >= maxChars) {
                    long cutByte;
                    int cutChars;
                    int cutNl;
                    if (nlAfterByte > sliceStartByte) {
                        cutByte = nlAfterByte;
                        cutChars = nlAfterChars;
                        cutNl = sliceNlAtNl;
                    } else {
                        cutByte = bytePos;
                        cutChars = chars;
                        cutNl = sliceNl;
                    }
                    if (cutByte > sliceStartByte) {
                        slices.add(new Slice(sliceStartByte, cutByte, sliceStartLine,
                                cutNl, cutChars - sliceStartChars));
                        sliceStartLine += cutNl;
                        sliceStartByte = cutByte;
                        sliceStartChars = cutChars;
                        sliceNl -= cutNl;
                        nlAfterByte = -1;
                        nlAfterChars = -1;
                        sliceNlAtNl = -1;
                    }
                }
            }
        }

        // 收尾：最后一片
        long fileEnd = total;
        if (fileEnd > sliceStartByte || slices.isEmpty()) {
            slices.add(new Slice(sliceStartByte, fileEnd, sliceStartLine,
                    sliceNl, chars - sliceStartChars));
        }

        int totalLines = (total == 0) ? 0 : nl + 1;

        return new Layout(slices, totalLines, total, bomLen, charsetName);
    }

    // ---------------------------------------------------------------- 读取

    /**
     * 读出一片并解码。
     *
     * @param skipBytes 片首要跳过的字节数（首片传 BOM 长度，其余传 0）
     */
    public static String readSlice(File file, String charsetName, Slice slice, int skipBytes)
            throws IOException {
        long start = slice.startByte + skipBytes;
        long len = slice.endByte - start;
        if (len < 0) len = 0;
        if (len > Integer.MAX_VALUE - 8) {
            throw new IOException("slice too large: " + len);
        }
        byte[] bytes = new byte[(int) len];
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            raf.seek(start);
            raf.readFully(bytes);
        }
        return decode(bytes, charsetName);
    }

    /**
     * 解码。
     *
     * 与 {@code TextCodec.read} 用的是同一套策略：REPLACE + REPLACE，
     * 遇到坏字节变成 {@code \uFFFD} 而不是抛异常 —— 宁可让用户看到几个替换字符，
     * 也不能因为一个坏字节就整篇打不开。两边的行为必须一致，
     * 否则"翻到第二片才发现乱码"这种问题根本没法查。
     */
    public static String decode(byte[] bytes, String charsetName) {
        Charset charset = charsetOf(charsetName);
        try {
            CharsetDecoder decoder = charset.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPLACE)
                    .onUnmappableCharacter(CodingErrorAction.REPLACE);
            CharBuffer out = decoder.decode(ByteBuffer.wrap(bytes));
            return out.toString();
        } catch (Throwable t) {
            return new String(bytes, Charsets.ISO_8859_1);
        }
    }

    /** 与 TextCodec.charsetOf 同语义：不认识的名字退回 UTF-8 */
    public static Charset charsetOf(String name) {
        try {
            return Charset.forName(name);
        } catch (Throwable t) {
            return Charsets.UTF_8;
        }
    }

    /** 避免直接依赖 Kotlin 的 Charsets 对象，保持本类纯 Java 可编译 */
    private static final class Charsets {
        static final Charset UTF_8 = Charset.forName("UTF-8");
        static final Charset ISO_8859_1 = Charset.forName("ISO-8859-1");
    }

    // ---------------------------------------------------------------- 合并写回

    /**
     * 按片拼接出新文件。
     *
     * @param source 原始文件（没改过的片从这里按字节复制）
     * @param out    输出文件（调用方负责之后用它覆盖目标）
     * @param edits  改过的片：片号 → 编辑后的文本
     */
    public static void merge(File source, File out, Layout layout, Map<Integer, String> edits)
            throws IOException {
        List<Slice> slices = layout.slices;
        Charset charset = charsetOf(layout.charset);
        byte[] buf = new byte[1 << 16];

        try (RandomAccessFile raf = new RandomAccessFile(source, "r");
             OutputStream os = new FileOutputStream(out)) {
            for (int index = 0; index < slices.size(); index++) {
                Slice slice = slices.get(index);
                String edited = edits == null ? null : edits.get(index);
                boolean first = index == 0;

                long start = slice.startByte;
                if (first && layout.bomLength > 0) {
                    // BOM 的**原始字节**照搬：本类不需要知道它是哪种 BOM
                    copyRange(raf, 0, layout.bomLength, os, buf);
                    start += layout.bomLength;
                }

                if (edited != null) {
                    os.write(edited.getBytes(charset));
                } else {
                    copyRange(raf, start, slice.endByte, os, buf);
                }
            }
            os.flush();
        }
    }

    private static void copyRange(RandomAccessFile raf, long from, long to, OutputStream os,
                                  byte[] buf) throws IOException {
        if (to <= from) return;
        raf.seek(from);
        long remaining = to - from;
        while (remaining > 0) {
            int want = (int) Math.min(buf.length, remaining);
            int got = raf.read(buf, 0, want);
            if (got <= 0) break;
            os.write(buf, 0, got);
            remaining -= got;
        }
    }

    // ---------------------------------------------------------------- 校验

    /** 文本里有几个换行符（与 countLines 的"行数 - 1"同口径） */
    public static int countNewlines(CharSequence text) {
        int n = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') n++;
        }
        return n;
    }

    /**
     * 最长一行的字符数（不含行尾的 {@code \n}）。
     *
     * 用途是**显示侧的防线**：不折行渲染时控件的宽度由最长行决定，一行十万字符
     * 会撑出几十万像素宽的 Layout，滚动直接废掉。所以渲染前得先知道"这一片有没有
     * 超长行"，有就强制折行。
     *
     * 放在这里而不是写进 Kotlin，是因为它必须与 [scan] 用**同一套"什么算换行"的口径**
     * （只有 {@code \n} 算换行，{@code \r} 算行内字符）—— 两份口径就是"行号对不上"
     * 那类问题的来源。
     */
    public static int longestLine(CharSequence text) {
        if (text == null || text.length() == 0) return 0;
        int n = text.length();
        int best = 0;
        int start = 0;
        for (int i = 0; i < n; i++) {
            if (text.charAt(i) == '\n') {
                int len = i - start;
                if (len > best) best = len;
                start = i + 1;
            }
        }
        int tail = n - start;
        if (tail > best) best = tail;
        return best;
    }

    /**
     * 合并前先算一遍"新文件应该有多少行"。
     *
     * 合并完成后会拿这个数去核对实际结果 —— 这是防止"写坏原文件"的最后一道闸门：
     * 只要行数对不上，就说明片切错了或拼接漏了，此时**不覆盖目标文件**。
     */
    public static int expectedLines(Layout layout, Map<Integer, String> edits) {
        if (edits == null || edits.isEmpty()) return layout.totalLines;
        int newlines = 0;
        boolean hasContent = layout.bomLength > 0;
        for (int index = 0; index < layout.slices.size(); index++) {
            Slice slice = layout.slices.get(index);
            String edited = edits.get(index);
            if (edited != null) {
                newlines += countNewlines(edited);
                if (!edited.isEmpty()) hasContent = true;
            } else {
                newlines += slice.newlines;
                long contentStart = slice.startByte + (index == 0 ? layout.bomLength : 0);
                if (slice.endByte > contentStart) hasContent = true;
            }
        }
        return hasContent ? newlines + 1 : 0;
    }

    /** 用同一套扫描规则数一个文件的行数（供合并后的核对使用） */
    public static int countLines(File file, String charsetName) throws IOException {
        Layout layout = scan(file, charsetName, Integer.MAX_VALUE);
        return layout.totalLines;
    }
}
