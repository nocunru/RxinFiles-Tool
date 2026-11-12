package com.rxinfiles.tool

import android.graphics.Bitmap
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable

/**
 * 「这张图交给 Canvas 时会被数成多少字节」的**唯一答案**，外加一道「超了就缩」的归一化。
 *
 * 之所以要单独拎出来，是因为这件事**极容易算错**，而算错的代价是闪退：
 * 系统在 [android.graphics.RecordingCanvas.throwIfCannotDraw] 里判的是
 * `bitmap.getByteCount()`（= `rowBytes × height`，见 `Bitmap.getByteCount()`），
 * 一旦超过阈值就抛 `RuntimeException: Canvas: trying to draw too large(Nbytes) bitmap`，
 * **绘制路径上没有 try/catch 能兜住**，用户看到的就是闪退。
 *
 * 两个必须避开的错法（都真实存在过）：
 *
 * 1. **按「像素数 × 4」猜。** 每像素字节数取决于位图配置：`ARGB_8888` 是 4，
 *    `RGBA_F16` 是 8（宽度色域 / HDR 图会拿到这个配置）。猜 4 会**少算一半**。
 * 2. **按 `drawable.intrinsicWidth/Height` 算。** 那两个值是**按 density 缩放过的渲染尺寸**，
 *    不是像素数：`BitmapDrawable.computeBitmapSize()` 里是
 *    `bitmap.getScaledWidth(mTargetDensity)`，而 `Bitmap.getScaledWidth()` 走
 *    `Bitmap.scaleFromDensity()` —— 只要位图自带的 density 与目标 density 不一致，
 *    这个数就跟真实像素数差几倍。
 *
 * 所以这里只认 [Bitmap.getByteCount]：**系统拿什么数判，我们就拿什么数判。**
 */
internal object DrawableSize {

    /**
     * 这张 drawable 被绘制时，Canvas 会看到的字节数。
     *
     * - [BitmapDrawable]（含九宫格）：走 [Bitmap.getByteCount]，与实际判定完全一致。
     * - [AnimatedImageDrawable]：拿不到帧位图，按「一帧 = 全尺寸 ARGB_8888」**估上界**。
     *   动图的单帧不会超过整图尺寸，所以这是个不会低估的近似。
     * - 其它：返回 0（不参与限额判断）。
     */
    fun bytesOf(drawable: Drawable): Long = when (drawable) {
        is BitmapDrawable -> {
            val bitmap = drawable.bitmap
            if (bitmap == null || bitmap.isRecycled) 0L else bitmap.getByteCount().toLong()
        }

        is AnimatedImageDrawable -> {
            val w = drawable.intrinsicWidth
            val h = drawable.intrinsicHeight
            if (w <= 0 || h <= 0) 0L else w.toLong() * h.toLong() * 4L
        }

        else -> 0L
    }

    /** 位图的真实像素尺寸（不是 density 缩放过的 intrinsic 尺寸） */
    fun pixelsOf(drawable: Drawable): IntArray? {
        val bitmap = when (drawable) {
            is BitmapDrawable -> drawable.bitmap ?: return null
            else -> return null
        }
        if (bitmap.isRecycled) return null
        return intArrayOf(bitmap.width, bitmap.height)
    }

    /**
     * 把超过 [maxBytes] 的位图**逐次减半**，直到放得下为止；放得下就原样返回。
     *
     * 正常情况下采样策略（[ImageSampling]）已经保证解码结果不超限，
     * 这里纯粹是最后一道兜底 —— 但它必须存在：绘制站点上没有任何 try/catch
     * 能兜住 `throwIfCannotDraw` 抛出的 RuntimeException。
     *
     * 只有 [BitmapDrawable] 缩得动。动图（[AnimatedImageDrawable]）没法在这里重采样，
     * 返回 null，由调用方决定「不画」而不是「硬画然后闪退」。
     */
    fun shrinkToLimit(drawable: Drawable?, maxBytes: Long): Drawable? {
        if (drawable == null) return null
        if (bytesOf(drawable) <= maxBytes) return drawable

        val bitmap = (drawable as? BitmapDrawable)?.bitmap ?: return null
        if (bitmap.isRecycled || bitmap.width <= 1 || bitmap.height <= 1) return null

        var current = bitmap
        var owned = false
        // 上限给的是「再缩一次必定够小」的次数；halve 每轮至少砍掉一半，不会拖长
        var guard = 0
        while (current.width > 1 && current.height > 1
            && current.getByteCount().toLong() > maxBytes && guard < 32
        ) {
            val next = Bitmap.createScaledBitmap(
                current,
                (current.width / 2).coerceAtLeast(1),
                (current.height / 2).coerceAtLeast(1),
                true
            )
            if (next === current) break
            if (owned) current.recycle()
            current = next
            owned = true
            guard++
        }

        if (current.getByteCount().toLong() > maxBytes) {
            if (owned) current.recycle()
            return null
        }
        return BitmapDrawable(null, current)
    }
}
