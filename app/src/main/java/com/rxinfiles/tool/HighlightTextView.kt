package com.rxinfiles.tool

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.text.Layout
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView

/**
 * 带「查找命中底色」的 TextView：底色由控件自己在 [onDraw] 里画，不用 span。
 *
 * ## 为什么不用 span
 *
 * 最直觉的做法是给命中区域挂 `BackgroundColorSpan`。但这条路有两个硬伤：
 *
 *  1. **span 一变动就会全量重排文本。** `TextView.spanChange()` 对 `CharacterStyle`
 *     这类 span 除了 `invalidate()` 还会调 `checkForResize()`（见 AOSP
 *     `TextView.java` 的 `spanChange`），而 `checkForResize()` 只要发现控件宽度是
 *     `WRAP_CONTENT` 就直接 `requestLayout()`。不折行模式**必须**是 WRAP_CONTENT
 *     （要靠它把外层 HorizontalScrollView 撑开），于是每挂一个 span 就等于把整篇
 *     文本重新排版一次。2MB 的文本翻一页就是几百毫秒起步，表现就是
 *     「输入完半天没反应、滚一下才看到底色」。
 *  2. `CharacterStyle` 不参与排版、只在绘制时应用，颜色字段改了必须自己保证**重绘**，
 *     一旦某个环节漏掉一次重绘，画面上就停在旧状态。
 *
 * 自己画没有这些问题：
 *
 *  - 不产生 span，就不会有 `spanChange → checkForResize → requestLayout` 这条链；
 *  - 底色每帧按当前可见区域现算，天然和文字同步，不存在"改完没重绘"的窗口；
 *  - 命中上万条时开销只与**当前可见的**命中数有关，与命中总数无关。
 *
 * ## 坐标
 *
 * 底色画在 `super.onDraw` **之前**，所以压在文字下面。平移量与 TextView 画文字时用的
 * 完全一致（`getCompoundPaddingLeft()` / `getExtendedPaddingTop()`），矩形由
 * `Layout.getSelectionPath()` 生成 —— 就是系统画文本选择高亮用的那个 API，
 * 折行、双向文本都能正确处理。
 */
class HighlightTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.textViewStyle
) : AppCompatTextView(context, attrs, defStyleAttr) {

    /** 命中起点，升序。只读，长度由调用方保证至少有 [count] 个元素 */
    private var starts: IntArray = EMPTY_STARTS
    private var count = 0

    /** 命中长度（= 关键词长度，所有命中一致） */
    private var matchLength = 0

    /** 当前命中在 [starts] 里的下标；-1 表示没有 */
    private var current = -1

    private var normalColor = 0
    private var currentColor = 0
    private var strokeColor = 0

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    /** 普通命中的矩形会**累积到一条 Path 上**，一帧只 drawPath 一次 */
    private val normalPath = Path()
    private val currentPath = Path()
    private val tmpPath = Path()

    private val visibleRect = Rect()

    /** 最近一次绘制用的可见行区间，用来判断滚动是否真的换了页 */
    private var drawnFirst = -1
    private var drawnLast = -1

    /** [computeVisibleLines] 的结果 */
    private var visibleFirst = -1
    private var visibleLast = -1

    init {
        strokePaint.strokeWidth = 1.5f * resources.displayMetrics.density
    }

    // ------------------------------------------------------------------ 对外接口

    /**
     * 铺一整套命中。
     *
     * 数组直接引用（不拷贝）：命中可能上万条，每次查找都复制一遍没必要。
     * 调用方保证在绘制期间不改动数组内容 —— 查找和绘制都在主线程，天然成立。
     */
    fun setHighlights(starts: IntArray, count: Int, matchLength: Int, current: Int) {
        this.starts = starts
        this.count = count
        this.matchLength = matchLength
        this.current = current
        drawnFirst = -1
        drawnLast = -1
        invalidate()
    }

    fun clearHighlights() {
        starts = EMPTY_STARTS
        count = 0
        matchLength = 0
        current = -1
        drawnFirst = -1
        drawnLast = -1
        if (normalPath.isEmpty && currentPath.isEmpty) {
            invalidate()
            return
        }
        normalPath.reset()
        currentPath.reset()
        invalidate()
    }

    fun setCurrent(index: Int) {
        if (current == index) return
        current = index
        invalidate()
    }

    fun setColors(normal: Int, currentFill: Int, currentStroke: Int) {
        normalColor = normal
        currentColor = currentFill
        strokeColor = currentStroke
        invalidate()
    }

    /** 只改当前命中的填充色（跳转闪烁用） */
    fun setCurrentColor(color: Int) {
        if (currentColor == color) return
        currentColor = color
        invalidate()
    }

    /**
     * 滚动过程中调用。
     *
     * 底色是按"可见行区间"现画的，所以滚动本身不需要重算命中；只有区间真的换了
     * （翻了一页）才让控件重绘一次。这样手感是「底色跟着文字走」，而不是
     * 「停下来才补上」，同时也不会每帧都重排一遍可见文字。
     */
    fun invalidateIfRangeChanged() {
        if (count == 0) return
        val layout = getLayout() ?: return
        if (!computeVisibleLines(layout)) return
        if (drawnFirst != visibleFirst || drawnLast != visibleLast) invalidate()
    }

    // ------------------------------------------------------------------ 绘制

    override fun onDraw(canvas: Canvas) {
        drawHighlights(canvas)
        super.onDraw(canvas)
    }

    private fun drawHighlights(canvas: Canvas) {
        if (count <= 0 || matchLength <= 0) return
        val layout = getLayout() ?: return
        if (layout.lineCount <= 0) return
        if (!computeVisibleLines(layout)) return

        drawnFirst = visibleFirst
        drawnLast = visibleLast

        val limit = length()
        val fromChar = layout.getLineStart(visibleFirst)
        val toChar = layout.getLineEnd(visibleLast)

        // 命中起点升序 —— 可见范围对应的那一段用两次二分就能框出来，
        // 不必遍历全部命中。起点在可见区之前但尾巴伸进来的也要算上。
        var lo = upperBound(fromChar - matchLength)
        var hi = upperBound(toChar - 1)
        if (lo >= hi) return

        // 整份文件挤在一行时（压缩过的 js/json、没有换行的日志），可见范围等于全文，
        // 这时候退化成围绕当前命的固定条数，避免一帧画上万个矩形。
        if (hi - lo > MAX_DRAW_MATCHES) {
            val center = if (current in lo until hi) current else lo
            var start = center - MAX_DRAW_MATCHES / 2
            start = start.coerceAtLeast(lo).coerceAtMost(hi - MAX_DRAW_MATCHES)
            lo = start
            hi = start + MAX_DRAW_MATCHES
        }

        canvas.save()
        canvas.translate(
            compoundPaddingLeft.toFloat(),
            extendedPaddingTop.toFloat()
        )

        normalPath.reset()
        for (i in lo until hi) {
            if (i == current) continue
            val s = starts[i]
            val e = s + matchLength
            if (s < 0 || e > limit) continue
            layout.getSelectionPath(s, e, tmpPath)
            normalPath.addPath(tmpPath)
        }
        if (!normalPath.isEmpty) {
            fillPaint.color = normalColor
            canvas.drawPath(normalPath, fillPaint)
        }

        if (current in lo until hi) {
            val s = starts[current]
            val e = s + matchLength
            if (s >= 0 && e <= limit) {
                layout.getSelectionPath(s, e, currentPath)
                fillPaint.color = currentColor
                canvas.drawPath(currentPath, fillPaint)
                // 描边让"当前是哪一个"在密排的命中里也一眼能认出来
                if (strokeColor != 0) {
                    strokePaint.color = strokeColor
                    canvas.drawPath(currentPath, strokePaint)
                }
            }
        }

        canvas.restore()
    }

    // ------------------------------------------------------------------ 可见范围

    /**
     * 算出控件当前真正可见的行区间。
     *
     * `getLocalVisibleRect` 已经把所有祖先的滚动与裁剪都算进去了（ScrollView 的纵向、
     * HorizontalScrollView 的横向），比自己去减 `scrollY` 稳。
     */
    private fun computeVisibleLines(layout: Layout): Boolean {
        if (!getLocalVisibleRect(visibleRect)) {
            visibleFirst = -1
            visibleLast = -1
            return false
        }
        val lines = layout.lineCount
        if (lines <= 0) {
            visibleFirst = -1
            visibleLast = -1
            return false
        }
        // 可见矩形是控件坐标系，减掉上内边距才是 layout 坐标系
        val pad = extendedPaddingTop
        visibleFirst = layout.getLineForVertical(visibleRect.top - pad).coerceIn(0, lines - 1)
        visibleLast = layout.getLineForVertical(visibleRect.bottom - pad).coerceIn(0, lines - 1)
        return true
    }

    /** 第一个起点 > [value] 的命中下标（[starts] 升序） */
    private fun upperBound(value: Int): Int {
        var lo = 0
        var hi = count
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (starts[mid] <= value) lo = mid + 1 else hi = mid
        }
        return lo
    }

    companion object {
        private val EMPTY_STARTS = IntArray(0)

        /**
         * 一帧最多画多少个命中矩形。
         *
         * 只有"整份文件挤在一行"这种退化情况下才会碰到这个上限，正常文本受限于
         * 可见区域本身，远达不到。
         */
        private const val MAX_DRAW_MATCHES = 600
    }
}
