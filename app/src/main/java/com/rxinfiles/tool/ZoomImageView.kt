package com.rxinfiles.tool

import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.abs
import kotlin.math.min

/**
 * 可缩放图片视图。
 *
 * 自己实现而不是引入第三方库的原因：本工程是离线构建，多一个依赖就多一份
 * 拿不到 jar 的风险；而这里需要的功能（捏合缩放、拖动、双击缩放、旋转、
 * 左右翻页）用一个 Matrix 就够，代码量远小于适配一个库。
 *
 * 变换模型：**只用一条 Matrix**（内容坐标 → 视图坐标），初始值是
 * 「绕内容中心旋转 → 适配缩放 → 平移到视图中心」。
 * 之后所有手势都是在这条矩阵上做后乘（postScale/postTranslate），
 * 于是「以手指为焦点缩放」这件事天然成立 —— 后乘的变换作用在视图坐标系里，
 * 焦点处的像素保持不动。
 *
 * 另外承担「翻页」的判断：**没有放大时**横向快速滑动才翻页，
 * 已放大时横向滑动是看图，不会误翻 —— 这是看图应用最容易被做错的交互。
 *
 * 还承担**绘制安全的最后一道闸门**：`Canvas.drawBitmap` 对过大的位图会直接抛
 * `RuntimeException: Canvas: trying to draw too large(Nbytes) bitmap`（见
 * `RecordingCanvas.throwIfCannotDraw`），绘制路径上没有 try/catch 能兜住，
 * 抛出来就是闪退。所以 [setImage] 会先把超限的图缩到限内；
 * 实在缩不动（动图）就标记为「不画」，[onDraw] 见到标记直接返回 ——
 * **宁可不显示，也绝不闪退。**
 */
class ZoomImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    /** 翻页请求：-1 上一张，+1 下一张 */
    var onPageRequest: ((Int) -> Unit)? = null

    /** 单击（用于切换界面上的操作栏） */
    var onSingleTap: (() -> Unit)? = null

    /** 双击/捏合导致缩放状态变化时回调（用于刷新「适应/1:1」按钮文案） */
    var onScaleChanged: ((Float) -> Unit)? = null

    /** 图片因超出绘制上限而被拦下（没画出来）时回调，用来给用户一句提示 */
    var onImageBlocked: ((Drawable) -> Unit)? = null

    private var image: Drawable? = null
    private val matrix = Matrix()

    /** 当前这张图是否被闸门拦下（true = [onDraw] 不画） */
    private var imageBlocked = false

    private var rotation = 0
    private var fitScale = 1f
    private var maxScale = 1f

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val current = currentScale()
                if (current <= 0f) return false
                val target = current * detector.scaleFactor
                val clamped = target.coerceIn(fitScale * 0.5f, maxScale)
                val factor = clamped / current
                if (abs(factor - 1f) < 0.001f) return false
                matrix.postScale(factor, factor, detector.focusX, detector.focusY)
                clampTranslation()
                invalidate()
                notifyScale()
                return true
            }
        }
    )

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {

            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                onSingleTap?.invoke()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (isZoomed()) {
                    fitToView()
                } else {
                    val target = fitScale * 2.5f
                    val factor = target / currentScale()
                    matrix.postScale(factor, factor, e.x, e.y)
                    clampTranslation()
                    invalidate()
                    notifyScale()
                }
                return true
            }

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                if (!isZoomed()) return false
                matrix.postTranslate(-distanceX, -distanceY)
                clampTranslation()
                invalidate()
                return true
            }

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                // 只在「没放大」时翻页；放大状态下横向滑动属于看图
                if (isZoomed()) return false
                if (abs(velocityX) < abs(velocityY) * 1.2f) return false
                if (abs(velocityX) < FLING_MIN_VELOCITY) return false
                onPageRequest?.invoke(if (velocityX < 0) 1 else -1)
                return true
            }
        }
    )

    init {
        isClickable = true
        isFocusable = true
    }

    // ------------------------------------------------------------------ 内容

    fun setImage(drawable: Drawable?) {
        if (drawable == null) {
            imageBlocked = false
            image = null
            rotation = 0
            fitToView()
            return
        }
        // 先过闸门：超出 Canvas 绘制上限的图必须在这里解决掉，不能留给 onDraw。
        // 上限的数值只有一处真源（ImageSampling.MAX_DRAW_BYTES），这里不再抄一份 ——
        // 同一个数写两处，早晚有一处忘记改。
        val safe = DrawableSize.shrinkToLimit(drawable, ImageSampling.MAX_DRAW_BYTES)
        imageBlocked = safe == null
        if (imageBlocked) {
            Log.e(
                TAG,
                "refuse to draw: ${DrawableSize.bytesOf(drawable)}B > " +
                    "${ImageSampling.MAX_DRAW_BYTES}B"
            )
            onImageBlocked?.invoke(drawable)
        }
        image = safe
        rotation = 0
        fitToView()
    }

    /** 当前这张图是否被绘制闸门拦下了（没画出来） */
    fun isImageBlocked(): Boolean = imageBlocked

    fun currentDrawable(): Drawable? = image

    /** 顺时针旋转 90° */
    fun rotate() {
        rotation = (rotation + 90) % 360
        fitToView()
    }

    fun rotationDegrees(): Int = rotation

    /** 适应屏幕 */
    fun fitToView() {
        val drawable = image ?: return
        val iw = drawable.intrinsicWidth.toFloat()
        val ih = drawable.intrinsicHeight.toFloat()
        if (iw <= 0f || ih <= 0f || width <= 0 || height <= 0) return

        val vw = width.toFloat()
        val vh = height.toFloat()
        // 旋转 90/270 度时，包围盒宽高互换
        val boxW = if (rotation % 180 == 0) iw else ih
        val boxH = if (rotation % 180 == 0) ih else iw

        val fit = min(vw / boxW, vh / boxH)
        fitScale = if (fit > 0f) fit else 1f
        maxScale = fitScale * 8f

        matrix.reset()
        matrix.postTranslate(-iw / 2f, -ih / 2f)   // 内容中心 → 原点
        matrix.postRotate(rotation.toFloat())      // 绕中心旋转
        matrix.postScale(fitScale, fitScale)       // 适配缩放
        matrix.postTranslate(vw / 2f, vh / 2f)     // 移到视图中心
        invalidate()
        notifyScale()
    }

    /** 当前缩放倍数（相对图片原始像素） */
    fun currentScale(): Float = try {
        matrix.mapRadius(1f)
    } catch (t: Throwable) {
        1f
    }

    fun isZoomed(): Boolean = currentScale() > fitScale * 1.05f

    /** 缩放适应后的最小倍数（1:1 是否等于「适应」由它决定） */
    fun fitScaleValue(): Float = fitScale

    /** 以某个焦点缩放到指定倍数（1:1 按钮用） */
    fun setScale(target: Float, focusX: Float = width / 2f, focusY: Float = height / 2f) {
        val current = currentScale()
        if (current <= 0f || fitScale <= 0f) return
        val clamped = target.coerceIn(fitScale * 0.5f, maxScale)
        val factor = clamped / current
        if (abs(factor - 1f) < 0.001f) return
        matrix.postScale(factor, factor, focusX, focusY)
        clampTranslation()
        invalidate()
        notifyScale()
    }

    // ------------------------------------------------------------------ 绘制

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        fitToView()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // 被闸门拦下的图根本不会进 image（见 setImage），这里再显式挡一次：
        // 绘制路径上没有 try/catch 能兜住 throwIfCannotDraw 抛出的 RuntimeException，
        // 漏一张就是把「显示不出来」升级成「闪退」
        if (imageBlocked) return
        val drawable = image ?: return
        canvas.save()
        canvas.concat(matrix)
        drawable.setBounds(0, 0, drawable.intrinsicWidth, drawable.intrinsicHeight)
        drawable.draw(canvas)
        canvas.restore()
    }

    // ------------------------------------------------------------------ 手势

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val handled = gestureDetector.onTouchEvent(event)
        scaleDetector.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            parent?.requestDisallowInterceptTouchEvent(true)
        }
        return handled || scaleDetector.isInProgress || super.onTouchEvent(event)
    }

    // ------------------------------------------------------------------ 内部

    /** 平移到边界就停住：内容比视图小时居中，比视图大时不许露出空白 */
    private fun clampTranslation() {
        val drawable = image ?: return
        val iw = drawable.intrinsicWidth.toFloat()
        val ih = drawable.intrinsicHeight.toFloat()
        if (iw <= 0f || ih <= 0f) return

        val rect = RectF(0f, 0f, iw, ih)
        matrix.mapRect(rect)

        val vw = width.toFloat()
        val vh = height.toFloat()
        var dx = 0f
        var dy = 0f

        if (rect.width() <= vw) {
            dx = (vw - rect.width()) / 2f - rect.left
        } else if (rect.left > 0f) {
            dx = -rect.left
        } else if (rect.right < vw) {
            dx = vw - rect.right
        }

        if (rect.height() <= vh) {
            dy = (vh - rect.height()) / 2f - rect.top
        } else if (rect.top > 0f) {
            dy = -rect.top
        } else if (rect.bottom < vh) {
            dy = vh - rect.bottom
        }

        if (dx != 0f || dy != 0f) {
            matrix.postTranslate(dx, dy)
        }
    }

    private fun notifyScale() {
        onScaleChanged?.invoke(currentScale())
    }

    private companion object {
        const val TAG = "ZoomImageView"

        /** 触发翻页的最小横向速度（屏幕像素/秒） */
        const val FLING_MIN_VELOCITY = 600f
    }
}
