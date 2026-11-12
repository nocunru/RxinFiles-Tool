import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * TextViewerActivity 查找逻辑的独立校验（纯 Java，不依赖 Android）。
 *
 * 下面这些方法是从 Kotlin 版逐行抄过来的，改了 Kotlin 那边记得同步这里：
 *   scanMatches / tailMatches / firstMatchAtOrAfter / liveWindow(syncSpans 的窗口收敛)
 *   cursorIndices(cursorCharRange) / lineNumbersFor / snippetAt
 */
public class FindCheck {

    static final int MAX_MATCHES = 20_000;
    static final int MAX_LIVE_SPANS = 300;
    static final int CURSOR_WINDOW_MATCHES = 300;
    static final int SNIPPET_CONTEXT = 28;

    static char[] needle = new char[0];
    static int[] matchStarts = new int[256];
    static int matchCount = 0;

    static boolean scanMatches(String text) {
        int size = needle.length;
        if (size == 0) return false;
        int i = 0;
        int last = text.length() - size;
        while (i <= last) {
            if (Character.toLowerCase(text.charAt(i)) == needle[0] && tailMatches(text, i + 1)) {
                if (matchCount == MAX_MATCHES) return true;
                if (matchCount == matchStarts.length) {
                    matchStarts = Arrays.copyOf(matchStarts,
                            Math.min(matchStarts.length * 2, MAX_MATCHES));
                }
                matchStarts[matchCount++] = i;
                i += size;
            } else {
                i++;
            }
        }
        return false;
    }

    static boolean tailMatches(String text, int start) {
        int size = needle.length;
        for (int j = 1; j < size; j++) {
            if (Character.toLowerCase(text.charAt(start + j - 1)) != needle[j]) return false;
        }
        return true;
    }

    static int firstMatchAtOrAfter(int offset) {
        int lo = 0, hi = matchCount;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (matchStarts[mid] < offset) lo = mid + 1; else hi = mid;
        }
        return lo;
    }

    /** syncSpans 的窗口收敛逻辑；入参是"视口换算出来的命中下标区间" */
    static int[] liveWindow(int fromIn, int toIn, int cursor) {
        int from = fromIn, to = toIn;
        if (cursor >= 0 && cursor < matchCount) {
            if (cursor < from) from = cursor;
            if (cursor >= to) to = cursor + 1;
        }
        if (to - from > MAX_LIVE_SPANS) {
            int center = (cursor >= from && cursor < to) ? cursor : from;
            int start = center - MAX_LIVE_SPANS / 2;
            start = Math.max(start, from);
            start = Math.min(start, to - MAX_LIVE_SPANS);
            from = start;
            to = start + MAX_LIVE_SPANS;
        }
        return new int[]{from, to};
    }

    /** cursorCharRange 的命中下标区间（闭区间） */
    static int[] cursorIndices(int cursor) {
        if (matchCount == 0) return new int[]{0, -1};
        int center = (cursor >= 0 && cursor < matchCount) ? cursor : 0;
        int from = Math.max(center - CURSOR_WINDOW_MATCHES, 0);
        int to = Math.min(center + CURSOR_WINDOW_MATCHES, matchCount - 1);
        return new int[]{from, to};
    }

    static int[] lineNumbersFor(String text, int count) {
        int[] out = new int[count];
        int line = 1, cursor = 0;
        for (int i = 0; i < count; i++) {
            int target = matchStarts[i];
            while (cursor < target && cursor < text.length()) {
                if (text.charAt(cursor) == '\n') line++;
                cursor++;
            }
            out[i] = line;
        }
        return out;
    }

    static String snippetAt(String text, int offset, int needleLen, int ctx) {
        int from = Math.max(offset - ctx, 0);
        int to = Math.min(offset + needleLen + ctx, text.length());
        if (from >= to) return "";
        String body = text.substring(from, to)
                .replace('\n', ' ').replace('\r', ' ').replace('\t', ' ')
                .replaceAll("\\s{2,}", " ").trim();
        StringBuilder sb = new StringBuilder();
        if (from > 0) sb.append('\u2026');
        sb.append(body);
        if (to < text.length()) sb.append('\u2026');
        return sb.toString();
    }

    // ------------------------------- 测试 -------------------------------

    static int cases = 0, fails = 0;

    static void reset(String q) {
        needle = new char[q.length()];
        for (int i = 0; i < q.length(); i++) needle[i] = Character.toLowerCase(q.charAt(i));
        matchStarts = new int[256];
        matchCount = 0;
    }

    static String found() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < matchCount; i++) sb.append(matchStarts[i]).append(',');
        return sb.toString();
    }

    /** 参照实现：忽略大小写、不重叠 */
    static String reference(String text, String q) {
        StringBuilder sb = new StringBuilder();
        String low = text.toLowerCase(Locale.ROOT);
        String n = q.toLowerCase(Locale.ROOT);
        int i = low.indexOf(n);
        while (i >= 0) {
            sb.append(i).append(',');
            i = low.indexOf(n, i + n.length());
        }
        return sb.toString();
    }

    static void eq(String label, Object got, Object want) {
        cases++;
        if (!String.valueOf(got).equals(String.valueOf(want))) {
            fails++;
            System.out.println("FAIL " + label + "\n  got  = " + got + "\n  want = " + want);
        }
    }

    static void scanCase(String label, String text, String q) {
        reset(q);
        scanMatches(text);
        eq(label, found(), reference(text, q));
    }

    static String lineOf(String text, int offset) {
        int line = 1;
        for (int i = 0; i < offset && i < text.length(); i++) {
            if (text.charAt(i) == '\n') line++;
        }
        return "L" + line;
    }

    static String lowerPerChar(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) sb.append(Character.toLowerCase(s.charAt(i)));
        return sb.toString();
    }

    static String repeat(char c, int n) {
        char[] a = new char[n];
        Arrays.fill(a, c);
        return new String(a);
    }

    static String esc(String s) {
        return s.replace("\n", "\\n").replace("\t", "\\t");
    }

    public static void main(String[] args) {
        // ---------- 1. 扫描正确性 ----------
        scanCase("普通 ASCII", "foo bar foo baz foo", "foo");
        scanCase("大小写混排", "Foo fOO fOo FOO", "foo");
        scanCase("关键词本身大写", "Foo fOO foo", "FOO");
        scanCase("中文", "目录A/文件 目录B/文件 目录A", "目录");
        scanCase("不重叠", "aaaa", "aa");
        scanCase("单字符", "abcabc", "a");
        scanCase("无命中", "abcdef", "zzz");
        scanCase("关键词比文本长", "ab", "abcdef");
        scanCase("空文本", "", "a");
        scanCase("整段相等", "hello", "hello");
        scanCase("含换行", "a\nneedle\nb needle", "needle");
        scanCase("制表符与空格", "\tfoo\tfoo  foo", "foo");
        scanCase("全角", "ＡＢＡＢ", "ＡＢ");
        scanCase("尾部命中", "xxneedle", "needle");
        scanCase("命中在 0 位", "needlexx", "needle");
        scanCase("重叠关键词取不重叠", "ababab", "aba");
        scanCase("重复两字符", "aaaaaaaa", "aa");

        Random rnd = new Random(20261007);
        String alphabet = "abABcC中目录文件 \n\t";
        int cross = 0;
        for (int t = 0; t < 4000; t++) {
            StringBuilder sb = new StringBuilder();
            int n = rnd.nextInt(60);
            for (int i = 0; i < n; i++) sb.append(alphabet.charAt(rnd.nextInt(alphabet.length())));
            int qn = 1 + rnd.nextInt(3);
            StringBuilder q = new StringBuilder();
            for (int i = 0; i < qn; i++) q.append(alphabet.charAt(rnd.nextInt(alphabet.length())));
            String text = sb.toString(), qs = q.toString();
            // 参照实现走 Locale.ROOT；只在两者与逐字符 toLowerCase 一致时才比较
            if (!text.toLowerCase(Locale.ROOT).equals(lowerPerChar(text))) continue;
            if (!qs.toLowerCase(Locale.ROOT).equals(lowerPerChar(qs))) continue;
            reset(qs);
            scanMatches(text);
            cross++;
            String got = found(), want = reference(text, qs);
            if (!got.equals(want)) {
                fails++;
                cases++;
                System.out.println("FAIL 随机交叉 text=" + esc(text) + " q=" + esc(qs)
                        + "\n  got  = " + got + "\n  want = " + want);
                break;
            }
        }
        System.out.println("随机交叉验证 " + cross + " 组");

        // ---------- 2. 上限行为 ----------
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 5000; i++) big.append("ab");
        reset("ab");
        scanMatches(big.toString());
        eq("5000 条命中全部记下", matchCount, 5000);

        StringBuilder huge = new StringBuilder();
        for (int i = 0; i < MAX_MATCHES + 500; i++) huge.append("z");
        reset("z");
        eq("超上限时报溢出", scanMatches(huge.toString()), true);
        eq("超上限时命中数正好到顶", matchCount, MAX_MATCHES);

        // ---------- 3. 二分查找 ----------
        reset("ab");
        big.setLength(0);
        for (int i = 0; i < 100; i++) big.append("ab--");
        scanMatches(big.toString());
        eq("命中数 100", matchCount, 100);
        boolean bsOk = true;
        for (int off = -5; off < big.length() + 10; off++) {
            int idx = firstMatchAtOrAfter(off);
            int expect = 0;
            while (expect < matchCount && matchStarts[expect] < off) expect++;
            if (idx != expect) {
                bsOk = false;
                System.out.println("  二分错 off=" + off + " got=" + idx + " want=" + expect);
                break;
            }
        }
        eq("二分与线性一致", bsOk, true);

        // ---------- 4. 窗口收敛不变量 ----------
        int windowFails = 0;
        String firstBad = "";
        int[] counts = {0, 1, 2, 299, 300, 301, 500, 2000, 20000};
        for (int mc : counts) {
            matchCount = mc;
            matchStarts = new int[Math.max(1, mc)];
            for (int i = 0; i < mc; i++) matchStarts[i] = i * 5;
            int step = Math.max(1, mc / 7);
            for (int cursor = -1; cursor <= Math.min(mc, 40); cursor++) {
                for (int fromIn = 0; fromIn <= mc; fromIn += step) {
                    for (int toIn = fromIn; toIn <= mc; toIn += step) {
                        int[] w = liveWindow(fromIn, toIn, cursor);
                        int f = w[0], t = w[1];
                        // 空视口本来就该是空窗口，只校验边界与上限；
                        // 非空视口（这里 fromIn/toIn 直接就是命中下标区间）则必须有内容
                        boolean ok = f >= 0 && t <= mc && f <= t && (t - f) <= MAX_LIVE_SPANS
                                && (mc == 0 ? (f == 0 && t == 0) : true)
                                && (fromIn >= toIn || f < t);
                        if (!ok) {
                            windowFails++;
                            if (firstBad.isEmpty()) {
                                firstBad = "mc=" + mc + " cursor=" + cursor + " in=["
                                        + fromIn + "," + toIn + ") -> [" + f + "," + t + ")";
                            }
                        }
                    }
                }
            }
        }
        eq("窗口不变量（边界/上限/非空）",
                windowFails == 0 ? "ok" : ("失败 " + windowFails + " 例，首例 " + firstBad), "ok");

        int overs = 0;
        Random r2 = new Random(7);
        for (int mc = 0; mc <= 1200; mc++) {
            matchCount = mc;
            matchStarts = new int[Math.max(1, mc)];
            for (int i = 0; i < mc; i++) matchStarts[i] = i;
            for (int t = 0; t < 30; t++) {
                int a1 = mc == 0 ? 0 : r2.nextInt(mc + 1);
                int a2 = mc == 0 ? 0 : r2.nextInt(mc + 1);
                int fromIn = Math.min(a1, a2), toIn = Math.max(a1, a2);
                int cursor = r2.nextInt(mc + 2) - 1;
                int[] w = liveWindow(fromIn, toIn, cursor);
                if (w[1] - w[0] > MAX_LIVE_SPANS) overs++;
                if (w[1] > mc || w[0] < 0 || w[0] > w[1]) overs++;
            }
        }
        eq("随机视口下不超上限且边界合法", overs == 0 ? "ok" : ("越界 " + overs + " 例"), "ok");

        matchCount = 20000;
        matchStarts = new int[20000];
        for (int i = 0; i < 20000; i++) matchStarts[i] = i * 10;
        for (int cursor : new int[]{0, 1, 5000, 12345, 19999}) {
            int[] w = liveWindow(0, 20000, cursor);
            eq("光标 " + cursor + " 在窗口内", cursor >= w[0] && cursor < w[1], true);
            eq("光标 " + cursor + " 窗口条数", w[1] - w[0], MAX_LIVE_SPANS);
        }

        // ---------- 5. 兜底窗口 ----------
        matchCount = 2000;
        matchStarts = new int[2000];
        for (int i = 0; i < 2000; i++) matchStarts[i] = i;
        int[] cw = cursorIndices(1000);
        eq("兜底窗口左侧跨度", 1000 - cw[0], CURSOR_WINDOW_MATCHES);
        eq("兜底窗口右侧跨度", cw[1] - 1000, CURSOR_WINDOW_MATCHES);
        cw = cursorIndices(0);
        eq("兜底窗口贴左边界", cw[0] + "," + cw[1], "0," + CURSOR_WINDOW_MATCHES);
        cw = cursorIndices(1999);
        eq("兜底窗口贴右边界", cw[0] + "," + cw[1], (1999 - CURSOR_WINDOW_MATCHES) + ",1999");

        // ---------- 6. 行号 ----------
        String doc = "line1 foo\nline2\nfoo line3\nfoo\n";
        reset("foo");
        scanMatches(doc);
        eq("行号（3 处）", Arrays.toString(lineNumbersFor(doc, matchCount)), "[1, 3, 4]");

        boolean lineOk = true;
        for (int t = 0; t < 800; t++) {
            StringBuilder sb = new StringBuilder();
            int n = rnd.nextInt(200);
            for (int i = 0; i < n; i++) {
                sb.append(rnd.nextInt(7) == 0 ? '\n' : (char) ('a' + rnd.nextInt(4)));
            }
            String text = sb.toString();
            reset("a");
            scanMatches(text);
            int[] got = lineNumbersFor(text, matchCount);
            StringBuilder g = new StringBuilder(), w = new StringBuilder();
            for (int i = 0; i < got.length; i++) {
                g.append('L').append(got[i]).append(',');
                w.append(lineOf(text, matchStarts[i])).append(',');
            }
            if (!g.toString().equals(w.toString())) {
                lineOk = false;
                System.out.println("  行号不一致 text=" + esc(text) + "\n   got=" + g + "\n   want=" + w);
                break;
            }
        }
        eq("行号与朴素实现一致（800 组随机）", lineOk, true);

        // ---------- 7. 摘要片段 ----------
        eq("短行不加省略号", snippetAt("xxfooyy", 2, 3, SNIPPET_CONTEXT), "xxfooyy");
        String longLine = repeat('a', 100) + "needle" + repeat('b', 100);
        String sn = snippetAt(longLine, 100, 6, SNIPPET_CONTEXT);
        eq("长行片段长度有界", sn.length() <= 2 + SNIPPET_CONTEXT * 2 + 6, true);
        eq("长行片段含关键词", sn.contains("needle"), true);
        eq("长行片段首尾省略号", sn.startsWith("\u2026") && sn.endsWith("\u2026"), true);
        eq("片段压掉连续空白", snippetAt("a  \t \n  b", 0, 1, SNIPPET_CONTEXT).contains("  "), false);
        eq("片段不越界（offset 在末尾）", snippetAt("abc", 2, 1, SNIPPET_CONTEXT), "abc");

        System.out.println();
        System.out.println(cases + " 项检查，" + fails + " 项失败");
        System.out.println(fails == 0 ? "ALL OK" : "HAS FAILURES");
    }
}
