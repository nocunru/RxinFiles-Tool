package com.rxinfiles.tool;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * {@link TextSlicer}（大文本切片 / 拼接）的断言。
 *
 * 为什么这个模块必须有离线断言：它的全部工作是「字节与字符的换算」+「按片拼接写回」，
 * 而这两件事**错一点都不会当场报错** —— 错一位的结果是「文件能打开、内容少了一行」，
 * 或者「保存之后文件变成乱码」。等用户在真机上发现时，原文件已经被覆盖了。
 * 所以这里的判据全部是**逐字节**级别的，而且不依赖 Android：直接 javac/java 跑。
 *
 * 覆盖八节：
 *   1. 字节形态判定（换行在哪、一个字符几字节、BOM 多长）
 *   2. 片边界必须落在行首（各编码）
 *   3. 切片无损：所有片拼起来 == 整篇解码（最强的一条）
 *   4. BOM：识别、跳过、保存时保留
 *   5. 拼接写回：没改的片逐字节原样，改了的片恰好替换
 *   6. 行数核对（写回前的最后一道闸门）
 *   7. 超长行硬切（几 MB 没有换行的文件）
 *   8. 边界输入：空文件、单行、无结尾换行、单字节编码
 */
public final class TextSliceCheck {

    private static int total = 0;
    private static int failures = 0;

    private TextSliceCheck() {
    }

    public static void main(String[] args) throws Exception {
        Path tmp = Files.createTempDirectory("rxin-slice-");
        try {
            section("1. 字节形态：换行在哪、一个字符几字节、BOM 多长");
            formChecks();

            section("2. 片边界落在行首（各编码）");
            boundaryChecks(tmp);

            section("3. 切片无损：所有片拼起来 == 整篇解码");
            losslessChecks(tmp);

            section("4. BOM：识别 / 跳过 / 保留");
            bomChecks(tmp);

            section("5. 拼接写回：没改的片逐字节原样，改了的片恰好替换");
            mergeChecks(tmp);

            section("6. 行数核对（写回前最后一道闸门）");
            lineCountChecks(tmp);

            section("7. 超长行硬切");
            hardCutChecks(tmp);

            section("8. 边界输入");
            edgeChecks(tmp);

            section("9. 最长行（显示侧的折行判据）");
            longestLineChecks();

            section("10. 片大小上限：每一片都不超过 maxChars");
            sliceSizeCapChecks(tmp);
        } finally {
            deleteTree(tmp.toFile());
        }

        System.out.println();
        System.out.printf("结果: %d 项通过, %d 项失败%n", total - failures, failures);
        if (failures > 0) {
            System.exit(1);
        }
    }

    // ================================================================ 1

    private static void formChecks() {
        checkEq("UTF-8 归类", TextSlicer.K_UTF8, TextSlicer.kindOf("UTF-8"));
        checkEq("utf-8 小写也算", TextSlicer.K_UTF8, TextSlicer.kindOf("utf-8"));
        checkEq("GBK 归类", TextSlicer.K_GBK, TextSlicer.kindOf("GBK"));
        checkEq("GB2312 归到 GBK", TextSlicer.K_GBK, TextSlicer.kindOf("GB2312"));
        checkEq("UTF-16LE 归类", TextSlicer.K_UTF16LE, TextSlicer.kindOf("UTF-16LE"));
        checkEq("UTF-16BE 归类", TextSlicer.K_UTF16BE, TextSlicer.kindOf("UTF-16BE"));
        checkEq("裸 UTF-16 按大端处理（与 java 的默认一致）",
                TextSlicer.K_UTF16BE, TextSlicer.kindOf("UTF-16"));
        checkEq("ISO-8859-1 归到单字节", TextSlicer.K_SINGLE, TextSlicer.kindOf("ISO-8859-1"));
        checkEq("不认识的名字按 UTF-8 兜底", TextSlicer.K_UTF8, TextSlicer.kindOf(null));

        checkEq("ASCII 1 字节", 1, TextSlicer.utf8Step(0x41));
        checkEq("换行符 1 字节", 1, TextSlicer.utf8Step(0x0A));
        checkEq("两字节序列（拉丁扩展）", 2, TextSlicer.utf8Step(0xC3));
        checkEq("三字节序列（汉字）", 3, TextSlicer.utf8Step(0xE4));
        checkEq("四字节序列（emoji）", 4, TextSlicer.utf8Step(0xF0));
        checkEq("孤立续字节按 1 字节（解码后是 U+FFFD）", 1, TextSlicer.utf8Step(0x80));
        checkEq("0xBF 也是续字节", 1, TextSlicer.utf8Step(0xBF));

        // GBK：换行符既不是首字节也不是尾字节 —— 这是「按字节找 \n 在 GBK 上永远安全」的依据
        check("换行符不是 GBK 首字节", !TextSlicer.isGbkLead(0x0A));
        check("换行符不是 GBK 尾字节", !TextSlicer.isGbkTrail(0x0A));
        check("回车也不是 GBK 尾字节", !TextSlicer.isGbkTrail(0x0D));
        check("0x7F 被排除在尾字节外（GBK 的规矩）", !TextSlicer.isGbkTrail(0x7F));
        check("0x81 是首字节", TextSlicer.isGbkLead(0x81));
        check("0xFE 是首字节", TextSlicer.isGbkLead(0xFE));
        check("0xFF 不是首字节", !TextSlicer.isGbkLead(0xFF));
        check("0x40 是尾字节", TextSlicer.isGbkTrail(0x40));

        checkEq("UTF-8 BOM 长 3", 3, TextSlicer.bomLength(
                new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, 'a'}));
        checkEq("UTF-16LE BOM 长 2", 2, TextSlicer.bomLength(
                new byte[]{(byte) 0xFF, (byte) 0xFE, 'a', 0}));
        checkEq("UTF-16BE BOM 长 2", 2, TextSlicer.bomLength(
                new byte[]{(byte) 0xFE, (byte) 0xFF, 0, 'a'}));
        checkEq("没有 BOM", 0, TextSlicer.bomLength(new byte[]{'a', 'b', 'c'}));
        checkEq("空头也不炸", 0, TextSlicer.bomLength(new byte[0]));
    }

    // ================================================================ 2

    private static void boundaryChecks(Path tmp) throws Exception {
        String text = sampleText();
        String[] charsets = {"UTF-8", "GBK", "UTF-16LE", "UTF-16BE", "ISO-8859-1"};
        for (String cs : charsets) {
            if (!Charset.isSupported(cs)) {
                check(cs + " 本机不支持，跳过", true);
                continue;
            }
            File f = write(tmp, "b-" + cs + ".txt", text, cs, false);
            byte[] data = Files.readAllBytes(f.toPath());
            int kind = TextSlicer.kindOf(cs);
            TextSlicer.Layout layout = TextSlicer.scan(f, cs, 40);

            check(cs + " 切出了多片（判据是字符数，不是字节数）", layout.size() > 1);
            checkEq(cs + " 首片从第 1 行开始", 1, layout.slices.get(0).startLine);

            boolean allOnLineStart = true;
            boolean lineNumbersCascade = true;
            for (int i = 0; i < layout.size(); i++) {
                TextSlicer.Slice s = layout.slices.get(i);
                if (s.startByte > 0 && !isLineStart(data, s.startByte, kind)) {
                    allOnLineStart = false;
                }
                if (i + 1 < layout.size()) {
                    TextSlicer.Slice next = layout.slices.get(i + 1);
                    if (next.startLine != s.startLine + s.newlines) lineNumbersCascade = false;
                }
            }
            check(cs + " 每片起点都落在行首", allOnLineStart);
            check(cs + " 行号逐片累加正确", lineNumbersCascade);

            // 片必须首尾相接、不留缝不重叠 —— 漏一个字节就是静默丢内容
            boolean contiguous = layout.slices.get(0).startByte == 0;
            for (int i = 1; i < layout.size(); i++) {
                if (layout.slices.get(i).startByte != layout.slices.get(i - 1).endByte) {
                    contiguous = false;
                }
            }
            if (layout.slices.get(layout.size() - 1).endByte != data.length) contiguous = false;
            check(cs + " 片与片首尾相接、覆盖整个文件", contiguous);
        }
    }

    // ================================================================ 3

    private static void losslessChecks(Path tmp) throws Exception {
        String text = sampleText();
        String[] charsets = {"UTF-8", "GBK", "UTF-16LE", "UTF-16BE", "ISO-8859-1"};
        for (String cs : charsets) {
            if (!Charset.isSupported(cs)) continue;
            File f = write(tmp, "l-" + cs + ".txt", text, cs, false);
            TextSlicer.Layout layout = TextSlicer.scan(f, cs, 40);

            // 期望值必须取"这份文本在这个编码下往返之后的样子"：
            // ISO-8859-1 表示不了中文，getBytes 时中文已经变成 '?' 了，
            // 拿原文当期望值会得到一条永远红的假断言
            String roundTrip = new String(text.getBytes(TextSlicer.charsetOf(cs)),
                    TextSlicer.charsetOf(cs));

            StringBuilder joined = new StringBuilder();
            boolean charsMatch = true;
            for (TextSlicer.Slice s : layout.slices) {
                String part = TextSlicer.readSlice(f, cs, s, 0);
                joined.append(part);
                if (part.length() != s.chars) charsMatch = false;
            }
            // 最强的一条：所有片按顺序拼起来，必须与整篇解码一字不差
            checkEq(cs + " 片拼接 == 整篇解码", roundTrip, joined.toString());
            check(cs + " 每片的 chars 与解码后的长度一致", charsMatch);
            checkEq(cs + " 每片都不含替换字符（说明没切在字符中间）",
                    0, countReplacement(joined.toString()));
            checkEq(cs + " 总行数与逐字符数出来的行数一致",
                    TextSlicer.countNewlines(roundTrip) + 1, layout.totalLines);
        }
    }

    // ================================================================ 4

    private static void bomChecks(Path tmp) throws Exception {
        String text = "第一行\n第二行\n第三行\n第四行\n";

        File u8 = write(tmp, "bom-u8.txt", text, "UTF-8", true);
        TextSlicer.Layout l8 = TextSlicer.scan(u8, "UTF-8", 8);
        checkEq("UTF-8 BOM 被识别", 3, l8.bomLength);
        checkEq("首片从字节 0 起算（BOM 属于首片）", 0L, l8.slices.get(0).startByte);
        String p8 = TextSlicer.readSlice(u8, "UTF-8", l8.slices.get(0), l8.bomLength);
        check("跳掉 BOM 后首字符不是 U+FEFF", p8.length() > 0 && p8.charAt(0) != '\uFEFF');
        checkEq("跳过 BOM 后首字符是「第」", '第', p8.charAt(0));

        File le = write(tmp, "bom-le.txt", text, "UTF-16LE", true);
        TextSlicer.Layout lle = TextSlicer.scan(le, "UTF-16LE", 8);
        checkEq("UTF-16LE BOM 长 2", 2, lle.bomLength);
        String ple = TextSlicer.readSlice(le, "UTF-16LE", lle.slices.get(0), lle.bomLength);
        check("UTF-16LE 跳过 BOM 后首字符正常", ple.length() > 0 && ple.charAt(0) == '第');

        File be = write(tmp, "bom-be.txt", text, "UTF-16BE", true);
        TextSlicer.Layout lbe = TextSlicer.scan(be, "UTF-16BE", 8);
        checkEq("UTF-16BE BOM 长 2", 2, lbe.bomLength);
        String pbe = TextSlicer.readSlice(be, "UTF-16BE", lbe.slices.get(0), lbe.bomLength);
        check("UTF-16BE 跳过 BOM 后首字符正常", pbe.length() > 0 && pbe.charAt(0) == '第');

        File noBom = write(tmp, "nobom-le.txt", text, "UTF-16LE", false);
        checkEq("不带 BOM 的 UTF-16LE 认作没有 BOM",
                0, TextSlicer.scan(noBom, "UTF-16LE", 8).bomLength);

        // 保存：带 BOM 且**没改**任何片 → 输出与原文逐字节相同（BOM 自然保留）
        File out = new File(tmp.toFile(), "bom-out.bin");
        TextSlicer.Layout layout = TextSlicer.scan(u8, "UTF-8", 8);
        TextSlicer.merge(u8, out, layout, new HashMap<Integer, String>());
        check("未编辑时 BOM 原样保留", sameBytes(u8, out));

        // 保存：带 BOM 且**改了首片** → BOM 仍要补回去
        Map<Integer, String> edits = new HashMap<>();
        edits.put(0, "改过了\n");
        TextSlicer.merge(u8, out, layout, edits);
        byte[] merged = Files.readAllBytes(out.toPath());
        check("改了首片之后 BOM 仍在",
                merged.length >= 3 && (merged[0] & 0xFF) == 0xEF
                        && (merged[1] & 0xFF) == 0xBB && (merged[2] & 0xFF) == 0xBF);
        check("改过的首片内容接在 BOM 之后",
                new String(merged, 3, merged.length - 3, StandardCharsets.UTF_8).startsWith("改过了\n"));
    }

    // ================================================================ 5

    private static void mergeChecks(Path tmp) throws Exception {
        // 造一个能切很多片的 UTF-8 文件，片内容各不相同，便于定位
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 200; i++) {
            sb.append("第").append(i).append("行内容\n");
        }
        String text = sb.toString();
        File src = write(tmp, "merge-src.txt", text, "UTF-8", false);
        TextSlicer.Layout layout = TextSlicer.scan(src, "UTF-8", 60);
        check("夹具确实切成了多片", layout.size() >= 4);

        File out = new File(tmp.toFile(), "merge-out.bin");

        // ① 一片都不改 → 逐字节等于源文件
        TextSlicer.merge(src, out, layout, new HashMap<Integer, String>());
        check("没改任何片时，输出与源文件逐字节相同", sameBytes(src, out));

        // ② 只改中间某片 → 其余片逐字节原样
        int mid = layout.size() / 2;
        Map<Integer, String> edits = new HashMap<>();
        edits.put(mid, "★替换掉的一片★\n");
        TextSlicer.merge(src, out, layout, edits);
        byte[] expect = expectedMerge(src, layout, edits);
        check("只改中间一片：其余片逐字节原样、改的那片恰好被替换",
                java.util.Arrays.equals(expect, Files.readAllBytes(out.toPath())));

        // ③ 改首片 + 末片
        edits.clear();
        edits.put(0, "开头换了\n");
        edits.put(layout.size() - 1, "结尾也换了\n");
        TextSlicer.merge(src, out, layout, edits);
        expect = expectedMerge(src, layout, edits);
        check("同时改首片与末片也正确",
                java.util.Arrays.equals(expect, Files.readAllBytes(out.toPath())));

        // ④ 改多片（隔一个改一个）
        edits.clear();
        for (int i = 0; i < layout.size(); i += 2) {
            edits.put(i, "偶数片 #" + i + "\n");
        }
        TextSlicer.merge(src, out, layout, edits);
        expect = expectedMerge(src, layout, edits);
        check("隔片修改（改动量大于原文）也正确",
                java.util.Arrays.equals(expect, Files.readAllBytes(out.toPath())));

        // ⑤ 改成空文本（等价于删掉这一片）
        edits.clear();
        edits.put(0, "");
        TextSlicer.merge(src, out, layout, edits);
        expect = expectedMerge(src, layout, edits);
        check("把某片改成空文本也正确",
                java.util.Arrays.equals(expect, Files.readAllBytes(out.toPath())));

        // ⑥ 片边界在 GBK / UTF-16 上同样成立
        for (String cs : new String[]{"GBK", "UTF-16LE", "UTF-16BE"}) {
            if (!Charset.isSupported(cs)) continue;
            File s2 = write(tmp, "merge-" + cs + ".txt", text, cs, false);
            TextSlicer.Layout lay2 = TextSlicer.scan(s2, cs, 60);
            Map<Integer, String> e2 = new HashMap<>();
            e2.put(0, "换了首片\n");
            e2.put(lay2.size() / 2, "换了中间\n");
            TextSlicer.merge(s2, out, lay2, e2);
            byte[] want = expectedMerge(s2, lay2, e2);
            check(cs + " 上改首片+中间片，其余原样",
                    java.util.Arrays.equals(want, Files.readAllBytes(out.toPath())));
        }
    }

    // ================================================================ 6

    private static void lineCountChecks(Path tmp) throws Exception {
        String text = "a\nb\nc\nd\ne\nf\ng\n";
        File f = write(tmp, "lines.txt", text, "UTF-8", false);
        TextSlicer.Layout layout = TextSlicer.scan(f, "UTF-8", 4);
        checkEq("7 个换行 → 8 行", 8, layout.totalLines);

        Map<Integer, String> edits = new HashMap<>();
        checkEq("没有改动时，预期行数 == 原行数",
                layout.totalLines, TextSlicer.expectedLines(layout, edits));

        int before = layout.slices.get(0).newlines;
        edits.put(0, "x\ny\nz\n");     // 3 个换行
        checkEq("改动带来 3 个换行、原来 (" + before + ") 个 → 行数按差值算",
                layout.totalLines - before + 3, TextSlicer.expectedLines(layout, edits));

        Map<Integer, String> empty = new HashMap<>();
        empty.put(1, "没有换行的一段");
        checkEq("把有 1 个换行的片改成没有换行 → 总行数 -1",
                layout.totalLines - layout.slices.get(1).newlines,
                TextSlicer.expectedLines(layout, empty));

        Map<Integer, String> bad = new HashMap<>();
        bad.put(999, "越界的片号被忽略");
        checkEq("越界片号不影响预期行数",
                layout.totalLines, TextSlicer.expectedLines(layout, bad));

        // countLines 与 scan 必须给出同一个数（两条独立的实现路径）
        checkEq("countLines 与 scan 的总行数一致",
                layout.totalLines, TextSlicer.countLines(f, "UTF-8"));

        File emptyFile = new File(tmp.toFile(), "empty-to-text.txt");
        Files.write(emptyFile.toPath(), new byte[0]);
        TextSlicer.Layout emptyLayout = TextSlicer.scan(emptyFile, "UTF-8", 100);
        Map<Integer, String> emptyToText = new HashMap<>();
        emptyToText.put(0, "abc");
        checkEq("空文件写入非空内容时预期为 1 行", 1,
                TextSlicer.expectedLines(emptyLayout, emptyToText));
        File emptyMerged = new File(tmp.toFile(), "empty-to-text-merged.txt");
        TextSlicer.merge(emptyFile, emptyMerged, emptyLayout, emptyToText);
        checkEq("空文件写入非空内容的实际行数通过校验", 1,
                TextSlicer.countLines(emptyMerged, "UTF-8"));

        File nonEmpty = write(tmp, "text-to-empty.txt", "abc", "UTF-8", false);
        TextSlicer.Layout nonEmptyLayout = TextSlicer.scan(nonEmpty, "UTF-8", 100);
        Map<Integer, String> textToEmpty = new HashMap<>();
        textToEmpty.put(0, "");
        checkEq("非空文件清空后预期为 0 行", 0,
                TextSlicer.expectedLines(nonEmptyLayout, textToEmpty));
        File cleared = new File(tmp.toFile(), "text-to-empty-merged.txt");
        TextSlicer.merge(nonEmpty, cleared, nonEmptyLayout, textToEmpty);
        checkEq("非空文件清空后的实际行数通过校验", 0,
                TextSlicer.countLines(cleared, "UTF-8"));
    }

    // ================================================================ 7

    private static void hardCutChecks(Path tmp) throws Exception {
        // 一整段没有换行的文本（比如压缩过的 JSON）：换成"按行切"就会退化成一片
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 4000; i++) {
            sb.append((char) ('a' + (i % 26)));
        }
        String text = sb.toString();
        File f = write(tmp, "oneline.txt", text, "UTF-8", false);
        TextSlicer.Layout layout = TextSlicer.scan(f, "UTF-8", 500);

        checkEq("没有换行的文件照样切成了 8 片", 8, layout.size());
        boolean everyFull = true;
        for (int i = 0; i < layout.size() - 1; i++) {
            if (layout.slices.get(i).chars != 500) everyFull = false;
        }
        check("除最后一片外，每片都吃满了上限", everyFull);

        StringBuilder joined = new StringBuilder();
        for (TextSlicer.Slice s : layout.slices) {
            joined.append(TextSlicer.readSlice(f, "UTF-8", s, 0));
        }
        check("硬切之后拼接仍然无损", text.contentEquals(joined));

        checkEq("硬切时行号只有 1 行", 1, layout.totalLines);

        // 中文硬切：切点不能落在字符中间
        StringBuilder cn = new StringBuilder();
        for (int i = 0; i < 900; i++) cn.append('测');
        String cnText = cn.toString();
        File cf = write(tmp, "oneline-cn.txt", cnText, "UTF-8", false);
        TextSlicer.Layout cl = TextSlicer.scan(cf, "UTF-8", 100);
        StringBuilder cj = new StringBuilder();
        int bad = 0;
        for (TextSlicer.Slice s : cl.slices) {
            String part = TextSlicer.readSlice(cf, "UTF-8", s, 0);
            cj.append(part);
            bad += countReplacement(part);
        }
        check("中文硬切不劈开汉字", bad == 0 && cnText.contentEquals(cj));

        // 带 BOM 的单行文件硬切
        File bf = write(tmp, "oneline-bom.txt", text, "UTF-8", true);
        TextSlicer.Layout bl = TextSlicer.scan(bf, "UTF-8", 500);
        StringBuilder bj = new StringBuilder();
        for (int i = 0; i < bl.size(); i++) {
            bj.append(TextSlicer.readSlice(bf, "UTF-8", bl.slices.get(i),
                    i == 0 ? bl.bomLength : 0));
        }
        check("带 BOM 的单行文件硬切后拼接无损", text.contentEquals(bj));
    }

    // ================================================================ 8

    private static void edgeChecks(Path tmp) throws Exception {
        // 空文件
        File empty = new File(tmp.toFile(), "empty.txt");
        Files.write(empty.toPath(), new byte[0]);
        TextSlicer.Layout le = TextSlicer.scan(empty, "UTF-8", 100);
        checkEq("空文件行数为 0（与 countLines 同口径）", 0, le.totalLines);
        checkEq("空文件仍有一片，不会切成 0 片", 1, le.size());
        checkEq("空文件那一片是空的", "", TextSlicer.readSlice(empty, "UTF-8", le.slices.get(0), 0));

        // 只有一个字符
        File one = write(tmp, "one.txt", "x", "UTF-8", false);
        TextSlicer.Layout lo = TextSlicer.scan(one, "UTF-8", 100);
        checkEq("单字符文件 1 行", 1, lo.totalLines);
        checkEq("单字符文件读回来正确", "x", TextSlicer.readSlice(one, "UTF-8", lo.slices.get(0), 0));

        // 没有结尾换行
        File noNl = write(tmp, "nonl.txt", "a\nb\nc", "UTF-8", false);
        TextSlicer.Layout ln = TextSlicer.scan(noNl, "UTF-8", 2);
        checkEq("无结尾换行：3 行", 3, ln.totalLines);
        StringBuilder nj = new StringBuilder();
        for (TextSlicer.Slice s : ln.slices) nj.append(TextSlicer.readSlice(noNl, "UTF-8", s, 0));
        checkEq("无结尾换行：拼接无损", "a\nb\nc", nj.toString());

        // 结尾有换行（会多出一个空行，与 countLines 的既有口径一致）
        File withNl = write(tmp, "withnl.txt", "a\nb\n", "UTF-8", false);
        checkEq("结尾换行 → countLines 口径为 3 行", 3,
                TextSlicer.scan(withNl, "UTF-8", 100).totalLines);

        // 只有换行符的文件
        File nlOnly = write(tmp, "nls.txt", "\n\n\n", "UTF-8", false);
        checkEq("三个换行 → 4 行", 4, TextSlicer.scan(nlOnly, "UTF-8", 1).totalLines);
        check("三个换行在 chars=1 的上限下切成多片", TextSlicer.scan(nlOnly, "UTF-8", 1).size() > 1);

        // 单字节编码（ISO-8859-1）每字节一个字符
        File latin = write(tmp, "latin.txt", "abc\ndef\n", "ISO-8859-1", false);
        TextSlicer.Layout ll = TextSlicer.scan(latin, "ISO-8859-1", 3);
        StringBuilder lj = new StringBuilder();
        for (TextSlicer.Slice s : ll.slices) {
            lj.append(TextSlicer.readSlice(latin, "ISO-8859-1", s, 0));
        }
        checkEq("单字节编码拼接无损", "abc\ndef\n", lj.toString());

        // 非法 UTF-8 字节：不能抛异常，要变成替换字符（与 TextCodec 的 REPLACE 策略一致）
        byte[] broken = {'a', (byte) 0xE4, 'b', '\n'};
        File bf = new File(tmp.toFile(), "broken.txt");
        Files.write(bf.toPath(), broken);
        TextSlicer.Layout lb = TextSlicer.scan(bf, "UTF-8", 100);
        String bt = TextSlicer.readSlice(bf, "UTF-8", lb.slices.get(0), 0);
        check("坏字节不会让扫描/解码抛异常", bt != null && !bt.isEmpty());
        check("坏字节变成替换字符", countReplacement(bt) > 0);
    }

    // ================================================================ 工具

    /** 拼接一份「期望的输出」，与 TextSlicer.merge 独立实现，用来交叉验证 */
    private static byte[] expectedMerge(File src, TextSlicer.Layout layout,
                                        Map<Integer, String> edits) throws IOException {
        byte[] data = Files.readAllBytes(src.toPath());
        Charset cs = TextSlicer.charsetOf(layout.charset);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        for (int i = 0; i < layout.size(); i++) {
            TextSlicer.Slice s = layout.slices.get(i);
            String edited = edits.get(i);
            long start = s.startByte;
            if (i == 0 && layout.bomLength > 0) {
                out.write(data, 0, layout.bomLength);
                start += layout.bomLength;
            }
            if (edited != null) {
                out.write(edited.getBytes(cs));
            } else {
                out.write(data, (int) start, (int) (s.endByte - start));
            }
        }
        return out.toByteArray();
    }

    private static boolean isLineStart(byte[] data, long offset, int kind) {
        int o = (int) offset;
        switch (kind) {
            case TextSlicer.K_UTF16LE:
                return o >= 2 && data[o - 2] == 0x0A && data[o - 1] == 0x00;
            case TextSlicer.K_UTF16BE:
                return o >= 2 && data[o - 2] == 0x00 && data[o - 1] == 0x0A;
            default:
                return data[o - 1] == 0x0A;
        }
    }

    private static String sampleText() {
        StringBuilder sb = new StringBuilder();
        sb.append("# 配置示例\n");
        sb.append("服务器=127.0.0.1\n");
        sb.append("端口=8080\n");
        sb.append("这里是一段比较长的中文，用来把编码的多字节特征摊开\n");
        sb.append("name = \"rouxin\"\n");
        sb.append("中文与 ascii 混排 mixed 起来看边界\n");
        sb.append("最后一行带个尾巴");
        return sb.toString();
    }

    private static File write(Path dir, String name, String text, String charsetName, boolean bom)
            throws IOException {
        Charset cs = TextSlicer.charsetOf(charsetName);
        byte[] body = text.getBytes(cs);
        Path path = dir.resolve(name);
        if (!bom) {
            Files.write(path, body);
            return path.toFile();
        }
        byte[] head;
        switch (TextSlicer.kindOf(charsetName)) {
            case TextSlicer.K_UTF16LE:
                head = new byte[]{(byte) 0xFF, (byte) 0xFE};
                break;
            case TextSlicer.K_UTF16BE:
                head = new byte[]{(byte) 0xFE, (byte) 0xFF};
                break;
            default:
                head = new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
                break;
        }
        byte[] all = new byte[head.length + body.length];
        System.arraycopy(head, 0, all, 0, head.length);
        System.arraycopy(body, 0, all, head.length, body.length);
        Files.write(path, all);
        return path.toFile();
    }

    private static boolean sameBytes(File a, File b) throws IOException {
        return java.util.Arrays.equals(Files.readAllBytes(a.toPath()),
                Files.readAllBytes(b.toPath()));
    }

    // ================================================================ 9

    /**
     * 最长行。
     *
     * 它是**显示侧的折行判据**：不折行渲染时控件宽度由最长行决定，一行十万字符
     * 会撑出几十万像素宽、滚动直接废掉。这个数算错，真机上就是"进得去但滑不动"。
     */
    private static void longestLineChecks() {
        checkEq("空串 → 0", 0, TextSlicer.longestLine(""));
        check("null → 0", TextSlicer.longestLine(null) == 0);
        checkEq("没有换行时就是全长", 5, TextSlicer.longestLine("abcde"));
        checkEq("取最长的那一行", 5, TextSlicer.longestLine("ab\nabcde\nabc"));
        checkEq("末尾没有换行的那一行也算", 7, TextSlicer.longestLine("ab\nabcdefg"));
        checkEq("行尾的换行符不计入行长", 3, TextSlicer.longestLine("abc\n"));
        checkEq("连续空行不干扰", 3, TextSlicer.longestLine("abc\n\n\n"));
        checkEq("只有一个换行 → 0", 0, TextSlicer.longestLine("\n"));

        // \r 必须算行内字符：口径要与 scan / countNewlines 完全一致（只有 \n 算换行）。
        // 若实现把 \r 也当换行，这里会得到 4 而不是 5。
        String crlf = "aa\r\nbbbb\r\nc";
        checkEq("CRLF 文本：\\r 算行内字符", 5, TextSlicer.longestLine(crlf));
        checkEq("CRLF 文本：行数仍然只看 \\n", 2, TextSlicer.countNewlines(crlf));

        String[] samples = {
                "", "\n", "\n\n\n", "a", "a\n", "\na", "\na\n", "aaa\nb\ncc",
                "测\n测试\n测试测", "one\ntwo\nthree", "尾巴有无\n",
                "x".repeat(1000) + "\n" + "y".repeat(999),
                "短\n" + "z".repeat(4096),
                "\r\n\r\n", "a\rb\ncccc\rd",
        };
        boolean cross = true;
        String firstBad = "";
        for (String s : samples) {
            int naive = naiveLongest(s);
            if (naive != TextSlicer.longestLine(s)) {
                cross = false;
                firstBad = s.length() > 24 ? s.substring(0, 24) + "…" : s;
                break;
            }
        }
        check("与朴素实现（逐字符累加、遇 \\n 归零）逐条一致"
                + (cross ? "" : "  第一个不一致: " + firstBad), cross);
    }

    /** 独立实现，用来交叉验证 [TextSlicer.longestLine] */
    private static int naiveLongest(String s) {
        if (s.isEmpty()) return 0;
        int best = 0;
        int len = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '\n') {
                if (len > best) best = len;
                len = 0;
            } else {
                len++;
            }
        }
        return Math.max(best, len);
    }

    // ================================================================ 10

    /**
     * 片大小上限。
     *
     * 片大小是**性能的唯一数量级杠杆**：一次塞给控件的字符数直接决定
     * `StaticLayout` / `DynamicLayout` 的构建开销与翻页时的临时分配。
     * 3.14.0 把上限设成 200 万，真机上就表现为"加载要等、滑不动、ANR、编辑闪退"。
     * 所以"每一片真的不超过 maxChars"必须是钉死的断言，而不是"看起来对"。
     */
    private static void sliceSizeCapChecks(Path tmp) throws Exception {
        // 1) 行都很短：落刀点只能落在换行之后，每片必须严格 ≤ cap
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 3000; i++) {
            sb.append("第").append(i).append("行\n");
        }
        String text = sb.toString();
        File f = write(tmp, "many-lines.txt", text, "UTF-8", false);

        int cap = 400;
        TextSlicer.Layout layout = TextSlicer.scan(f, "UTF-8", cap);
        check("短行文本切出了多片（否则这条断言没有意义）", layout.size() > 1);

        int worst = 0;
        boolean kept = true;
        StringBuilder joined = new StringBuilder();
        for (TextSlicer.Slice s : layout.slices) {
            if (s.chars > worst) worst = s.chars;
            if (s.chars > cap) kept = false;
            joined.append(TextSlicer.readSlice(f, "UTF-8", s, 0));
        }
        check("短行文本：每片都严格不超过 maxChars", kept);
        checkEq("而且真的用满了上限（最坏的片 == cap）", cap, worst);
        check("片拼起来仍然无损", text.contentEquals(joined));

        // 2) 各片最长行的最大值必须等于整篇最长行
        int maxOfSlices = 0;
        for (TextSlicer.Slice s : layout.slices) {
            int l = TextSlicer.longestLine(TextSlicer.readSlice(f, "UTF-8", s, 0));
            if (l > maxOfSlices) maxOfSlices = l;
        }
        checkEq("各片最长行的最大值 == 整篇最长行",
                TextSlicer.longestLine(text), maxOfSlices);

        // 3) 超长行硬切：一整行没有换行，落刀点只能在一整"字符"之后
        StringBuilder one = new StringBuilder();
        for (int i = 0; i < 5000; i++) one.append('z');
        File of = write(tmp, "one-line.txt", one.toString(), "UTF-8", false);
        TextSlicer.Layout ol = TextSlicer.scan(of, "UTF-8", cap);
        int asciiOver = 0;
        StringBuilder oj = new StringBuilder();
        for (TextSlicer.Slice s : ol.slices) {
            if (s.chars > cap) asciiOver++;
            oj.append(TextSlicer.readSlice(of, "UTF-8", s, 0));
        }
        checkEq("ASCII 硬切：每片都恰好按上限切，没有一片超出", 0, asciiOver);
        checkEq("ASCII 硬切：5000 / 400 → 13 片", 13, ol.size());
        check("ASCII 硬切：拼起来无损", one.toString().contentEquals(oj));
        checkEq("ASCII 硬切：整篇仍然只有 1 行", 1, ol.totalLines);

        // 4) 四字节字符（Java String 里是代理对，占 2 个 char）：落刀点可能跨过上限。
        //    前面先放一个单字节字符，让字符数奇偶错开，逼出"超出 1"这条路径。
        StringBuilder emoji = new StringBuilder("x");
        for (int i = 0; i < 600; i++) emoji.append("\uD83D\uDE00");
        File ef = write(tmp, "one-line-emoji.txt", emoji.toString(), "UTF-8", false);
        TextSlicer.Layout el = TextSlicer.scan(ef, "UTF-8", cap);
        int emojiOver = 0;
        int emojiWorst = 0;
        StringBuilder ej = new StringBuilder();
        for (TextSlicer.Slice s : el.slices) {
            if (s.chars > cap) {
                emojiOver++;
                emojiWorst = Math.max(emojiWorst, s.chars - cap);
            }
            ej.append(TextSlicer.readSlice(ef, "UTF-8", s, 0));
        }
        check("四字节字符硬切：至多一片略超上限", emojiOver <= 1);
        check("四字节字符硬切：超出量不超过 1 个字符", emojiWorst <= 1);
        check("四字节字符硬切：拼起来无损（代理对没被劈开）",
                emoji.toString().contentEquals(ej));
    }

    private static int countReplacement(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '\uFFFD') n++;
        }
        return n;
    }

    private static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) {
            for (File k : kids) deleteTree(k);
        }
        // 断言产生的临时夹具，删掉即可
        f.delete();
    }

    private static void section(String title) {
        System.out.println();
        System.out.println("== " + title + " ==");
    }

    private static void check(String name, boolean ok) {
        total++;
        if (!ok) {
            failures++;
            System.out.println("  FAIL  " + name);
        } else {
            System.out.println("  ok    " + name);
        }
    }

    private static void checkEq(String name, Object expected, Object actual) {
        boolean ok = expected == null ? actual == null : expected.equals(actual);
        total++;
        if (!ok) {
            failures++;
            System.out.println("  FAIL  " + name + "  期望=" + expected + " 实际=" + actual);
        } else {
            System.out.println("  ok    " + name);
        }
    }
}
