package com.rxinfiles.tool

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog

/**
 * 面板内部的「对话框」—— 菜单 / 输入 / 确认三合一。
 *
 * **为什么不用 AlertDialog**：真机反馈「点书签行尾的⋮，什么也没出现」。
 * 排查下来是**在 BottomSheetDialog 之上再开一个窗口**并不可靠：二级窗口被面板
 * 挡住，用户看不到任何东西（同一层级的窗口顺序在不同 ROM 上表现不一致）。
 * 而「面板里弹 AlertDialog」在本工程里是刚需 —— 加密压缩包**唯一**的入口就是
 * 那个密码框，框弹不出来就等于整个加密功能不存在。
 *
 * 所以这里把要问的东西**画在面板自己内部**：一层半透明遮罩 + 一张卡片，
 * 从头到尾只用到面板已有的那一个窗口，不存在层级问题。
 *
 * 用法：面板布局里 `<include layout="@layout/sheet_dialog_layer"/>`，
 * 然后 `SheetDialog(sheet, content)`，调 [menu] / [input] / [confirm]。
 * 两个面板（书签、压缩包）共用同一套 id 与同一份布局。
 */
class SheetDialog(private val sheet: BottomSheetDialog, content: View) {

    // 遮罩层的容器类型**不能写死**：它原来是 FrameLayout，为了给卡片限宽居中改成了
    // ConstraintLayout。早期这里写的是 findViewById<FrameLayout>，改布局后强转失败
    // 直接 ClassCastException —— 书签 / 压缩包面板一打开就闪退。
    // 这里只用到 View 的 API（背景、点击、可见性、bringToFront），按 View 取最稳。
    private val layer = content.findViewById<View>(R.id.sheetDialogLayer)
        ?: error("面板布局缺少 sheetDialogLayer，见 sheet_dialog_layer.xml")
    private val card = content.findViewById<LinearLayout>(R.id.sheetDialogCard)
    private val titleView = content.findViewById<TextView>(R.id.sheetDialogTitle)
    private val textView = content.findViewById<TextView>(R.id.sheetDialogText)
    private val inputView = content.findViewById<EditText>(R.id.sheetDialogInput)
    private val actionsBox = content.findViewById<LinearLayout>(R.id.sheetDialogActions)
    private val buttonsBox = content.findViewById<LinearLayout>(R.id.sheetDialogButtons)
    private val okView = content.findViewById<TextView>(R.id.sheetDialogOk)
    private val cancelView = content.findViewById<TextView>(R.id.sheetDialogCancel)

    private var onOk: ((String) -> Unit)? = null
    private var onCancel: (() -> Unit)? = null

    /**
     * 弹卡片时把面板撑高用的，收起时还原。
     *
     * 哨兵用 [Int.MIN_VALUE] 而不是 `-1`：`-1` 是 `MATCH_PARENT`、`-2` 是
     * `WRAP_CONTENT` —— 初值取 `-1` 会和「面板本来就撑满」撞上，
     * 结果收起时还原不回去，面板一直停在撑高的状态。
     */
    private var heightBefore = Int.MIN_VALUE

    /**
     * 这张面板的**本体是否不承载内容**（内容全在卡片里），见 [cardIsOnlyContent]。
     *
     * 默认 `false`：书签 / 压缩包面板的本体是列表，收起卡片后要回到列表。
     */
    private var emptyPanel = false

    val isShowing: Boolean get() = layer.visibility == View.VISIBLE

    init {
        // 遮罩与卡片的底色都在这里给：不走 XML 的主题属性，省得某个 ROM 上
        // 解析不出属性值直接把面板 inflate 崩掉
        layer.setBackgroundColor(SCRIM)
        layer.isClickable = true
        layer.setOnClickListener { cancel() }
        // **卡片要把点击吃掉**：卡片是遮罩的子视图，它自己没有可点击的子控件时，
        // 事件会冒泡到遮罩上 —— 于是「点在卡片空白处」会被当成「点了遮罩」，
        // 直接取消。密码框上尤其致命：手指落在标题或内边距上就把输入框关了。
        card.isClickable = true
        card.setOnClickListener { /* 吃掉，不做事 */ }
        card.background = cardBackground(card.context)
        card.elevation = 8f * card.resources.displayMetrics.density
        okView.setOnClickListener {
            val action = onOk
            val raw = inputView.text.toString()
            collapse()
            action?.invoke(raw)
        }
        cancelView.setOnClickListener { cancel() }
    }

    // ------------------------------------------------------------------ 三种形态

    /** 动作菜单（重命名 / 复制路径 / 删除…） */
    fun menu(title: String, message: String?, actions: List<String>, onPick: (Int) -> Unit) {
        prepare(title, message)
        actionsBox.visibility = View.VISIBLE
        actions.forEachIndexed { index, label ->
            actionsBox.addView(actionRow(label) {
                collapse()
                onPick(index)
            })
        }
        reveal()
    }

    /** 要一段输入（改书名签 / 输密码） */
    fun input(
        title: String,
        message: String?,
        value: String,
        hint: String,
        okText: String,
        password: Boolean,
        onOk: (String) -> Unit,
        onCancel: () -> Unit
    ) {
        prepare(title, message)
        this.onOk = onOk
        this.onCancel = onCancel

        inputView.visibility = View.VISIBLE
        inputView.hint = hint
        inputView.setText(value)
        inputView.setSelection(inputView.text.length)
        inputView.inputType = if (password) {
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        } else {
            InputType.TYPE_CLASS_TEXT
        }
        buttonsBox.visibility = View.VISIBLE
        okView.text = okText
        cancelView.text = "取消"
        reveal()

        inputView.requestFocus()
        inputView.post {
            val imm = inputView.context
                .getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(inputView, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    /** 只要一个是/否 */
    fun confirm(
        title: String,
        message: String?,
        okText: String,
        onOk: () -> Unit,
        onCancel: () -> Unit
    ) {
        prepare(title, message)
        this.onOk = { onOk() }
        this.onCancel = onCancel
        buttonsBox.visibility = View.VISIBLE
        okView.text = okText
        cancelView.text = "取消"
        reveal()
    }

    /** 收起，不触发任何回调 */
    fun dismiss() = collapse()

    /**
     * 声明「**这张面板的本体不承载内容**，内容全在卡片里」（顶栏 ⋯ 菜单就是这种）。
     *
     * <h2>不声明会怎样</h2>
     *
     * 这类面板收起卡片后，整张面板就是**空的** —— 而面板已经被 [expandFullScreen] 撑满全屏，
     * 屏幕上只剩对话框自己那层 dim（一层灰）。此时：
     *
     * - 点遮罩 → 卡片消失、灰还在（用户的原话：「点击了其他地方，那块半透明的区域还是没有消失」）；
     * - 再点一次 → 事件穿透到容器的 touch-outside，面板这才被关掉，「再点一次才会消失」。
     *
     * 两个 BottomSheetDialog 的默认交互（点面板外部关闭）在**全屏面板上本来就不存在**
     * —— 全屏之后没有「外部」可点。所以这类面板必须自己把面板关掉。
     *
     * <h2>不要给本体有内容的面板调这个</h2>
     *
     * 书签 / 压缩包面板的本体是列表，收起卡片后应该**回到列表继续操作**，
     * 连面板一起关掉会把用户刚打开的东西一起收走。
     *
     * @return `this`，方便在创建处链式书写。
     */
    fun cardIsOnlyContent(): SheetDialog {
        emptyPanel = true
        return this
    }

    // ------------------------------------------------------------------ 内部

    private fun prepare(title: String?, message: String?) {
        actionsBox.removeAllViews()
        actionsBox.visibility = View.GONE
        buttonsBox.visibility = View.GONE
        inputView.visibility = View.GONE
        inputView.setText("")
        titleView.text = title.orEmpty()
        titleView.visibility = if (title.isNullOrEmpty()) View.GONE else View.VISIBLE
        textView.text = message.orEmpty()
        textView.visibility = if (message.isNullOrEmpty()) View.GONE else View.VISIBLE
        onOk = null
        onCancel = null
    }

    private fun reveal() {
        // 折叠时 restoreHeight() 会还原高度；二次打开必须重新撑满，不能只靠 onShow。
        applyFullScreen()
        layer.visibility = View.VISIBLE
        layer.bringToFront()
    }

    private fun collapse() {
        layer.visibility = View.GONE
        actionsBox.removeAllViews()
        okView.text = "确定"
        hideKeyboard()
        restoreHeight()
        onOk = null
        onCancel = null
        // 本体不承载内容的面板（⋯ 菜单），收起卡片后整张面板就是空的 —— 必须连面板一起关，
        // 否则屏幕上只剩 dim 那层灰，看起来像「点了没反应」（见 cardIsOnlyContent）。
        if (emptyPanel) sheet.dismiss()
    }

    private fun cancel() {
        val callback = onCancel
        collapse()
        callback?.invoke()
    }

    private fun actionRow(label: String, onTap: () -> Unit): TextView {
        val ctx = actionsBox.context
        return TextView(ctx).apply {
            text = label
            textSize = 15f
            gravity = Gravity.CENTER_VERTICAL
            minHeight = dp(ctx, 48f)
            setPadding(dp(ctx, 4f), 0, dp(ctx, 4f), 0)
            selectableBackground(ctx)?.let { background = it }
            isClickable = true
            isFocusable = true
            setOnClickListener { onTap() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
    }

    /**
     * 在 `sheet.show()` **之前**调用：注册「show 之后把面板撑到全屏 + 容器背景透明」。
     *
     * <h2>为什么必须等 show 之后</h2>
     *
     * `design_bottom_sheet` 的高度与背景由 `BottomSheetBehavior` 在 show 的过程中
     * 重新生成一次 —— show 之前改的会被它盖掉（[reveal] 里那段注释是同一个原因）。
     *
     * <h2>为什么不能和 `glassSurface()` 同时用</h2>
     *
     * 两者都往 `setOnShowListener` 里写，而它是**替换语义、没有追加** ——
     * 后注册的把先注册的整个覆盖掉。3.15.7 就是这么翻车的：`SheetDialog` 先把容器设成透明，
     * 紧接着 `glassSurface()` 又把它设回玻璃，于是撑到全屏的**玻璃**把整屏涂白 / 涂暗，
     * 遮罩再盖一层 —— 成了**一整块死灰色**，底下的列表完全看不见（真机截图里一眼能看出来）。
     * **用 `SheetDialog` 的面板就不要再调 `glassSurface()`。**
     *
     * <h2>为什么是全屏，而不是原来的 72%</h2>
     *
     * 遮罩（[layer]）画在**面板内部**：面板有多高，遮罩就只能盖多高。
     * 撑 72% 的话上面 28% 的列表还是亮的，看着不像模态，像「下半屏弹了张卡」。
     */
    fun expandFullScreen() {
        sheet.setOnShowListener { applyFullScreen() }
    }

    /**
     * 撑到全屏 + 容器透明。**这两件事必须同时做**：
     *
     * 容器背景是 [R.drawable.bg_glass_sheet]（几乎不透明的玻璃）。只撑高、不改背景的话，
     * 整屏会先被涂白、遮罩再盖一层 —— 变成一块死灰色，**底下的内容全看不见**，
     * 比原来的 72% 还糟。透明之后才是标准模态：内容只是变暗，卡片浮在上面。
     */
    private fun applyFullScreen() {
        val bottomSheet = findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            ?: return
        if (heightBefore == Int.MIN_VALUE) {
            heightBefore = bottomSheet.layoutParams.height
        }
        bottomSheet.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        // 撑高的同时把宽度按当前窗口约束：手机仍全宽，平板居中限宽。
        sheet.adaptiveWidth()
        val wanted = ViewGroup.LayoutParams.MATCH_PARENT
        if (bottomSheet.layoutParams.height != wanted) {
            bottomSheet.layoutParams = bottomSheet.layoutParams.apply { height = wanted }
        }
        runCatching { BottomSheetBehavior.from(bottomSheet).state = BottomSheetBehavior.STATE_EXPANDED }
    }

    private fun restoreHeight() {
        if (heightBefore == Int.MIN_VALUE) return
        val bottomSheet = findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
        if (bottomSheet != null && bottomSheet.layoutParams.height != heightBefore) {
            bottomSheet.layoutParams = bottomSheet.layoutParams.apply { height = heightBefore }
        }
        heightBefore = Int.MIN_VALUE
    }

    private fun <T : View> findViewById(id: Int): T? = sheet.findViewById(id)

    private fun hideKeyboard() {
        val imm = card.context
            .getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        imm.hideSoftInputFromWindow(card.windowToken, 0)
    }

    private companion object {

        /** 遮罩：两种主题下都能用 */
        const val SCRIM = 0x99000000.toInt()

        fun dp(context: Context, value: Float): Int =
            (value * context.resources.displayMetrics.density).toInt()

        /**
         * 卡片底色：先按主题取 colorSurface，取不到再退回一个深色。
         *
         * 用代码取而不是写在 drawable 里，是为了不把「主题属性解析失败」变成
         * 面板 inflate 失败 —— 那种崩法整个面板都打不开。
         */
        fun cardBackground(context: Context): Drawable {
            val drawable = GradientDrawable()
            drawable.cornerRadius = 20f * context.resources.displayMetrics.density
            drawable.setStroke(dp(context, 1f), strokeColor(context))
            drawable.setColor(cardColor(context))
            return drawable
        }

        /**
         * 卡片表面色：取 [R.color.card_surface]（比页面背景**亮一档**），**不是** `colorSurface`。
         *
         * 原来取的是 colorSurface —— 深色模式下它等于 app_bg_top，是整屏最暗的一档；
         * 卡片浮在「被遮罩压暗的列表」之上时两者几乎同色，**看不出卡片的边界**
         * （真机截图确认过：深色下面板像是一块和背景糊在一起的暗块）。
         *
         * 取不到就退回 colorSurface，与 [surfaceColor] 同一套兜底思路。
         */
        fun cardColor(context: Context): Int = runCatching {
            ContextCompat.getColor(context, R.color.card_surface)
        }.getOrElse { surfaceColor(context) }

        /**
         * 卡片描边色：取新色板里那支「玻璃描边」（浅色是淡黑、深色是淡白），
         * 取不到退回原来的中性灰 —— 与 [surfaceColor] 同一套兜底思路。
         *
         * 卡片本身**不能用半透明**：它浮在面板的遮罩（60% 黑）之上，
         * 一旦透光，透出来的就是那层黑，卡片会发脏。
         */
        fun strokeColor(context: Context): Int = runCatching {
            ContextCompat.getColor(context, R.color.glass_stroke)
        }.getOrDefault(0x33808080)

        fun surfaceColor(context: Context): Int {
            val value = TypedValue()
            val found = context.theme.resolveAttribute(
                com.google.android.material.R.attr.colorSurface, value, true
            )
            if (!found) return FALLBACK_SURFACE
            return when {
                value.type in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT -> value.data
                value.resourceId != 0 -> runCatching {
                    ContextCompat.getColor(context, value.resourceId)
                }.getOrDefault(FALLBACK_SURFACE)
                else -> FALLBACK_SURFACE
            }
        }

        const val FALLBACK_SURFACE = 0xFF22262B.toInt()

        /** `?selectableItemBackground` 只能这样拿：它是主题属性，不是资源 id */
        fun selectableBackground(context: Context): Drawable? {
            val value = TypedValue()
            val found = context.theme
                .resolveAttribute(android.R.attr.selectableItemBackground, value, true)
            if (!found || value.resourceId == 0) return null
            return runCatching { ContextCompat.getDrawable(context, value.resourceId) }.getOrNull()
        }
    }
}
