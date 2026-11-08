package com.manga.translate.settings

import android.content.Context
import androidx.annotation.StringRes
import com.manga.translate.R

/**
 * Which engine turns recognized bubble text into the target language.
 *
 * - [AI_API]: the configured AI provider (default, original behavior).
 * - [GOOGLE_OFFLINE]: Google ML Kit on-device translation. Free, no key; downloads a
 *   language pack once, then works offline.
 * - [GOOGLE_CLOUD]: Google Cloud Translation API (Basic v2) with the user's API key.
 *
 * Google engines only replace text translation. Vision (VL) translation, AI OCR and
 * glossary/synopsis extraction still need an AI provider.
 */
enum class TranslationEngine(
    val prefValue: String,
    @param:StringRes val labelRes: Int
) {
    AI_API("ai_api", R.string.translation_engine_ai_api),
    GOOGLE_OFFLINE("google_offline", R.string.translation_engine_google_offline),
    GOOGLE_CLOUD("google_cloud", R.string.translation_engine_google_cloud);

    val usesGoogle: Boolean get() = this != AI_API

    companion object {
        fun fromPref(value: String?): TranslationEngine {
            return entries.firstOrNull { it.prefValue == value } ?: AI_API
        }
    }
}

class TranslationEngineStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun loadEngine(): TranslationEngine =
        TranslationEngine.fromPref(prefs.getString(KEY_ENGINE, null))

    fun saveEngine(engine: TranslationEngine) {
        prefs.edit().putString(KEY_ENGINE, engine.prefValue).apply()
    }

    fun loadCloudApiKey(): String = prefs.getString(KEY_CLOUD_API_KEY, null).orEmpty().trim()

    fun saveCloudApiKey(key: String) {
        prefs.edit().putString(KEY_CLOUD_API_KEY, key.trim()).apply()
    }

    /** True when the selected Google engine has everything it needs to run. */
    fun isGoogleEngineReady(): Boolean {
        return when (loadEngine()) {
            TranslationEngine.AI_API -> false
            TranslationEngine.GOOGLE_OFFLINE -> true
            TranslationEngine.GOOGLE_CLOUD -> loadCloudApiKey().isNotEmpty()
        }
    }

    private companion object {
        const val PREFS_NAME = "translation_engine_prefs"
        const val KEY_ENGINE = "engine"
        const val KEY_CLOUD_API_KEY = "google_cloud_api_key"
    }
}
