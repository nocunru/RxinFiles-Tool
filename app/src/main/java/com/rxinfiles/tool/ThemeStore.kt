package com.rxinfiles.tool

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

object ThemeStore {
    private const val PREFS = "app_prefs"
    private const val KEY_DARK = "dark_theme"

    fun isDark(context: Context): Boolean {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.contains(KEY_DARK)) return prefs.getBoolean(KEY_DARK, false)
        val legacy = when {
            app.getSharedPreferences("shell_prefs", Context.MODE_PRIVATE).contains(KEY_DARK) ->
                app.getSharedPreferences("shell_prefs", Context.MODE_PRIVATE)
                    .getBoolean(KEY_DARK, false)
            app.getSharedPreferences("terminal_prefs", Context.MODE_PRIVATE).contains(KEY_DARK) ->
                app.getSharedPreferences("terminal_prefs", Context.MODE_PRIVATE)
                    .getBoolean(KEY_DARK, false)
            else -> false
        }
        prefs.edit().putBoolean(KEY_DARK, legacy).apply()
        return legacy
    }

    fun setDark(context: Context, dark: Boolean) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_DARK, dark).apply()
        apply(context)
    }

    fun toggle(context: Context): Boolean {
        val next = !isDark(context)
        setDark(context, next)
        return next
    }

    fun apply(context: Context) {
        AppCompatDelegate.setDefaultNightMode(
            if (isDark(context)) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        )
    }
}
