package com.rxinfiles.tool

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import androidx.core.content.ContextCompat

/**
 * 条目行的**层级引导线**。
 *
 * 为什么不直接在名字前面拼 `│ ├ └ ─` 字符：这几个制表符在不同字体下的宽度并不一致
 * （还有全角 / 半角之分），而它对不对得齐**只有真机上才看得出来** ——
 * 一旦歪了，比不画更难看。自己画就没这个问题：每一级固定占一格，线永远落在格子正中。
 *
 * 画的是 `tree` 命令那一套：
 * ```
 * ├─ dirA          某一格若「那一层还有后续兄弟」，竖线一路通到行底
 * │  ├─ a.sh
 * │  └─ sub        本行是同级最后一个 → 竖线只画半行，再向右拐出去
 * │     └─ deep.bin
 * └─ top.txt
 * ```
 *
 * 层级由调用方算好传进来（见 `ArchiveSheet.EntryAdapter.submit`）——
 * 这里只负责画，不猜结构。
 */
class TreeRailView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /**
     * 每一格画不画「通到行底的竖线」，长度 == 层级深度。
     *
     * 前 `depth - 1` 项说的是**祖先那一层还有没有后续兄弟**；
     * 最后一项说的就是本行自己（`true` = 还有兄弟 → 画通线，`false` = 最后一个 → 只画半行再拐弯）。
     * 两种语义正好同形，所以共用一个数组。
     */
    private var rails: BooleanArray = EMPTY

    private val density = resources.displayMetrics.density
    private val step = STEP_DP * density
    private val elbow = ELBOW_DP * density

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * density
        strokeCap = Paint.Cap.ROUND
        color = railColor(context)
    }

    /** 深度 0 的条目没有上级目录，整条线不必存在 */
    fun bind(rails: BooleanArray) {
        this.rails = rails
        visibility = if (rails.isEmpty()) GONE else VISIBLE
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val wanted = (rails.size * step).toInt()
        // 高度要等于行高，线才铺得满整行。
        //
        // 横向 LinearLayout 通常会给 `match_parent` 的孩子传 EXACTLY（内部会按最终
        // 行高重测一次），但那是它的实现细节；这里直接吃下父给的任何上限，
        // 万一哪天布局改成了 AT_MOST，也不会退化成一条 0 高的线（画不出来还查不出原因）。
        val mode = MeasureSpec.getMode(heightMeasureSpec)
        val height = if (mode == MeasureSpec.UNSPECIFIED) {
            (FALLBACK_ROW_HEIGHT_DP * density).toInt()
        } else {
            MeasureSpec.getSize(heightMeasureSpec)
        }
        setMeasuredDimension(resolveSize(wanted, widthMeasureSpec), height)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val count = rails.size
        if (count == 0) {
            return
        }
        val bottom = height.toFloat()
        val middle = bottom / 2f
        for (i in 0 until count) {
            // 线画在格子正中，横向的拐线从中心往右伸到格子边缘
            val centerX = i * step + step / 2f
            val node = i == count - 1
            if (node) {
                canvas.drawLine(centerX, 0f, centerX, if (rails[i]) bottom else middle, paint)
                canvas.drawLine(centerX, middle, centerX + elbow, middle, paint)
            } else if (rails[i]) {
                canvas.drawLine(centerX, 0f, centerX, bottom, paint)
            }
        }
    }

    private companion object {
        val EMPTY = BooleanArray(0)

        /** 每一级往右推多少 */
        const val STEP_DP = 18f

        /** 拐出去那一小横有多长 */
        const val ELBOW_DP = 8f

        /** 父容器没给高度上限时的兜底行高（正常路径用不到，防的是一条 0 高的线） */
        const val FALLBACK_ROW_HEIGHT_DP = 48f

        /**
         * 读主题的文字色再压到很淡 —— 引导线只是背景，不能抢条目名。
         *
         * 与 `ArchiveSheet.resolveThemeColor` 同一套路：解析不到就退回中性灰，
         * 绝不因为主题属性缺失把整个列表画成黑的。
         */
        fun railColor(context: Context): Int {
            val value = TypedValue()
            val resolved = context.theme.resolveAttribute(android.R.attr.textColorPrimary, value, true)
            val base = when {
                resolved && value.resourceId != 0 -> ContextCompat.getColor(context, value.resourceId)
                resolved && value.data != 0 -> value.data
                else -> 0xFF808080.toInt()
            }
            val alpha = (255 * 0.30f).toInt().coerceIn(0, 255)
            return (base and 0x00FFFFFF) or (alpha shl 24)
        }
    }
}
