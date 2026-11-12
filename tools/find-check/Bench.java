import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * 查找扫描的耗时对比：旧实现（整段小写副本 + indexOf）vs 新实现（单遍、零分配）。
 *
 * 只为拿到量级，不代表真机数字（真机 CPU 更慢，差距只会更大）。
 */
public class Bench {

    static final int MAX_MATCHES = 20_000;

    /** 旧实现：与 3.9.2 的 TextViewerActivity.highlight 一致 */
    static int oldScan(String text, String query) {
        String haystack = text.toLowerCase(Locale.getDefault());
        String needle = query.toLowerCase(Locale.getDefault());
        int count = 0;
        int index = haystack.indexOf(needle);
        while (index >= 0 && count < 2000) {          // 旧上限
            count++;
            index = haystack.indexOf(needle, index + Math.max(1, needle.length()));
        }
        return count;
    }

    static char[] needle = new char[0];
    static int[] starts = new int[256];
    static int count;

    /** 新实现：与现在的 scanMatches 一致 */
    static int newScan(String text, String query) {
        needle = new char[query.length()];
        for (int i = 0; i < query.length(); i++) needle[i] = Character.toLowerCase(query.charAt(i));
        starts = new int[256];
        count = 0;
        int size = needle.length;
        int i = 0;
        int last = text.length() - size;
        while (i <= last) {
            if (Character.toLowerCase(text.charAt(i)) == needle[0] && tail(text, i + 1)) {
                if (count == MAX_MATCHES) return count;
                if (count == starts.length) {
                    starts = java.util.Arrays.copyOf(starts, Math.min(starts.length * 2, MAX_MATCHES));
                }
                starts[count++] = i;
                i += size;
            } else {
                i++;
            }
        }
        return count;
    }

    static boolean tail(String text, int start) {
        for (int j = 1; j < needle.length; j++) {
            if (Character.toLowerCase(text.charAt(start + j - 1)) != needle[j]) return false;
        }
        return true;
    }

    static String build(int targetChars, String word, int everyChars) {
        StringBuilder sb = new StringBuilder(targetChars + 64);
        Random rnd = new Random(42);
        String[] filler = {"the quick brown fox ", "日志行 data=12345 ", "\t\tindent\t", "中文内容也要有 "};
        while (sb.length() < targetChars) {
            sb.append(filler[rnd.nextInt(filler.length)]);
        }
        // 每隔一段插一个关键词，制造"大量命中"
        int n = sb.length() / everyChars;
        for (int k = n - 1; k >= 0; k--) {
            sb.insert(k * everyChars, word);
        }
        return sb.toString();
    }

    static void run(String label, String text, String query, boolean old) {
        // 预热
        for (int i = 0; i < 3; i++) { if (old) oldScan(text, query); else newScan(text, query); }
        int reps = 10;
        long best = Long.MAX_VALUE;
        int hits = 0;
        for (int i = 0; i < reps; i++) {
            long t0 = System.nanoTime();
            hits = old ? oldScan(text, query) : newScan(text, query);
            long dt = System.nanoTime() - t0;
            if (dt < best) best = dt;
        }
        System.out.printf("%-28s %-10s 命中 %6d  最快 %7.2f ms%n",
                label, old ? "旧" : "新", hits, best / 1e6);
    }

    public static void main(String[] args) {
        System.out.println("对照项：2MB 文本（即查看器的显示上限）");
        System.out.println();
        String[] probes = {"data", "e", "日志"};
        for (String q : probes) {
            int every = "e".equals(q) ? 60 : 600;
            String text = build(2_000_000, q, every);
            System.out.println("关键词「" + q + "」  文本 " + text.length() + " 字符");
            run("  整段小写副本 + indexOf", text, q, true);
            run("  单遍零分配扫描", text, q, false);
            System.out.println();
        }

        System.out.println("命中极密时（关键词「e」，几乎每个词都中）");
        String dense = build(2_000_000, "e", 30);
        run("  整段小写副本 + indexOf", dense, "e", true);
        run("  单遍零分配扫描", dense, "e", false);
        System.out.println();

        System.out.println("额外开销对比：旧实现每次查找会额外产生的临时字符串");
        List<String> lines = new ArrayList<>();
        lines.add("  text.lowercase()  -> " + (2_000_000 * 2 / 1024 / 1024) + " MB 副本（UTF-16）");
        lines.add("  SpannableString(text) -> 再一份同样大小的副本");
        lines.add("  再加上 setText 触发的整段重新排版（几十万字时是百毫秒量级）");
        for (String l : lines) System.out.println(l);
    }
}
