package com.rxinfiles.tool

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.method.ScrollingMovementMethod
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog

/**
 * 管道执行的日志窗口。
 *
 * 三个刻意的设计：
 *
 * 1. **先建窗口再跑脚本。** `ScriptPipe.run` 是异步的，探测解释器那一下要几百毫秒，
 *    先把窗口摆出来，用户至少知道「点了有反应」。
 * 2. **日志节流到约 10fps。** 一个 `for i in $(seq 100000)` 的脚本每行都刷一次
 *    UI 会直接把主线程压满，反而看不出内容。攒够一帧再落。
 * 3. **关闭窗口 ≠ 终止进程。** 点「后台运行」只是把界面收起来，脚本继续跑；
 *    真要停得点「终止」。反过来，**用户在跑完之前关掉窗口不会留下孤儿进程**——
 *    进程的存活与对话框无关，`ScriptPipe.Handle` 由 Activity 持有。
 */
class ScriptLogDialog(
    private val activity: Activity,
    title: String
) {

    private val statusView = TextView(activity).apply {
        setPadding(0, 0, 0, dp(6))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setTextColor(Color.GRAY)
        text = "正在探测解释器…"
    }

    private val logView = TextView(activity).apply {
        setPadding(dp(8), dp(6), dp(8), dp(6))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        typeface = Typeface.MONOSPACE
        setTextIsSelectable(true)
        movementMethod = ScrollingMovementMethod()
    }

    private val scroll = ScrollView(activity).apply {
        addView(
            logView,
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    private val dialog: AlertDialog = AlertDialog.Builder(activity)
        .setTitle(title)
        .setView(LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(6), dp(20), 0)
            addView(statusView)
            addView(
                scroll,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    logHeight()
                )
            )
        })
        .setCancelable(true)
        .setPositiveButton("后台运行", null)
        .setNegativeButton("终止", null)
        .create()

    /** 用户请求终止 */
    var onCancel: (() -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private val pending = StringBuilder()

    @Volatile
    private var finished = false

    private val flush = Runnable { flushNow() }

    fun show() {
        if (activity.isFinishing) return
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setOnClickListener {
                onCancel?.invoke()
                markCancelling()
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
                // 只是把窗口收起来，脚本继续跑
                dialog.dismiss()
            }
            scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
        }
        if (!dialog.isShowing) dialog.show()
    }

    /** 追加一行。可从任意线程调用。 */
    fun append(text: String, isError: Boolean) {
        if (finished) return
        val line = if (isError) "[err] $text" else text
        synchronized(pending) { pending.append(line).append('\n') }
        main.removeCallbacks(flush)
        main.postDelayed(flush, PAINT_INTERVAL_MS)
    }

    private fun flushNow() {
        val chunk = synchronized(pending) {
            val s = pending.toString()
            pending.setLength(0)
            s
        }
        if (chunk.isEmpty()) return
        // 窗口收起来时也照常累积 —— 用户点回来看时内容还在；结果摘要里另有退出码兜底
        logView.append(chunk)
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun markCancelling() {
        activity.runOnUiThread {
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.apply {
                isEnabled = false
                text = "终止中…"
            }
            statusView.text = "正在终止…"
        }
    }

    /** 结束：写摘要、换掉按钮 */
    fun finish(result: ScriptPipe.Result) {
        finished = true
        flushNow()
        activity.runOnUiThread {
            statusView.text = result.summary()
            statusView.setTextColor(if (result.ok) OK_COLOR else ERR_COLOR)

            result.error?.let { logView.append("\n$it\n") }
            val tail = buildString {
                if (result.stdout.isEmpty() && result.stderr.isEmpty()) append("\n（没有任何输出）\n")
                if (result.truncated) append("\n（日志过长，已截断显示）\n")
                if (result.signal != null || result.timedOut || result.cancelled) {
                    append("\n（脚本可能没有执行完）\n")
                }
            }
            if (tail.isNotEmpty() && dialog.isShowing) logView.append(tail)
            if (dialog.isShowing) scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }

            dialog.setCancelable(true)
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.visibility =
                android.view.View.GONE
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.apply {
                isEnabled = true
                text = "关闭"
                setOnClickListener { dialog.dismiss() }
            }
        }
    }

    fun dismiss() {
        main.removeCallbacks(flush)
        if (dialog.isShowing) dialog.dismiss()
    }

    private fun dp(value: Int): Int =
        (value * activity.resources.displayMetrics.density).toInt()

    /** 日志区高度按窗口高度取比例并限幅，横屏/分屏不再用固定 260dp。 */
    private fun logHeight(): Int {
        val percent = activity.resources.getInteger(R.integer.script_log_height_percent)
        val fullHeight = activity.resources.displayMetrics.heightPixels
        return (fullHeight * percent / 100f).toInt().coerceIn(dp(120), dp(260))
    }

    private companion object {
        const val PAINT_INTERVAL_MS = 100L
        val OK_COLOR = Color.parseColor("#2E7D32")
        val ERR_COLOR = Color.parseColor("#C62828")
    }
}
