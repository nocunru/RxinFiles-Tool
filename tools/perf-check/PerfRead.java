import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;

/**
 * 文本查看器「读取 → 探测编码 → 解码 → 统计 → 扫描」各步骤的耗时基准。
 *
 * ## 这个文件是干什么的
 *
 * 用来回答一个问题：**打开一个大文本文件，时间到底花在哪一步。**
 * 有了它，「优化读取方案」这类提案才有一个可对照的基线 ——
 * 否则很容易花几天去抠一个只占 10 ms 的环节，而真正的瓶颈在别处（见
 * `文本查看器-性能优化方案.md`）。
 *
 * ## 怎么读结果
 *
 * 关心的是**各步之间的相对比例**，不是绝对值。跑出来大概长这样（4 MB 中文 UTF-8）：
 *
 * ```
 *   读取字节   2.8 ms
 *   探测编码   1.5 ms
 *   解码成串   5.5 ms
 *   统计行数   0.5 ms      ← 以上合计约 10 ms，就是读取方案的全部工作
 * ```
 *
 * ## 两个必须知道的限制
 *
 * 1. **这是 JVM（HotSpot）的数字，不是 ART 的。** 手机上绝对耗时会大几倍
 *    （CPU 更弱、JIT/AOT 行为不同），但「哪一步占大头」这种结构性结论仍然成立。
 *    真机数字要打点测，见方案文档第 7 节。
 * 2. **下面的夹具是与 Kotlin 源码手工对齐的，不会自动同步。**
 *    改了 `TextCodec.isValidUtf8` / `TextViewerActivity.scanMatches` / `countLines`
 *    之后，这里的数字就不再代表真实实现 —— 要同步改这个文件。
 *    （它们只是**测量用**的等价实现，不参与任何断言；断言在别的目录。）
 *
 * ## 跑法
 *
 *     sh tools/perf-check/run.sh
 */
public class PerfRead {

    /** 夹具文本：中英混排，接近真机上会打开的配置文件 / 日志 */
    static final String UNIT_CN =
            "中文测试内容，包含标点符号与全角字符。The quick brown fox jumps over the lazy dog. 1234567890\n";
    static final String UNIT_ASCII =
            "line of ascii text 0123456789 abcdefghijklmnopqrstuvwxyz -- config value = true\n";

    static Object SINK;

    static byte[] gen(int targetBytes, String unit, Charset cs) {
        byte[] u = unit.getBytes(cs);
        ByteArrayOutputStream out = new ByteArrayOutputStream(targetBytes + u.length);
        while (out.size() < targetBytes) out.write(u, 0, u.length);
        return Arrays.copyOf(out.toByteArray(), targetBytes);
    }

    /** 预热 3 轮再计时，取平均与最快 —— 最快递掉 GC 抖动 */
    static long bench(String name, int reps, Runnable r) {
        for (int i = 0; i < 3; i++) r.run();
        long best = Long.MAX_VALUE, sum = 0;
        for (int i = 0; i < reps; i++) {
            long t0 = System.nanoTime();
            r.run();
            long dt = System.nanoTime() - t0;
            sum += dt;
            if (dt < best) best = dt;
        }
        System.out.printf("  %-44s 平均 %8.2f ms   最快 %8.2f ms%n", name, sum / reps / 1e6, best / 1e6);
        return best;
    }

    // ---- 与 TextCodec.isValidUtf8 手工对齐（只用于测量） ----
    static boolean isValidUtf8(byte[] bytes, int off, int len) {
        int i = off, n = off + len;
        while (i < n) {
            int b = bytes[i] & 0xFF;
            if (b < 0x80) { i++; }
            else if (b >= 0xC2 && b <= 0xDF) {
                if (i + 1 >= n) return true;
                if ((bytes[i + 1] & 0xFF) < 0x80 || (bytes[i + 1] & 0xFF) > 0xBF) return false;
                i += 2;
            } else if (b >= 0xE0 && b <= 0xEF) {
                if (i + 2 >= n) return true;
                if ((bytes[i + 1] & 0xFF) < 0x80 || (bytes[i + 1] & 0xFF) > 0xBF) return false;
                if ((bytes[i + 2] & 0xFF) < 0x80 || (bytes[i + 2] & 0xFF) > 0xBF) return false;
                i += 3;
            } else if (b >= 0xF0 && b <= 0xF4) {
                if (i + 3 >= n) return true;
                for (int k = 1; k <= 3; k++) {
                    if ((bytes[i + k] & 0xFF) < 0x80 || (bytes[i + k] & 0xFF) > 0xBF) return false;
                }
                i += 4;
            } else return false;
        }
        return true;
    }

    /** 对照项：native 解码器 REPORT 模式（TextCodec.isStrictlyDecodable 走的就是它） */
    static boolean decodableStrict(byte[] b, int off, int len, Charset cs) {
        try {
            cs.newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(b, off, len));
            return true;
        } catch (CharacterCodingException e) {
            return false;
        }
    }

    // ---- 与 TextCodec.countLines 手工对齐（只用于测量） ----
    static int countLines(String s) {
        if (s.isEmpty()) return 0;
        int lines = 1;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == '\n') lines++;
        return lines;
    }

    // ---- 与 TextViewerActivity.scanMatches 手工对齐（只用于测量） ----
    static int scanMatches(String text, String query, int max) {
        char[] needle = new char[query.length()];
        for (int i = 0; i < needle.length; i++) needle[i] = Character.toLowerCase(query.charAt(i));
        int size = needle.length, found = 0, i = 0, last = text.length() - size;
        while (i <= last) {
            if (Character.toLowerCase(text.charAt(i)) == needle[0]) {
                boolean ok = true;
                for (int j = 1; j < size; j++) {
                    if (Character.toLowerCase(text.charAt(i + j)) != needle[j]) { ok = false; break; }
                }
                if (ok) {
                    if (found == max) return found + 1;
                    found++;
                    i += size;
                    continue;
                }
            }
            i++;
        }
        return found;
    }

    public static void main(String[] args) throws Exception {
        File dir = new File(args.length > 0 ? args[0] : ".");
        dir.mkdirs();

        final int FULL = 4 * 1024 * 1024;
        final int PREVIEW = 512 * 1024;
        final int SAMPLE = 64 * 1024;

        File fCn = new File(dir, "cn.txt");
        File fAscii = new File(dir, "ascii.txt");
        File fGbk = new File(dir, "gbk.txt");
        Files.write(fCn.toPath(), gen(FULL, UNIT_CN, StandardCharsets.UTF_8));
        Files.write(fAscii.toPath(), gen(FULL, UNIT_ASCII, StandardCharsets.UTF_8));
        Files.write(fGbk.toPath(), gen(FULL, UNIT_CN, Charset.forName("GBK")));

        final byte[] cn = Files.readAllBytes(fCn.toPath());
        final byte[] ascii = Files.readAllBytes(fAscii.toPath());
        final byte[] gbk = Files.readAllBytes(fGbk.toPath());

        System.out.println("=== 0. 夹具 ===");
        System.out.printf("  UTF-8 中文 %d B   ASCII %d B   GBK %d B%n", cn.length, ascii.length, gbk.length);
        final String cnText = new String(cn, StandardCharsets.UTF_8);
        System.out.printf("  解码后字符数: 中文文本 %d chars%n", cnText.length());

        System.out.println("\n=== 1. 读取（RandomAccessFile.readFully，TextCodec.read 第一步） ===");
        bench("读 4MB", 8, () -> {
            try (RandomAccessFile raf = new RandomAccessFile(fCn, "r")) {
                byte[] buf = new byte[FULL];
                raf.readFully(buf);
                SINK = buf;
            } catch (Exception e) { throw new RuntimeException(e); }
        });
        bench("读 512KB（超限文件的预览量）", 8, () -> {
            try (RandomAccessFile raf = new RandomAccessFile(fCn, "r")) {
                byte[] buf = new byte[PREVIEW];
                raf.readFully(buf);
                SINK = buf;
            } catch (Exception e) { throw new RuntimeException(e); }
        });

        System.out.println("\n=== 2. 编码探测（现状：手写循环全量扫） ===");
        bench("isValidUtf8 全量 4MB (UTF-8 文本)", 8, () -> SINK = isValidUtf8(cn, 0, cn.length));
        bench("isValidUtf8 全量 4MB (ASCII 文本)", 8, () -> SINK = isValidUtf8(ascii, 0, ascii.length));
        bench("isValidUtf8 采样前 64KB (UTF-8)", 8, () -> SINK = isValidUtf8(cn, 0, SAMPLE));
        bench("isValidUtf8 采样前 64KB (GBK)", 8, () -> SINK = isValidUtf8(gbk, 0, SAMPLE));
        System.out.println("  -- 对照：native 解码器 REPORT 模式 --");
        bench("decodableStrict 全量 4MB (UTF-8 文本)", 8, () -> SINK = decodableStrict(cn, 0, cn.length, StandardCharsets.UTF_8));
        bench("decodableStrict 采样前 64KB (UTF-8)", 8, () -> SINK = decodableStrict(cn, 0, SAMPLE, StandardCharsets.UTF_8));
        bench("decodableStrict 采样前 64KB (GBK)", 8, () -> SINK = decodableStrict(gbk, 0, SAMPLE, StandardCharsets.UTF_8));

        System.out.println("\n=== 3. 解码（4MB → String） ===");
        bench("CharsetDecoder REPLACE (现状)", 8, () -> {
            try {
                SINK = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPLACE)
                    .onUnmappableCharacter(CodingErrorAction.REPLACE)
                    .decode(ByteBuffer.wrap(cn));
            } catch (CharacterCodingException e) { throw new RuntimeException(e); }
        });
        bench("new String(bytes, UTF_8)", 8, () -> SINK = new String(cn, StandardCharsets.UTF_8));

        System.out.println("\n=== 4. 尾部裁剪的拷贝代价（现状：copyOf 整个数组） ===");
        bench("copyOf 512KB（只为砍掉末尾 1~3 字节）", 8, () -> SINK = Arrays.copyOf(cn, PREVIEW - 2));

        System.out.println("\n=== 5. 解码之后的字符串扫描 ===");
        bench("countLines 扫 278 万 chars", 8, () -> SINK = countLines(cnText));
        bench("scanMatches 找 \"the\"（不区分大小写）", 8, () -> SINK = scanMatches(cnText, "the", 20000));
        bench("indexOf 对照 \"the\"", 8, () -> {
            int from = 0, n = 0;
            while (true) {
                int i = cnText.indexOf("the", from);
                if (i < 0 || n >= 20000) break;
                n++; from = i + 3;
            }
            SINK = n;
        });

        System.out.println("\n=== 6. 采样判定能否区分 UTF-8 / GBK（决定采样长度能否缩短） ===");
        System.out.printf("  UTF-8 文本前64KB 合法? %s%n", isValidUtf8(cn, 0, SAMPLE));
        System.out.printf("  GBK   文本前64KB 合法? %s  ← 期望 false%n", isValidUtf8(gbk, 0, SAMPLE));
        System.out.printf("  GBK   文本前1KB  合法? %s  ← 期望 false%n", isValidUtf8(gbk, 0, 1024));
        System.out.printf("  GBK   文本前256B  合法? %s  ← 期望 false%n", isValidUtf8(gbk, 0, 256));
        System.out.printf("  ASCII 文本前64KB 合法? %s  ← 期望 true%n", isValidUtf8(ascii, 0, SAMPLE));

        System.out.println("\n=== 7. 渲染前那几步字符串处理（render() 里的） ===");
        final String big = cnText;
        final String trimmed = big.substring(0, 2_000_000);
        bench("substring 截到 200 万字符", 8, () -> SINK = big.substring(0, 2_000_000));
        bench("String 拼接 text + suffix（plainText）", 8, () -> SINK = trimmed + "\n\n--- 尾部提示 ---\n");
        bench("text.toByteArray() 只为拿字节长度", 8, () -> SINK = trimmed.getBytes(StandardCharsets.UTF_8));
        bench("new String(char[]) 拷贝（SpannableString 那条路）", 8, () -> SINK = new String(trimmed.toCharArray()));
        bench("对照：只取 .length", 8, () -> SINK = trimmed.length());
        System.out.printf("%n  注：显示上限 %d 字符，而这批文本共 %d 字符（超出 %.0f%%）%n",
                2_000_000, big.length(), (big.length() / 2_000_000.0 - 1) * 100);

        System.out.println("\n跑完。以上是 JVM 数字，手机上的绝对值更大，看比例即可。");
    }
}
