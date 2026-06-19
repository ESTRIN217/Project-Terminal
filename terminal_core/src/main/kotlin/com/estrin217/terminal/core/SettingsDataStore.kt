package com.estrin217.terminal.core

import android.content.Context
import android.content.SharedPreferences

object SettingsDataStore {
    private const val PREFS_NAME = "terminal_settings"
    private const val KEY_THEME_MODE = "theme_mode"
    private const val KEY_FONT_SIZE = "font_size"
    private const val KEY_LANGUAGE = "language"
    private const val KEY_FONT_FAMILY = "font_family"

    enum class ThemeMode { SYSTEM, LIGHT, DARK }

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    var themeMode: ThemeMode
        get() = ThemeMode.valueOf(prefs.getString(KEY_THEME_MODE, ThemeMode.DARK.name) ?: ThemeMode.DARK.name)
        set(value) = prefs.edit().putString(KEY_THEME_MODE, value.name).apply()

    var fontSize: Int
        get() = prefs.getInt(KEY_FONT_SIZE, 12)
        set(value) = prefs.edit().putInt(KEY_FONT_SIZE, value.coerceIn(8, 32)).apply()

    var language: String
        get() = prefs.getString(KEY_LANGUAGE, "") ?: ""
        set(value) = prefs.edit().putString(KEY_LANGUAGE, value).apply()

    var fontFamily: String
        get() = prefs.getString(KEY_FONT_FAMILY, "monospace") ?: "monospace"
        set(value) = prefs.edit().putString(KEY_FONT_FAMILY, value).apply()
}
