package com.manga.translate.settings.ui.dialogs

import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.manga.translate.R
import com.manga.translate.databinding.DialogTranslationEngineBinding
import com.manga.translate.platform.AppLogger
import com.manga.translate.settings.TranslationEngine
import com.manga.translate.settings.TranslationEngineStore
import com.manga.translate.settings.ui.SettingsFragment

/**
 * Chooses the text translation engine: AI provider, Google ML Kit offline, or Google Cloud
 * Translation with an API key.
 */
internal class TranslationEngineDialog(
    private val fragment: SettingsFragment,
    private val engineStore: TranslationEngineStore
) {
    fun show() {
        val binding = DialogTranslationEngineBinding.inflate(fragment.layoutInflater)
        val radioByEngine = mapOf(
            TranslationEngine.AI_API to binding.translationEngineAiApi,
            TranslationEngine.GOOGLE_OFFLINE to binding.translationEngineGoogleOffline,
            TranslationEngine.GOOGLE_CLOUD to binding.translationEngineGoogleCloud
        )
        val current = engineStore.loadEngine()
        radioByEngine.getValue(current).isChecked = true
        binding.googleCloudApiKeyInput.setText(engineStore.loadCloudApiKey())

        fun selectedEngine(): TranslationEngine {
            val checkedId = binding.translationEngineGroup.checkedRadioButtonId
            return radioByEngine.entries.firstOrNull { it.value.id == checkedId }?.key ?: current
        }

        fun refresh() {
            val engine = selectedEngine()
            binding.translationEngineDescription.setText(
                when (engine) {
                    TranslationEngine.AI_API -> R.string.translation_engine_ai_api_description
                    TranslationEngine.GOOGLE_OFFLINE -> R.string.translation_engine_google_offline_description
                    TranslationEngine.GOOGLE_CLOUD -> R.string.translation_engine_google_cloud_description
                }
            )
            binding.googleCloudApiKeyLayout.visibility =
                if (engine == TranslationEngine.GOOGLE_CLOUD) View.VISIBLE else View.GONE
        }

        binding.translationEngineGroup.setOnCheckedChangeListener { _, _ -> refresh() }
        refresh()

        AlertDialog.Builder(fragment.requireContext())
            .setTitle(R.string.translation_engine_title)
            .setView(binding.root)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val engine = selectedEngine()
                val key = binding.googleCloudApiKeyInput.text?.toString()?.trim().orEmpty()
                engineStore.saveCloudApiKey(key)
                engineStore.saveEngine(engine)
                fragment.updateTranslationEngineButton(engine)
                if (engine == TranslationEngine.GOOGLE_CLOUD && key.isEmpty()) {
                    Toast.makeText(
                        fragment.requireContext(),
                        R.string.google_cloud_api_key_missing,
                        Toast.LENGTH_LONG
                    ).show()
                }
                AppLogger.log("Settings", "Translation engine set to ${engine.prefValue}")
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
