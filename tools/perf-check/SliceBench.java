package com.rxinfiles.tool;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Map;

/**
 * 大文本「读取 / 修改」链路的性能基准。
 *
 * <p>存在的理由：3.14.0 把文本查看器换成了「按字符数切片 + 逐片拼接写回」，
 * 旧的 {@code PerfRead.java} 量的是**已被删掉的**旧读取链路，对新路径
 * 一行都没覆盖。于是「这个环节到底慢不慢」又变成了只能凭感觉的判断。
 *
 * <p>这里量的全部是**真的会跑在手机上的那份代码**（直接调
 * {@code TextSlicer}），不是等价实现 —— 只有「候选写法对比」那一节
 * 是独立实现，因为它要回答的正是「换一种写法能快多少」。
 *
 * <h2>两个必须写在最前面的限制</h2>
 * <ol>
 *   <li><b>JVM 的数字，不是 ART 的。</b>手机上绝对值大几倍（尤其
 *       {@code RandomAccessFile} 的系统调用与 {@code mmap} 的缺页）。
 *       能用的是**各步骤之间的比例**和**候选写法之间的相对速度**，
 *       不能把这里的毫秒数当成手机上的指标。</li>
 *   <li><b>这里量不到 UI。</b>真正的开销在 {@code setText} →
 *       {@code StaticLayout.generate()}，那是 framework 代码，
 *       脱离设备跑不了。本基准只能说明「IO 与字节处理这一侧还剩多少水」。 </li>
 * </ol>
 */
public final class SliceBench {

    // ------------------------------------------------------------ 计时脚手架

    private interface Job {
        void run() throws Exception;
    }

    /** 防止 JIT 把整段计算当死代码消掉 */
    private static long blackhole;

    /** 预热若干轮取最好成绩：最好成绩比平均值更能反映"没有 GC 打断时"的代价 */
    private static double timeMs(Job job, int warmup, int runs) throws Exception {
        for (int i = 0; i < warmup; i++) job.run();
        double best = Double.MAX_VALUE;
        for (int i = 0; i < runs; i++) {
            long t0 = System.nanoTime();
            job.run();
            double ms = (System.nanoTime() - t0) / 1_000_000.0;
            if (ms < best) best = ms;
        }
        return best;
    }

    private static void row(String name, String unit, double value) {
        System.out.printf("  %-46s %10.2f %s%n", name, value, unit);
    }

    // ------------------------------------------------------------ word trick

    private static final VarHandle LONG_LE =
            MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);

    private static final long NL_MASK = 0x0A0A0A0A0A0A0A0AL;
    private static final long ONES = 0x0101010101010101L;
    private static final long HIGHS = 0x8080808080808080L;

    /**
     * 8 字节一组数换行。
     *
     * 掩码只用来判断「这 8 个字节里**可能**有 0x0A」，命中才逐字节给确定答案
     * —— 这是标准的 has-zero-byte 技巧，它允许假阳性（相邻字节借位），
     * 所以**绝不能直接拿它当计数**，只能当筛子。
     */
    private static int countNlWord(byte[] b, int len) {
        int count = 0;
        int limit = len & ~7;
        int i = 0;
        for (; i < limit; i += 8) {
            long x = ((long) LONG_LE.get(b, i)) ^ NL_MASK;
            if (((x - ONES) & ~x & HIGHS) != 0L) {
                for (int k = i; k < i + 8; k++) {
                    if (b[k] == 0x0A) count++;
                }
            }
        }
        for (; i < len; i++) {
            if (b[i] == 0x0A) count++;
        }
        return count;
    }

    /**
     * UTF-8 的「String.length 口径字符数」也能量出来：非续接字节（{@code b & 0xC0 != 0x80}）
     * 一个算一个，四字节序列（{@code 0xF0..0xF7} 开头）在 Java 里是**代理对**，
     * 再补一个。
     *
     * 这条之所以重要：它说明「按字符数切片」所需的统计在 Java 里同样能做成
     * 接近内存带宽，不需要为了它去写 native。
     */
    private static int countCharsUtf8Word(byte[] b, int len) {
        int chars = 0;
        int extra = 0;      // 四字节序列额外补的 char
        int limit = len & ~7;
        int i = 0;
        for (; i < limit; i += 8) {
            long w = (long) LONG_LE.get(b, i);
            // 续接字节：高两位为 10。用掩码筛出"可能有续接字节"的组，再逐字节确认
            if (((w - ONES) & ~w & HIGHS) != 0L
                    || containsContinuation(b, i)
                    || containsFourByteLead(b, i)) {
                for (int k = i; k < i + 8; k++) {
                    int v = b[k] & 0xFF;
                    if ((v & 0xC0) != 0x80) chars++;
                    if (v >= 0xF0 && v <= 0xF7) extra++;
                }
            } else {
                chars += 8;   // 全是普通 ASCII：8 个字节 = 8 个字符
            }
        }
        for (; i < len; i++) {
            int v = b[i] & 0xFF;
            if ((v & 0xC0) != 0x80) chars++;
            if (v >= 0xF0 && v <= 0xF7) extra++;
        }
        return chars + extra;
    }

    private static boolean containsContinuation(byte[] b, int at) {
        for (int k = at; k < at + 8; k++) {
            if ((b[k] & 0xC0) == 0x80) return true;
        }
        return false;
    }

    private static boolean containsFourByteLead(byte[] b, int at) {
        for (int k = at; k < at + 8; k++) {
            int v = b[k] & 0xFF;
            if (v >= 0xF0 && v <= 0xF7) return true;
        }
        return false;
    }

    // ------------------------------------------------------------ 候选写法

    private static final int BUF = 1 << 20;

    /** 现状写法：RandomAccessFile + 1MB 缓冲 + 逐字节（TextSlicer.scan 的骨架） */
    private static int scanRaf(File f) throws Exception {
        byte[] buf = new byte[BUF];
        int nl = 0;
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            int got;
            while ((got = raf.read(buf, 0, buf.length)) > 0) {
                for (int i = 0; i < got; i++) {
                    if (buf[i] == 0x0A) nl++;
                }
            }
        }
        return nl;
    }

    /** 换成 FileInputStream：只差在有没有 BufferedInputStream 那层，对照系统调用开销 */
    private static int scanStream(File f) throws Exception {
        byte[] buf = new byte[BUF];
        int nl = 0;
        try (FileInputStream in = new FileInputStream(f)) {
            int got;
            while ((got = in.read(buf, 0, buf.length)) > 0) {
                for (int i = 0; i < got; i++) {
                    if (buf[i] == 0x0A) nl++;
                }
            }
        }
        return nl;
    }

    /** 纯 Java 的 word trick（不碰 mmap）—— 这是"不换语言"能拿到的上限 */
    private static int scanStreamWord(File f) throws Exception {
        byte[] buf = new byte[BUF];
        int nl = 0;
        try (FileInputStream in = new FileInputStream(f)) {
            int got;
            while ((got = in.read(buf, 0, buf.length)) > 0) {
                nl += countNlWord(buf, got);
            }
        }
        return nl;
    }

    /** mmap + 逐字节 get()：很多人以为 mmap 就该这么快 */
    private static int scanMmapGet(File f) throws Exception {
        int nl = 0;
        try (FileChannel ch = FileChannel.open(f.toPath())) {
            MappedByteBuffer mb = ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size());
            int n = mb.limit();
            for (int i = 0; i < n; i++) {
                if (mb.get(i) == 0x0A) nl++;
            }
        }
        return nl;
    }

    /** mmap 的正确用法：整块拷进 byte[] 再扫（get() 每个字节都要过一遍边界检查） */
    private static int scanMmapBulk(File f) throws Exception {
        int nl = 0;
        byte[] buf = new byte[BUF];
        try (FileChannel ch = FileChannel.open(f.toPath())) {
            MappedByteBuffer mb = ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size());
            while (mb.hasRemaining()) {
                int n = Math.min(buf.length, mb.remaining());
                mb.get(buf, 0, n);
                nl += countNlWord(buf, n);
            }
        }
        return nl;
    }

    /** mmap + word trick（把最有利的组合也量出来，免得说我们没给 mmap 机会） */
    private static int scanMmapPlain(File f) throws Exception {
        int nl = 0;
        byte[] buf = new byte[BUF];
        try (FileChannel ch = FileChannel.open(f.toPath())) {
            MappedByteBuffer mb = ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size());
            while (mb.hasRemaining()) {
                int n = Math.min(buf.length, mb.remaining());
                mb.get(buf, 0, n);
                for (int i = 0; i < n; i++) {
                    if (buf[i] == 0x0A) nl++;
                }
            }
        }
        return nl;
    }

    // ------------------------------------------------------------ 夹具

    private static File makeChinese(File dir, String name, long targetBytes) throws Exception {
        File f = new File(dir, name);
        String line = "行 这是一行用来测试分片与写回的文本，包含中文字符与 ASCII mixed text 0123456789.";
        byte[] lb = (line + "\n").getBytes("UTF-8");
        try (OutputStream os = new BufferedOutputStream(new FileOutputStream(f), 1 << 16)) {
            long written = 0;
            while (written < targetBytes) {
                os.write(lb);
                written += lb.length;
            }
        }
        return f;
    }

    private static File makeOneLine(File dir, String name, long targetBytes) throws Exception {
        File f = new File(dir, name);
        byte[] unit = "abcdefghijklmnopqrstuvwxyz0123456789".getBytes("UTF-8");
        try (OutputStream os = new BufferedOutputStream(new FileOutputStream(f), 1 << 16)) {
            long written = 0;
            while (written < targetBytes) {
                os.write(unit);
                written += unit.length;
            }
        }
        return f;
    }

    // ------------------------------------------------------------ main

    public static void main(String[] args) throws Exception {
        String base = args.length > 0
                ? args[0]
                : System.getProperty("java.io.tmpdir") + "/rxin-perf/slice";
        File dir = new File(base);
        dir.mkdirs();

        System.out.println("夹具目录: " + dir.getAbsolutePath());
        System.out.println();

        File f78 = makeChinese(dir, "zh-7_8m.txt", 7_800_000L);
        File f50 = makeChinese(dir, "zh-50m.txt", 50_000_000L);
        File f1line = makeOneLine(dir, "oneline-1m.txt", 1_000_000L);

        System.out.println("== 0. 夹具 ==");
        System.out.printf("  %-22s %10d B%n", f78.getName(), f78.length());
        System.out.printf("  %-22s %10d B%n", f50.getName(), f50.length());
        System.out.printf("  %-22s %10d B（无换行，单行）%n", f1line.getName(), f1line.length());
        System.out.println();

        // ---------------------------------------------------------- 1
        System.out.println("== 1. 打开文件：TextSlicer.scan（真路径，含字符数统计）==");
        for (File f : new File[]{f78, f50, f1line}) {
            final File cur = f;
            double ms = timeMs(() -> blackhole += TextSlicer.scan(cur, "UTF-8",
                    TextViewerActivityConstants.SLICE_CHARS).slices.size(), 2, 5);
            row(f.getName(), "ms", ms);
        }
        System.out.println();

        // ---------------------------------------------------------- 2
        System.out.println("== 2. 只数换行：候选写法对比（同一份文件）==");
        for (File f : new File[]{f78, f50}) {
            System.out.println("  --- " + f.getName() + " (" + f.length() + " B) ---");
            row("A RandomAccessFile + 1MB 缓冲 + 逐字节", "ms",
                    timeMs(() -> blackhole += scanRaf(f), 2, 5));
            row("B FileInputStream + 1MB 缓冲 + 逐字节", "ms",
                    timeMs(() -> blackhole += scanStream(f), 2, 5));
            row("C FileInputStream + word trick（纯 Java）", "ms",
                    timeMs(() -> blackhole += scanStreamWord(f), 2, 5));
            row("D mmap + 逐字节 get()", "ms",
                    timeMs(() -> blackhole += scanMmapGet(f), 2, 5));
            row("E mmap + 整块拷进 byte[] + 逐字节", "ms",
                    timeMs(() -> blackhole += scanMmapPlain(f), 2, 5));
            row("F mmap + 整块拷 + word trick", "ms",
                    timeMs(() -> blackhole += scanMmapBulk(f), 2, 5));
        }
        System.out.println();

        // ---------------------------------------------------------- 3
        System.out.println("== 3. UTF-8 字符数统计：逐字节 vs word trick ==");
        for (File f : new File[]{f78, f50}) {
            byte[] all = new byte[(int) f.length()];
            try (FileInputStream in = new FileInputStream(f)) {
                int off = 0;
                while (off < all.length) {
                    int got = in.read(all, off, all.length - off);
                    if (got <= 0) break;
                    off += got;
                }
            }
            System.out.println("  --- " + f.getName() + " ---");
            row("逐字节（现在的 scan 就是这样）", "ms",
                    timeMs(() -> {
                        int n = 0, extra = 0;
                        for (int i = 0; i < all.length; i++) {
                            int v = all[i] & 0xFF;
                            if ((v & 0xC0) != 0x80) n++;
                            if (v >= 0xF0 && v <= 0xF7) extra++;
                        }
                        blackhole += n + extra;
                    }, 2, 5));
            row("word trick", "ms",
                    timeMs(() -> blackhole += countCharsUtf8Word(all, all.length), 2, 5));
        }
        System.out.println();

        // ---------------------------------------------------------- 4
        System.out.println("== 4. 翻片：readSlice + 解码（SLICE_CHARS 一片）==");
        {
            TextSlicer.Layout layout = TextSlicer.scan(f78, "UTF-8",
                    TextViewerActivityConstants.SLICE_CHARS);
            System.out.printf("  7.8MB 切出 %d 片；首片 %d 字符 / %d 字节%n",
                    layout.slices.size(), layout.slices.get(0).chars,
                    layout.slices.get(0).byteLength());
            row("readSlice（读字节 + 解码）", "ms",
                    timeMs(() -> blackhole += TextSlicer.readSlice(f78, "UTF-8",
                            layout.slices.get(0), 0).length(), 2, 5));

            TextSlicer.Layout big = TextSlicer.scan(f50, "UTF-8",
                    TextViewerActivityConstants.SLICE_CHARS);
            System.out.printf("  50MB 切出 %d 片；首片 %d 字符 / %d 字节%n",
                    big.slices.size(), big.slices.get(0).chars,
                    big.slices.get(0).byteLength());
            row("readSlice（同上，50MB 文件的首片）", "ms",
                    timeMs(() -> blackhole += TextSlicer.readSlice(f50, "UTF-8",
                            big.slices.get(0), 0).length(), 2, 5));
        }
        System.out.println();

        // ---------------------------------------------------------- 5
        System.out.println("== 5. 保存（点「保存」那一刻的全部 IO）==");
        {
            TextSlicer.Layout layout = TextSlicer.scan(f78, "UTF-8",
                    TextViewerActivityConstants.SLICE_CHARS);
            File tmp = new File(dir, "out.tmp");
            row("merge：逐片拼接（无改动，7.8MB）", "ms",
                    timeMs(() -> {
                        TextSlicer.merge(f78, tmp, layout, java.util.Collections.emptyMap());
                        blackhole += tmp.length();
                    }, 1, 3));
            row("countLines：写回前的行数核对（重扫新文件）", "ms",
                    timeMs(() -> blackhole += TextSlicer.countLines(tmp, "UTF-8"), 1, 3));
            tmp.delete();
        }
        System.out.println();

        // ---------------------------------------------------------- 6
        System.out.println("== 6. 解码写法对比（readSlice 里那个大头）==");
        {
            TextSlicer.Layout layout = TextSlicer.scan(f78, "UTF-8",
                    TextViewerActivityConstants.SLICE_CHARS);
            TextSlicer.Slice s0 = layout.slices.get(0);
            byte[] raw = new byte[(int) s0.byteLength()];
            try (RandomAccessFile raf = new RandomAccessFile(f78, "r")) {
                raf.seek(s0.startByte);
                raf.readFully(raw);
            }

            row("A 现状 TextSlicer.decode（每次新建 CharsetDecoder）", "ms",
                    timeMs(() -> blackhole += TextSlicer.decode(raw, "UTF-8").length(), 2, 5));
            row("B 缓存 CharsetDecoder 实例（别再每次 new）", "ms",
                    timeMs(() -> blackhole += decodeCached(raw, "UTF-8").length(), 2, 5));
            row("C new String(bytes, charset)", "ms",
                    timeMs(() -> blackhole += new String(raw,
                            TextSlicer.charsetOf("UTF-8")).length(), 2, 5));
            row("D new String(bytes, UTF_8) 常量直给", "ms",
                    timeMs(() -> blackhole += new String(raw,
                            java.nio.charset.StandardCharsets.UTF_8).length(), 2, 5));

            // 三种写法必须给出同一篇文本，否则"快的那个"根本没资格上
            String a = TextSlicer.decode(raw, "UTF-8");
            String b = decodeCached(raw, "UTF-8");
            String c = new String(raw, java.nio.charset.StandardCharsets.UTF_8);
            System.out.println("    A/B/C 结果一致: " + (a.equals(b) && b.equals(c))
                    + "（长度 " + a.length() + "）");

            // 坏字节也要一致：REPLACE 语义不能因为换写法就变了
            byte[] broken = new byte[]{'a', (byte) 0xE4, (byte) 0xB8, 'b', (byte) 0xFF, 'c'};
            String ba = TextSlicer.decode(broken, "UTF-8");
            String bc = new String(broken, java.nio.charset.StandardCharsets.UTF_8);
            System.out.println("    坏字节输入 A/C 一致: " + ba.equals(bc)
                    + " → \"" + ba.replace('\uFFFD', '?') + "\"");
        }
        System.out.println();

        // ---------------------------------------------------------- 7
        System.out.println("== 7. 片大小的代价：每翻一片要分配多少、花多久 ==");
        {
            com.sun.management.ThreadMXBean tmx =
                    (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory
                            .getThreadMXBean();
            tmx.setThreadAllocatedMemoryEnabled(true);
            long tid = Thread.currentThread().getId();

            // 200 万是 3.14.0 的值 —— 留着它当对照，正是它把真机拖垮的
            int[] sizes = {2_000_000, 500_000, 200_000, 100_000, 20_000};
            System.out.printf("  %-12s %8s %14s %14s %12s%n",
                    "片字符上限", "切片数", "readSlice(ms)", "分配(MB)/次", "首片字符");
            for (int maxChars : sizes) {
                final TextSlicer.Layout layout = TextSlicer.scan(f78, "UTF-8", maxChars);
                final TextSlicer.Slice s0 = layout.slices.get(0);
                final int skip = s0.startByte == 0L ? layout.bomLength : 0;

                long before = tmx.getThreadAllocatedBytes(tid);
                String text = TextSlicer.readSlice(f78, "UTF-8", s0, skip);
                long alloc = tmx.getThreadAllocatedBytes(tid) - before;
                blackhole += text.length();

                final TextSlicer.Slice fs = s0;
                double ms = timeMs(() -> blackhole += TextSlicer.readSlice(f78, "UTF-8",
                        fs, skip).length(), 2, 5);
                System.out.printf("  %-12d %8d %14.2f %14.2f %12d%n",
                        maxChars, layout.slices.size(), ms,
                        alloc / 1024.0 / 1024.0, s0.chars);
            }
        }
        System.out.println();

        System.out.println("（blackhole=" + blackhole + "，仅用于防止 JIT 消除计算）");
    }

    /** 复用同一个 decoder 实例。CharsetDecoder **不是线程安全的**，只适合单线程场景 */
    private static String decodeCached(byte[] bytes, String charsetName) {
        if (CACHED_DECODER == null || !CACHED_NAME.equals(charsetName)) {
            CACHED_DECODER = TextSlicer.charsetOf(charsetName).newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPLACE)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPLACE);
            CACHED_NAME = charsetName;
        }
        try {
            return CACHED_DECODER.decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        } catch (java.nio.charset.CharacterCodingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static java.nio.charset.CharsetDecoder CACHED_DECODER;
    private static String CACHED_NAME = "";

    /** 只为了拿到 SLICE_CHARS 这个常量，避免本文件引用 Android 类型 */
    static final class TextViewerActivityConstants {
        /**
         * **必须与 `TextViewerActivity.SLICE_CHARS` 一致**。
         *
         * 这个数只是"从哪来的"的记录，本文件量的是不同片大小之间的相对代价，
         * 换掉它不影响测量结果。但两份不同步会让基准里的数字与线上行为对不上，
         * 所以 `verify-textslice.py` 有一条断言专门比对这两处。
         */
        static final int SLICE_CHARS = 100_000;

        private TextViewerActivityConstants() {
        }
    }

    private SliceBench() {
    }
}
