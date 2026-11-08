package com.daftech.suaraku

import android.content.Context
import android.content.SharedPreferences
import com.daftech.suaraku.engine.SupertonicTtsEngine

/** Pengaturan SuaraKu. API key Gemini disimpan di SharedPreferences privat HP ini saja. */
class Prefs(context: Context) {
    private val sp: SharedPreferences =
        context.getSharedPreferences("suaraku_prefs", Context.MODE_PRIVATE)

    var geminiKey: String
        get() = sp.getString(KEY_GEMINI, "") ?: ""
        set(v) { sp.edit().putString(KEY_GEMINI, v.trim()).apply() }

    var geminiModel: String
        get() = sp.getString(KEY_MODEL, MODEL_FLASH) ?: MODEL_FLASH
        set(v) { sp.edit().putString(KEY_MODEL, v).apply() }

    var defaultEngine: String
        get() = sp.getString(KEY_ENGINE, ENGINE_GEMINI) ?: ENGINE_GEMINI
        set(v) { sp.edit().putString(KEY_ENGINE, v).apply() }

    var directorEnabled: Boolean
        get() = sp.getBoolean(KEY_DIRECTOR, true)
        set(v) { sp.edit().putBoolean(KEY_DIRECTOR, v).apply() }

    /** Suara Supertonic yang dipilih (sid 0..9, default 3). Nilai di luar rentang dibaca sebagai default. */
    var supertonicSid: Int
        get() = sp.getInt(KEY_SUPERTONIC_SID, SupertonicTtsEngine.DEFAULT_SID)
            .takeIf { it in 0..9 } ?: SupertonicTtsEngine.DEFAULT_SID
        set(v) { sp.edit().putInt(KEY_SUPERTONIC_SID, v.coerceIn(0, 9)).apply() }

    companion object {
        private const val KEY_GEMINI = "gemini_api_key"
        private const val KEY_MODEL = "gemini_model"
        private const val KEY_ENGINE = "default_engine"
        private const val KEY_DIRECTOR = "director_enabled"
        private const val KEY_SUPERTONIC_SID = "supertonic_sid"

        const val MODEL_FLASH = "gemini-2.5-flash-preview-tts"
        const val MODEL_PRO = "gemini-2.5-pro-preview-tts"

        const val ENGINE_GEMINI = "GEMINI"
        const val ENGINE_EDGE = "EDGE"
        const val ENGINE_OFFLINE = "OFFLINE"
        const val ENGINE_SUPERTONIC = "SUPERTONIC"
    }
}
