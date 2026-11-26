package com.ps5translator.pro

import android.content.Context

enum class DialectMode {
    ORIGINAL_MSA,
    IRAQI
}

class AppConfig(context: Context) {
    private val prefs = context.getSharedPreferences("ps5_translator_prefs", Context.MODE_PRIVATE)

    var apiKey: String
        get() = prefs.getString("api_key", "") ?: ""
        set(value) = prefs.edit().putString("api_key", value.trim()).apply()

    var modelName: String
        get() = prefs.getString("model_name", "gemini-2.5-flash-lite") ?: "gemini-2.5-flash-lite"
        set(value) = prefs.edit().putString("model_name", value).apply()

    var dialect: DialectMode
        get() {
            val raw = prefs.getString("dialect", DialectMode.ORIGINAL_MSA.name)
            return runCatching { DialectMode.valueOf(raw ?: "") }.getOrDefault(DialectMode.ORIGINAL_MSA)
        }
        set(value) = prefs.edit().putString("dialect", value.name).apply()

    var gamePreset: String
        get() = prefs.getString("game_preset", "GTA 6") ?: "GTA 6"
        set(value) = prefs.edit().putString("game_preset", value).apply()

    var ttsEnabled: Boolean
        get() = prefs.getBoolean("tts_enabled", true)
        set(value) = prefs.edit().putBoolean("tts_enabled", value).apply()

    var ttsSpeed: Float
        get() = prefs.getFloat("tts_speed", 1.2f)
        set(value) = prefs.edit().putFloat("tts_speed", value.coerceIn(0.8f, 1.8f)).apply()

    var scanIntervalMs: Long
        get() = prefs.getLong("scan_interval_ms", 200L)
        set(value) = prefs.edit().putLong("scan_interval_ms", value.coerceIn(150L, 600L)).apply()

    var boxLeftRatio: Float
        get() = prefs.getFloat("box_left", 0.12f)
        set(value) = prefs.edit().putFloat("box_left", value.coerceIn(0f, 0.9f)).apply()

    var boxTopRatio: Float
        get() = prefs.getFloat("box_top", 0.72f)
        set(value) = prefs.edit().putFloat("box_top", value.coerceIn(0f, 0.9f)).apply()

    var boxWidthRatio: Float
        get() = prefs.getFloat("box_width", 0.76f)
        set(value) = prefs.edit().putFloat("box_width", value.coerceIn(0.15f, 1f)).apply()

    var boxHeightRatio: Float
        get() = prefs.getFloat("box_height", 0.16f)
        set(value) = prefs.edit().putFloat("box_height", value.coerceIn(0.06f, 0.5f)).apply()
}
