package com.rxinfiles.tool

import android.app.Activity
import android.content.DialogInterface
import android.graphics.Color
import android.os.SystemClock
import android.util.TypedValue
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import java.io.File

/**
 * 长任务进度对话框。
 *
 * 三个刻意的设计：
 *  1. **不确定进度条**（转圈）而不是百分比 —— 复制一棵目录树之前没人知道总字节数，
 *     与其先扫一遍（在几十万文件的 SD 卡上这一步本身就很久）不如老实说「在处理」，
 *     真正有价值的信息是「当前在处理哪个文件」。
 *  2. **更新节流**：每个文件都往 UI 线程发一帧会让复制本身变慢，
 *     这里限制到约 12fps，界面依然跟得上。
 *  3. **取消是「请求」不是「立刻」**：回调返回后由任务在下一个检查点自行停止，
 *     所以在取消后对话框仍然可见，直到任务真正结束 —— 否则用户会以为卡死了。
 */
class OperationProgressDialog(
    private val activity: Activity,
    title: String
) {

    private val messageView = TextView(activity).apply {
        setPadding(0, dp(4), 0, dp(0))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        maxLines = 3
        setTextColor(Color.GRAY)
    }

    private val bar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
        isIndeterminate = true
    }

    /** 用户点了取消 */
    var onCancel: (() -> Unit)? = null

    private val dialog: AlertDialog = AlertDialog.Builder(activity)
        .setTitle(title)
        .setView(LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(bar, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
            addView(messageView)
        })
        // 点外部不关闭：避免任务还在跑、对话框却没了，用户不知道发生了什么
        .setCancelable(false)
        .setNegativeButton("取消") { _: DialogInterface, _ -> onCancel?.invoke() }
        .create()

    private var lastPaint = 0L
    private var pending: String? = null

    fun show() {
        if (!dialog.isShowing && !activity.isFinishing) {
            dialog.show()
        }
    }

    /**
     * 报告当前处理的条目。可从任意线程调用。
     */
    fun report(path: String) {
        val text = shorten(path)
        val now = SystemClock.uptimeMillis()
        if (now - lastPaint < PAINT_INTERVAL_MS) {
            pending = text
            return
        }
        lastPaint = now
        pending = null
        activity.runOnUiThread { if (dialog.isShowing) messageView.text = text }
    }

    /** 用户已经点了取消：把按钮换掉，避免重复触发 */
    fun markCancelling() {
        activity.runOnUiThread {
            if (dialog.isShowing) {
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.apply {
                    isEnabled = false
                    text = "取消中…"
                }
                messageView.text = "正在停止…"
            }
        }
    }

    fun dismiss() {
        activity.runOnUiThread {
            pending?.let { messageView.text = it }
            if (dialog.isShowing) {
                dialog.dismiss()
            }
        }
    }

    private fun shorten(path: String): String {
        val name = File(path).name.ifEmpty { path }
        val parent = File(path).parent ?: ""
        return if (parent.isEmpty()) name else "$parent/\n$name"
    }

    private fun dp(value: Int): Int =
        (value * activity.resources.displayMetrics.density).toInt()

    private companion object {
        const val PAINT_INTERVAL_MS = 80L
    }
}
