package com.rxinfiles.tool;

/**
 * {@link ImageSampling}（图片解码尺寸策略）的断言。
 *
 * 为什么这条策略必须有离线断言：它唯一的工作是「决定按多大采样率解码」，
 * 而**算错了不会当场报错** —— 只有当某个尺寸的图被交给 Canvas 时才会抛
 * `RuntimeException: Canvas: trying to draw too large(Nbytes) bitmap`，
 * 而绘制路径上没有 try/catch 能兜住，用户看到的就是**闪退**。
 * 更糟的是这条策略以前埋在 Activity 里，一行都断言不了；
 * 「要求两条边都超过阈值才肯降采样」那个写法就是这么活到真机上的。
 *
 * 覆盖七节：
 *   1. 策略常数本身（含与系统绘制上限的关系）
 *   2. 3.9.2 立下的那张尺寸表（逐条钉住，行为不许悄悄变）
 *   3. 那次闪退的本尊：4852×5824（崩溃指纹 113032192 字节）
 *   4. 核心不变量：**任何尺寸**解码后都不超限（穷举）
 *   5. 大尺寸与极端输入（不溢出、不卡死）
 *   6. 单调性
 *   7. 「像素数 × 4」为什么不够：RGBA_F16 会翻倍
 */
public final class ImageSamplingCheck {

    private static int total = 0;
    private static int failures = 0;

    private ImageSamplingCheck() {
    }

    public static void main(String[] args) {
        section("1. 策略常数与系统绘制上限的关系");
        constantChecks();

        section("2. 3.9.2 那张表的尺寸，一个都不许变");
        tableChecks();

        section("3. 那次闪退的本尊：4852×5824");
        crashFingerprintChecks();

        section("4. 核心不变量：任何尺寸解码后都不超限（穷举）");
        exhaustiveChecks();

        section("5. 大尺寸与极端输入");
        extremeChecks();

        section("6. 单调性");
        monotonicChecks();

        section("7. 「像素数 × 4」为什么不够：RGBA_F16 会翻倍");
        bytesPerPixelChecks();

        System.out.println();
        System.out.printf("结果: %d 项通过, %d 项失败%n", total - failures, failures);
        if (failures > 0) {
            System.exit(1);
        }
    }

    // ================================================================ 1

    private static void constantChecks() {
        checkEq("长边上限", 4096, ImageSampling.MAX_LONG);
        checkEq("像素总量上限", 13_000_000L, ImageSampling.MAX_AREA);
        checkEq("绘制硬上限 64MB", 64L * 1024 * 1024, ImageSampling.MAX_DRAW_BYTES);
        checkEq("采样率上限", 1 << 16, ImageSampling.MAX_SAMPLE);

        // 系统在 RecordingCanvas.throwIfCannotDraw 里按位图字节数判，
        // 旧版本（API ≤ 34）阈值是固定的 100MB —— 我们的硬闸门要留出足够余量：
        // 至少 1.5 倍，也就是别的路径塞进来的图也不至于贴着上限走
        long legacyCanvasLimit = 100L * 1024 * 1024;
        check("绘制硬上限对旧版 100MB 至少留出 1.5 倍余量",
                ImageSampling.MAX_DRAW_BYTES * 3L <= legacyCanvasLimit * 2L);

        // 采样策略自己的最坏结果必须留在这道闸门之下，否则重试逻辑会变成常态
        long worst = worstDecodedBytesArgb();
        check("策略最坏解码结果不超过绘制硬上限（实际 " + mb(worst) + "）",
                worst <= ImageSampling.MAX_DRAW_BYTES);

        checkEq("ARGB_8888 每像素 4 字节", 4, ImageSampling.BYTES_ARGB_8888);
        checkEq("RGBA_F16 每像素 8 字节", 8, ImageSampling.BYTES_RGBA_F16);
        checkEq("F16 正好是 ARGB_8888 的两倍",
                ImageSampling.BYTES_ARGB_8888 * 2, ImageSampling.BYTES_RGBA_F16);
    }

    // ================================================================ 2

    /** 3.9.2 立下的表。行为一旦变化必须在改动处显式更新这张表，而不是悄悄漂移 */
    private static void tableChecks() {
        int[][] cases = {
                {5000, 5652, 2, 2500, 2826},
                {8000, 6000, 2, 4000, 3000},
                {12000, 9000, 4, 3000, 2250},
                {8160, 6120, 2, 4080, 3060},
                {4032, 3024, 1, 4032, 3024},
        };
        for (int[] c : cases) {
            String tag = c[0] + "×" + c[1];
            checkEq(tag + " 采样率", c[2], ImageSampling.sampleSize(c[0], c[1]));
            int[] size = ImageSampling.sampledSize(c[0], c[1], c[2]);
            checkEq(tag + " 解码尺寸", c[3] + "×" + c[4], size[0] + "×" + size[1]);
        }

        // 与升级说明里那张 MB 表对上（容差 0.1MB，取整方式不同不至于误报）
        checkNear("5000×5652 → 约 27.0MB", 27.0, mbOf(5000, 5652), 0.15);
        checkNear("8000×6000 → 约 45.8MB", 45.8, mbOf(8000, 6000), 0.15);
        checkNear("12000×9000 → 约 25.7MB", 25.7, mbOf(12000, 9000), 0.15);
        checkNear("8160×6120 → 约 47.6MB", 47.6, mbOf(8160, 6120), 0.15);
        checkNear("4032×3024 → 约 46.5MB（保持原分辨率，别砍半）", 46.5,
                mbOf(4032, 3024), 0.15);

        // 「差一个像素就压线」的尺寸不能被多降一档 —— 那等于白丢一半清晰度。
        // 判据用整除（8191/2 = 4095），所以 sample 仍然是 2 而不是 4；
        // 这里只是提醒：sampledSize 报的是**上界**（向上取整 → 4096），两个数不一样是正常的。
        checkEq("8191×6348 落在 sample=2 而不是 4（多降一档=白丢一半清晰度）",
                2, ImageSampling.sampleSize(8191, 6348));
        checkEq("sampledSize 报的是上界（向上取整）", "4096×3174",
                ImageSampling.sampledSize(8191, 6348, 2)[0] + "×"
                        + ImageSampling.sampledSize(8191, 6348, 2)[1]);
        check("这个上界仍然落在闸门内",
                ImageSampling.decodedBytes(8191, 6348, 4) <= ImageSampling.MAX_DRAW_BYTES);
    }

    // ================================================================ 3

    /**
     * 4852 × 5824 = 28,258,048 像素，× 4 字节 = **113,032,192 字节** ——
     * 这正是真机日志里 `trying to draw too large(113032192bytes)` 那个数。
     * 当时的判据要求**两条边都超过 6400** 才降采样，而 4852/2 = 2426 < 3200，
     * 于是判定「不用降采样」，按原始尺寸解码后交给 Canvas 闪退。
     */
    private static void crashFingerprintChecks() {
        long pixels = 4852L * 5824L;
        long bytes = pixels * 4L;
        checkEq("崩溃指纹：4852×5824 的像素数", 28_258_048L, pixels);
        checkEq("崩溃指纹：× 4 字节 = 113032192", 113_032_192L, bytes);
        check("这个字节数确实超过旧版系统上限（所以当年必崩）",
                bytes > 100L * 1024 * 1024);

        // 新策略：这类「只有一条边很大」的图必须降采样
        checkEq("新策略给出 sample=2（旧策略给 1）", 2,
                ImageSampling.sampleSize(4852, 5824));
        int[] size = ImageSampling.sampledSize(4852, 5824, 2);
        checkEq("解码成 2426×2912", "2426×2912", size[0] + "×" + size[1]);
        long now = ImageSampling.decodedBytes(4852, 5824, 4);
        check("解码后 " + mb(now) + "，远低于 113MB 的崩溃值",
                now < 100L * 1024 * 1024);
        check("并且落在绘制硬闸门之内", now <= ImageSampling.MAX_DRAW_BYTES);

        // 长宽互换也要一样（EXIF 旋转过的图就是互换的）
        checkEq("长宽互换同样 sample=2", 2, ImageSampling.sampleSize(5824, 4852));
    }

    // ================================================================ 4

    /**
     * 最关键的一节：**不管原始尺寸是什么**，采样后都必须同时满足
     * 「长边 ≤ MAX_LONG」与「解码字节 ≤ 绘制硬闸门」。
     *
     * 这条不变量直接否掉旧写法：旧实现要求两条边都超阈值才降采样，
     * 于是「单边超阈值」的尺寸会原尺寸解码 —— 那正是崩溃的来源。
     */
    private static void exhaustiveChecks() {
        int limit = 4100;
        int violations = 0;
        int badSampleOne = 0;
        long worst = 0;
        String worstAt = "";

        for (int w = 1; w <= limit; w++) {
            for (int h = 1; h <= limit; h++) {
                long decoded = ImageSampling.decodedBytes(w, h, 4);
                if (decoded > ImageSampling.MAX_DRAW_BYTES) {
                    violations++;
                }
                int sample = ImageSampling.sampleSize(w, h);
                if (sample == 1) {
                    int[] s = ImageSampling.sampledSize(w, h, 1);
                    boolean ok = Math.max(s[0], s[1]) <= ImageSampling.MAX_LONG
                            && ImageSampling.bytes(s[0], s[1], 1) <= ImageSampling.MAX_AREA;
                    // sample==1 必须是「真的够小」，绝不能是「判据没用上」
                    if (!ok) {
                        badSampleOne++;
                    }
                }
                if (decoded > worst) {
                    worst = decoded;
                    worstAt = w + "×" + h;
                }
            }
        }
        check("1.." + limit + "² 全量穷举：没有一例超出绘制硬闸门", violations == 0);
        check("sample==1 的每一例都真的满足两道闸门（不是漏判）", badSampleOne == 0);
        System.out.println("        （最坏 " + worstAt + " → " + mb(worst) + "）");
        check("穷举出来的最坏值也在闸门内", worst <= ImageSampling.MAX_DRAW_BYTES);

        // 大尺寸方向：每个采样档位附近都扫一遍
        int[] multi = {1, 2, 3, 5, 1001, 4095, 4096, 20000};
        int[] heights = {1, 2, 3, 7, 4096, 30000, 100000};
        int bigViolations = 0;
        for (int sample = 1; sample <= (1 << 16); sample *= 2) {
            for (int k : multi) {
                for (int hh : heights) {
                    int w = sample * k;
                    if (w <= 0) {
                        continue;
                    }
                    long decoded = ImageSampling.decodedBytes(w, hh, 4);
                    if (decoded > ImageSampling.MAX_DRAW_BYTES) {
                        bigViolations++;
                        System.out.println("        FAIL at " + w + "×" + hh);
                    }
                }
            }
        }
        check("大尺寸方向（每个采样档位 × 8 种倍率 × 7 种高）无一超限",
                bigViolations == 0);
    }

    // ================================================================ 5

    private static void extremeChecks() {
        int[][] extremes = {
                {1, 1},
                {1, 4_000_000},
                {4_000_000, 1},
                {200000, 200000},
                {65536, 65536},
                {Integer.MAX_VALUE, Integer.MAX_VALUE},
                {Integer.MAX_VALUE, 1},
                {1, Integer.MAX_VALUE},
        };
        int violations = 0;
        for (int[] e : extremes) {
            long decoded = ImageSampling.decodedBytes(e[0], e[1], 4);
            if (decoded > ImageSampling.MAX_DRAW_BYTES) {
                violations++;
                System.out.println("        FAIL at " + e[0] + "×" + e[1]);
            }
        }
        check("极端尺寸一律不超限（含 int 上限，靠 long 运算不溢出）", violations == 0);

        checkEq("尺寸非法时不硬缩（返回 1，交给调用方处理）",
                1, ImageSampling.sampleSize(0, 100));
        checkEq("宽为负同样返回 1", 1, ImageSampling.sampleSize(-5, 100));
        checkEq("非法尺寸的字节数算作 0", 0L, ImageSampling.bytes(0, 100, 4));
        checkEq("非法尺寸的采样尺寸为 0", "0×0",
                ImageSampling.sampledSize(0, 100, 2)[0] + "×"
                        + ImageSampling.sampledSize(0, 100, 2)[1]);

        // sample 传 0 / 负数要当成 1，不能除零
        checkEq("sample=0 当 1 处理", "100×50",
                ImageSampling.sampledSize(100, 50, 0)[0] + "×"
                        + ImageSampling.sampledSize(100, 50, 0)[1]);
        checkEq("sample=-3 当 1 处理", "100×50",
                ImageSampling.sampledSize(100, 50, -3)[0] + "×"
                        + ImageSampling.sampledSize(100, 50, -3)[1]);

        checkEq("int 上限相乘不溢出", (long) Integer.MAX_VALUE * (long) Integer.MAX_VALUE * 4L,
                ImageSampling.bytes(Integer.MAX_VALUE, Integer.MAX_VALUE, 4));
    }

    // ================================================================ 6

    private static void monotonicChecks() {
        boolean ok = true;
        for (int w = 1; w <= 600 && ok; w++) {
            for (int h = 1; h <= 600; h++) {
                int here = ImageSampling.sampleSize(w, h);
                int wider = ImageSampling.sampleSize(w + 1, h);
                int taller = ImageSampling.sampleSize(w, h + 1);
                if (wider < here || taller < here) {
                    ok = false;
                    System.out.println("        FAIL 单调性 at " + w + "×" + h);
                    break;
                }
            }
        }
        check("图变大采样率不会变小（600² 穷举）", ok);

        boolean longSideOk = true;
        int[] dims = {1, 2, 100, 4096, 4097, 8192, 20000, 123456};
        for (int w : dims) {
            for (int h : dims) {
                int s = ImageSampling.sampleSize(w, h);
                int[] size = ImageSampling.sampledSize(w, h, s);
                if (Math.max(size[0], size[1]) > ImageSampling.MAX_LONG) {
                    longSideOk = false;
                    System.out.println("        FAIL 长边 at " + w + "×" + h
                            + " → " + size[0] + "×" + size[1]);
                }
            }
        }
        check("任何尺寸采样后长边都 ≤ " + ImageSampling.MAX_LONG, longSideOk);
    }

    // ================================================================ 7

    /**
     * 这一节把「为什么运行时必须用 `Bitmap.getByteCount()`」钉成事实：
     * 同一个像素数，换成 RGBA_F16（宽度色域 / HDR 图会拿到）就翻倍。
     * 只看像素数、按 ×4 估的写法会**少算一半**，把闸门变成摆设。
     */
    private static void bytesPerPixelChecks() {
        int w = 4096;
        int h = 3174;   // 4096×3174 ≈ 13.0M 像素，正好是策略允许的最坏区
        long argb = ImageSampling.bytes(w, h, ImageSampling.BYTES_ARGB_8888);
        long f16 = ImageSampling.bytes(w, h, ImageSampling.BYTES_RGBA_F16);

        check("ARGB_8888 下 " + mb(argb) + " 落在闸门内",
                argb <= ImageSampling.MAX_DRAW_BYTES);
        check("同样的像素数换成 RGBA_F16 就是 " + mb(f16) + "，超出闸门",
                f16 > ImageSampling.MAX_DRAW_BYTES);
        check("所以「像素数 × 4」不足为凭 —— 运行时必须问 Bitmap.getByteCount()",
                f16 == argb * 2);
        check("F16 的这份超限能被重试兜住（再降一档就够）",
                ImageSampling.bytes(w / 2, h / 2, ImageSampling.BYTES_RGBA_F16)
                        <= ImageSampling.MAX_DRAW_BYTES);
        check("withinDrawLimit 对 F16 会说不", !ImageSampling.withinDrawLimit(
                w, h, ImageSampling.BYTES_RGBA_F16));
        check("withinDrawLimit 对 ARGB_8888 会说可以",
                ImageSampling.withinDrawLimit(w, h, ImageSampling.BYTES_ARGB_8888));
    }

    // ================================================================ 工具

    /** 策略在最坏情况下的解码字节数：13M 像素上界，外加解码器取整可能多出的 1 像素 */
    private static long worstDecodedBytesArgb() {
        long worst = 0;
        for (int w = 1; w <= 4100; w++) {
            // 只扫判据边界附近：h 取「刚好还能留在 sample=1」与「刚好压线」
            int h1 = (int) Math.min(ImageSampling.MAX_LONG, ImageSampling.MAX_AREA / w);
            if (h1 >= 1) {
                worst = Math.max(worst, ImageSampling.decodedBytes(w, h1, 4));
            }
            worst = Math.max(worst, ImageSampling.decodedBytes(w, 4096, 4));
        }
        return worst;
    }

    private static double mbOf(int w, int h) {
        return ImageSampling.decodedBytes(w, h, 4) / 1024.0 / 1024.0;
    }

    private static String mb(long bytes) {
        return String.format("%.1fMB", bytes / 1024.0 / 1024.0);
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

    private static void checkNear(String name, double expected, double actual, double tol) {
        boolean ok = Math.abs(expected - actual) <= tol;
        total++;
        if (!ok) {
            failures++;
            System.out.printf("  FAIL  %s  期望=%.2f 实际=%.2f%n", name, expected, actual);
        } else {
            System.out.println("  ok    " + name);
        }
    }
}
