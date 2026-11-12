package com.rxinfiles.tool

import android.content.Context
import org.json.JSONArray

/**
 * 文件剪贴板（复制/剪切 → 粘贴）。
 *
 * 系统的 ClipboardManager 只能装文本，装不下一组文件路径，
 * 因此这里自己用 SharedPreferences 存一份「待粘贴清单」：
 * 关掉应用再回来，之前复制的文件还在，粘贴按钮上的计数也还能对上。
 *
 * 只存路径不存内容 —— 复制是「粘贴时才真正读写文件」，
 * 这与桌面文件管理器的直觉一致，也不会因为复制大文件而卡顿。
 */
object FileClipboard {

    private const val PREFS = "rouxin_clipboard"
    private const val KEY_MODE = "mode"
    private const val KEY_PATHS = "paths"

    enum class Mode {
        COPY, MOVE;

        val label: String get() = if (this == COPY) "复制" else "剪切"
    }

    data class Snapshot(val mode: Mode, val paths: List<String>) {
        val size: Int get() = paths.size

        /** 供 UI 直接展示的动词，例如「待剪切 3 项」 */
        val label: String get() = mode.label
    }

    fun put(context: Context, mode: Mode, paths: List<String>) {
        if (paths.isEmpty()) {
            clear(context)
            return
        }
        val array = JSONArray()
        paths.forEach { array.put(it) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_MODE, mode.name)
            .putString(KEY_PATHS, array.toString())
            .apply()
    }

    fun peek(context: Context): Snapshot? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_PATHS, null) ?: return null
        val mode = runCatching { Mode.valueOf(prefs.getString(KEY_MODE, null) ?: "") }
            .getOrDefault(Mode.COPY)
        val paths = try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { array.optString(it, null)?.takeIf { it.isNotEmpty() } }
        } catch (t: Throwable) {
            emptyList()
        }
        if (paths.isEmpty()) return null
        return Snapshot(mode, paths)
    }

    fun count(context: Context): Int = peek(context)?.size ?: 0

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
