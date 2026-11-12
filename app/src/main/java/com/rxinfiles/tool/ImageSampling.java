package com.rxinfiles.tool;

/**
 * 图片解码**尺寸策略**：一张图应该按多大的采样率解码，才既能看又不会把 Canvas 撑崩。
 *
 * <p>本类**不碰任何 Android API** —— 采样算法是纯粹的整数运算，写成纯 Java 就能在开发机上
 * 用 {@code javac}/{@code java} 直接跑断言（见 {@code tools/image-check/}）。
 * 这条策略以前埋在 {@code ImageViewerActivity} 里，**一行都断言不了**，
 * 所以「两条边都超阈值才降采样」那个会把应用打崩的写法才活到了真机上。
 *
 * <h3>两道尺寸闸门（都要满足）</h3>
 * <ul>
 *   <li>长边 ≤ {@link #MAX_LONG}（4096）：覆盖 12M/13M 像素的常见照片，不必无谓地砍半。</li>
 *   <li>像素总量 ≤ {@link #MAX_AREA}（13M ≈ 49.6MB @ ARGB_8888）：留出足够余量。</li>
 * </ul>
 *
 * <p>为什么是「同时满足」而不是「两条边都超了才管」：后者要求短边也超过阈值，
 * 于是 4852×5824 这种「有一条边很大、另一条不算大」的图会被**按原始尺寸解码** ——
 * 28,258,048 像素 × 4 字节 = 113,032,192 字节，交给 Canvas 直接抛
 * {@code RuntimeException: Canvas: trying to draw too large(113032192bytes) bitmap} 闪退。
 * {@code 4852 × 5824 = 28258048} 这个乘法就是那次崩溃的指纹，已经钉进断言。
 *
 * <h3>上限是「按位图字节数」判的，不是按像素数</h3>
 * 系统在 {@code RecordingCanvas.throwIfCannotDraw()} 里用的是 {@code bitmap.getByteCount()}
 * （= {@code rowBytes × height}），**每像素几字节取决于位图配置**：
 * ARGB_8888 是 4，RGBA_F16 是 8。所以「像素数 × 4」只能当**参考**，
 * 运行时真正的判据必须是 {@code Bitmap.getByteCount()}（见 {@code DrawableBytes.of()}）。
 * 本类因此把每像素字节数作为**参数**传进来，而不是写死 4。
 *
 * <p>{@link #MAX_DRAW_BYTES}（64MB）是留给「交给 Canvas 之前」的硬闸门：
 * 系统的阈值按设备和版本浮动（API ≤ 34 是 100MB；新版本是
 * {@code max(ro.hwui.max_texture_allocation_size, 150MB)}），64MB 对哪种都留足了余量。
 */
public final class ImageSampling {

    private ImageSampling() {
    }

    /** 解码后**长边**上限。4096 能覆盖最常见的 12MP/13MP 照片，不必无谓地砍半 */
    public static final int MAX_LONG = 4096;

    /**
     * 解码后**像素总量**上限。13M 像素 × 4 字节 ≈ 49.6MB，
     * 稳在系统 Canvas 绘制上限（旧版本固定 100MB，超过即抛
     * {@code trying to draw too large(...) bitmap}）之下。
     */
    public static final long MAX_AREA = 13_000_000L;

    /** 采样率上限，防止异常尺寸把循环拖长 */
    public static final int MAX_SAMPLE = 1 << 16;

    /**
     * 「交给 Canvas 之前」的硬上限（字节）。采样算对了就碰不到，纯属兜底 ——
     * 但必须有：绘制站点上没有任何 try/catch 能兜住，抛出来就是闪退。
     */
    public static final long MAX_DRAW_BYTES = 64L * 1024 * 1024;

    /** ARGB_8888 每像素字节数（参考值，断言与文案都用它） */
    public static final int BYTES_ARGB_8888 = 4;

    /** RGBA_F16 每像素字节数 —— 宽度色域/HDR 图会拿到这个配置，是 ARGB_8888 的两倍 */
    public static final int BYTES_RGBA_F16 = 8;

    /**
     * 满足「长边 ≤ {@link #MAX_LONG} 且 像素总量 ≤ {@link #MAX_AREA}」的**最小** 2 的幂采样率。
     *
     * <p>返回 {@code 1} 表示不需要降采样（原图本身就够小）。
     * 尺寸非法（≤0）时返回 1：宁可让调用方按原尺寸试一次，也不要在这里悄悄把图缩成 1 像素。
     */
    public static int sampleSize(int width, int height) {
        if (width <= 0 || height <= 0) {
            return 1;
        }
        int sample = 1;
        while (sample < MAX_SAMPLE) {
            if (fits(width, height, sample)) {
                return sample;
            }
            sample *= 2;
        }
        return sample;
    }

    /**
     * 按 {@code sample} 降采样后的尺寸**上界**。
     *
     * <p>用向上取整：{@code ImageDecoder} 在采样不能整除时会选最省事的一侧取整
     * （{@code ImageDecoder.getTargetDimension()}），{@code BitmapFactory} 也用
     * {@code inSampleSize} 自己算，两者都可能比 {@code width / sample} 大一点点。
     * 取上界才能让「解码结果一定不会超过多少字节」这句话站得住。
     */
    public static int[] sampledSize(int width, int height, int sample) {
        if (width <= 0 || height <= 0) {
            return new int[]{0, 0};
        }
        int s = sample < 1 ? 1 : sample;
        return new int[]{ceilDiv(width, s), ceilDiv(height, s)};
    }

    /** 按给定每像素字节数算位图占用（用 long，避免面积乘法溢出 int） */
    public static long bytes(int width, int height, int bytesPerPixel) {
        if (width <= 0 || height <= 0) {
            return 0L;
        }
        return (long) width * (long) height * (long) bytesPerPixel;
    }

    /** 按给定每像素字节数判断这张位图能不能直接交给 Canvas */
    public static boolean withinDrawLimit(int width, int height, int bytesPerPixel) {
        return bytes(width, height, bytesPerPixel) <= MAX_DRAW_BYTES;
    }

    /** 按当前策略解码 {@code width×height} 之后，位图占用的上界（字节） */
    public static long decodedBytes(int width, int height, int bytesPerPixel) {
        int[] size = sampledSize(width, height, sampleSize(width, height));
        return bytes(size[0], size[1], bytesPerPixel);
    }

    // ------------------------------------------------------------------ 内部

    /**
     * {@code width×height} 按 {@code sample} 降采样后是否同时满足长边与像素总量两道闸门。
     *
     * <p>判据用**整除**（{@code width / sample}），与旧实现一致 —— 若改成向上取整，
     * 8191×6348 这种「差一个像素就压线」的尺寸会被多降一档（4095 变 2048），
     * **白白丢掉一半清晰度**。解码器实际多出来的那一个像素（≤0.1% 面积）由
     * {@link #sampledSize} 的上界负责兜，不在这里收紧。
     */
    private static boolean fits(int width, int height, int sample) {
        int w = width / sample;
        int h = height / sample;
        return Math.max(w, h) <= MAX_LONG && (long) w * (long) h <= MAX_AREA;
    }

    private static int ceilDiv(int value, int divisor) {
        return (value + divisor - 1) / divisor;
    }
}
