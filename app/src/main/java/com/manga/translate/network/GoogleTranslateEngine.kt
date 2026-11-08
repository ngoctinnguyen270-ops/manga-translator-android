package com.manga.translate.network

import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.manga.translate.model.TranslationLanguage
import com.manga.translate.platform.AppLogger
import com.manga.translate.platform.PromptAssetResolver
import com.manga.translate.settings.TranslationEngine
import com.manga.translate.settings.TranslationEngineStore
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Source language of the bubbles being translated, carried through the coroutine context so
 * Google engines know which language pack to use without widening [LlmGateway] signatures.
 * Callers that do not set it (for example screen translation) fall back to auto-detection
 * (Cloud) or English (ML Kit).
 */
internal class TranslationSourceLanguage(
    val language: TranslationLanguage
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<TranslationSourceLanguage>
}

/**
 * Text translation through Google instead of an AI provider.
 *
 * Returns results in the same shape as AI translation (every requested id exactly once,
 * no glossary additions), so the rest of the pipeline, storage and rendering stay unchanged.
 */
internal class GoogleTranslateEngine(
    context: Context,
    private val engineStore: TranslationEngineStore = TranslationEngineStore(context)
) {
    private val appContext = context.applicationContext
    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(90, TimeUnit.SECONDS)
            .build()
    }
    private val translators = ConcurrentHashMap<String, Translator>()
    private val modelReady = ConcurrentHashMap.newKeySet<String>()
    private val downloadMutex = Mutex()

    fun isActive(): Boolean = engineStore.loadEngine().usesGoogle

    fun isReady(): Boolean = engineStore.isGoogleEngineReady()

    suspend fun translateItems(
        items: List<LlmBubbleTranslationRequestItem>,
        glossary: Map<String, String>
    ): LlmBubbleTranslationResult = withContext(Dispatchers.IO) {
        if (items.isEmpty()) return@withContext LlmBubbleTranslationResult(emptyList(), emptyMap())
        val source = currentCoroutineContext()[TranslationSourceLanguage]?.language
        val target = PromptAssetResolver.translationTargetKey(appContext)
        val prepared = items.map { prepareSourceText(it.text, source) }
        val translated = when (engineStore.loadEngine()) {
            TranslationEngine.GOOGLE_CLOUD -> translateWithCloud(prepared, source, target)
            TranslationEngine.GOOGLE_OFFLINE -> translateWithMlKit(prepared, source, target)
            TranslationEngine.AI_API -> error("Google engine is not selected")
        }
        val results = items.mapIndexed { index, item ->
            val text = translated.getOrNull(index).orEmpty()
            LlmBubbleTranslationItem(item.id, applyGlossaryNames(text, glossary))
        }
        AppLogger.log(TAG, "Google translated ${results.size} bubbles (source=${source?.prefValue ?: "auto"}, target=$target)")
        LlmBubbleTranslationResult(results, emptyMap())
    }

    // ---------------------------------------------------------------- Cloud Translation v2

    private suspend fun translateWithCloud(
        texts: List<String>,
        source: TranslationLanguage?,
        targetKey: String
    ): List<String> {
        val apiKey = engineStore.loadCloudApiKey()
        if (apiKey.isEmpty()) {
            throw LlmResponseException(LlmErrorCode.MissingTranslateApiSettings, "Google Cloud API key is empty", retryable = false)
        }
        val output = MutableList(texts.size) { "" }
        val pending = texts.indices.filter { texts[it].isNotBlank() }
        for (chunk in chunkForCloud(pending, texts)) {
            currentCoroutineContext().ensureActive()
            val body = JSONObject().apply {
                put("q", JSONArray().apply { chunk.forEach { put(texts[it]) } })
                put("target", cloudTargetCode(targetKey))
                put("format", "text")
                cloudSourceCode(source)?.let { put("source", it) }
            }
            val request = Request.Builder()
                .url("$CLOUD_ENDPOINT?key=$apiKey")
                .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()
            val responseText = try {
                httpClient.newCall(request).execute().use { response ->
                    val raw = response.body.string()
                    if (!response.isSuccessful) {
                        throw LlmResponseException(
                            LlmErrorCode.Http(response.code),
                            extractCloudError(raw),
                            retryable = response.code == 429 || response.code >= 500
                        )
                    }
                    raw
                }
            } catch (e: IOException) {
                throw LlmResponseException(LlmErrorCode.NetworkError, e.message.orEmpty(), e)
            }
            val translations = runCatching {
                JSONObject(responseText).getJSONObject("data").getJSONArray("translations")
            }.getOrElse {
                throw LlmResponseException(LlmErrorCode.InvalidResponse, responseText.take(600), it)
            }
            if (translations.length() != chunk.size) {
                throw LlmResponseException(LlmErrorCode.MissingTranslationItems, responseText.take(600))
            }
            chunk.forEachIndexed { i, index ->
                output[index] = translations.getJSONObject(i).optString("translatedText").trim()
            }
        }
        return output
    }

    /** v2 accepts at most 128 segments per call; also keep each request comfortably small. */
    private fun chunkForCloud(indices: List<Int>, texts: List<String>): List<List<Int>> {
        val chunks = mutableListOf<List<Int>>()
        var current = mutableListOf<Int>()
        var chars = 0
        for (index in indices) {
            val length = texts[index].length
            if (current.isNotEmpty() && (current.size >= CLOUD_MAX_SEGMENTS || chars + length > CLOUD_MAX_CHARS)) {
                chunks += current
                current = mutableListOf()
                chars = 0
            }
            current += index
            chars += length
        }
        if (current.isNotEmpty()) chunks += current
        return chunks
    }

    private fun extractCloudError(raw: String): String {
        return runCatching { JSONObject(raw).getJSONObject("error").optString("message") }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: raw.take(600)
    }

    private fun cloudTargetCode(targetKey: String): String = when (targetKey) {
        "zh_hans" -> "zh-CN"
        "zh_hant" -> "zh-TW"
        "pt_br" -> "pt"
        else -> targetKey
    }

    private fun cloudSourceCode(source: TranslationLanguage?): String? = when (source) {
        TranslationLanguage.JA_TO_ZH -> "ja"
        TranslationLanguage.EN_TO_ZH -> "en"
        TranslationLanguage.KO_TO_ZH -> "ko"
        TranslationLanguage.ZH_HANS_TO_TARGET -> "zh-CN"
        TranslationLanguage.ZH_HANT_TO_TARGET -> "zh-TW"
        TranslationLanguage.FR_TO_ZH -> "fr"
        TranslationLanguage.ES_TO_ZH -> "es"
        TranslationLanguage.PT_TO_ZH -> "pt"
        TranslationLanguage.DE_TO_ZH -> "de"
        TranslationLanguage.IT_TO_ZH -> "it"
        TranslationLanguage.RU_TO_ZH -> "ru"
        TranslationLanguage.CHN_ENG_TO_ZH, null -> null
    }

    // ---------------------------------------------------------------- ML Kit (offline)

    private suspend fun translateWithMlKit(
        texts: List<String>,
        source: TranslationLanguage?,
        targetKey: String
    ): List<String> {
        val sourceCode = mlKitSourceCode(source)
        val targetCode = mlKitTargetCode(targetKey)
        if (sourceCode == targetCode) return texts
        val translator = obtainReadyTranslator(sourceCode, targetCode)
        return texts.map { text ->
            currentCoroutineContext().ensureActive()
            if (text.isBlank()) "" else translator.translate(text).await().trim()
        }
    }

    private suspend fun obtainReadyTranslator(sourceCode: String, targetCode: String): Translator {
        val pairKey = "$sourceCode>$targetCode"
        val translator = translators.getOrPut(pairKey) {
            Translation.getClient(
                TranslatorOptions.Builder()
                    .setSourceLanguage(sourceCode)
                    .setTargetLanguage(targetCode)
                    .build()
            )
        }
        if (pairKey !in modelReady) {
            downloadMutex.withLock {
                if (pairKey !in modelReady) {
                    AppLogger.log(TAG, "Downloading ML Kit language pack $pairKey if needed")
                    try {
                        translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
                    } catch (e: Exception) {
                        throw LlmResponseException(
                            LlmErrorCode.NetworkError,
                            "ML Kit language pack download failed: ${e.message.orEmpty()}",
                            e
                        )
                    }
                    modelReady += pairKey
                }
            }
        }
        return translator
    }

    private fun mlKitTargetCode(targetKey: String): String {
        val tag = when (targetKey) {
            "zh_hans", "zh_hant" -> "zh"
            "pt_br" -> "pt"
            else -> targetKey
        }
        return TranslateLanguage.fromLanguageTag(tag) ?: TranslateLanguage.ENGLISH
    }

    private fun mlKitSourceCode(source: TranslationLanguage?): String {
        val tag = when (source) {
            TranslationLanguage.JA_TO_ZH -> "ja"
            TranslationLanguage.KO_TO_ZH -> "ko"
            TranslationLanguage.ZH_HANS_TO_TARGET,
            TranslationLanguage.ZH_HANT_TO_TARGET,
            TranslationLanguage.CHN_ENG_TO_ZH -> "zh"
            TranslationLanguage.FR_TO_ZH -> "fr"
            TranslationLanguage.ES_TO_ZH -> "es"
            TranslationLanguage.PT_TO_ZH -> "pt"
            TranslationLanguage.DE_TO_ZH -> "de"
            TranslationLanguage.IT_TO_ZH -> "it"
            TranslationLanguage.RU_TO_ZH -> "ru"
            TranslationLanguage.EN_TO_ZH, null -> "en"
        }
        return TranslateLanguage.fromLanguageTag(tag) ?: TranslateLanguage.ENGLISH
    }

    // ---------------------------------------------------------------- text helpers

    /**
     * Western comics are usually lettered in ALL CAPS, which machine translation handles
     * poorly (it treats words as acronyms or names). Convert such text to sentence case first.
     */
    private fun prepareSourceText(text: String, source: TranslationLanguage?): String {
        val joined = text.replace(Regex("-\\s*\\n\\s*"), "").replace(Regex("\\s*\\n\\s*"), " ").trim()
        val latin = source == null || source.usesLatinOcr()
        return if (latin && isMostlyUppercase(joined)) toSentenceCase(joined) else joined
    }

    private fun isMostlyUppercase(text: String): Boolean {
        val letters = text.filter { it.isLetter() }
        if (letters.length < 4) return false
        return letters.count { it.isUpperCase() } >= letters.length * 0.8
    }

    private fun toSentenceCase(text: String): String {
        val lower = text.lowercase()
        val builder = StringBuilder(lower.length)
        var capitalizeNext = true
        for (ch in lower) {
            if (capitalizeNext && ch.isLetter()) {
                builder.append(ch.uppercaseChar())
                capitalizeNext = false
            } else {
                builder.append(ch)
            }
            if (ch == '.' || ch == '!' || ch == '?' || ch == '…') capitalizeNext = true
        }
        return builder.toString()
            .replace(Regex("\\bi\\b"), "I")
            .replace(Regex("\\bi'(m|ll|d|ve)\\b"), "I'$1")
    }

    /** Keep proper names from the folder glossary consistent when the source name survives untranslated. */
    private fun applyGlossaryNames(text: String, glossary: Map<String, String>): String {
        if (text.isEmpty() || glossary.isEmpty()) return text
        var result = text
        for ((original, fixed) in glossary) {
            if (original.length < 2 || fixed.isBlank() || original == fixed) continue
            val pattern = Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(original) + "(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)
            result = pattern.replace(result, Regex.escapeReplacement(fixed))
        }
        return result
    }

    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
        addOnSuccessListener { value -> if (continuation.isActive) continuation.resume(value) }
        addOnFailureListener { error -> if (continuation.isActive) continuation.resumeWithException(error) }
        addOnCanceledListener { continuation.cancel() }
    }

    private companion object {
        const val TAG = "GoogleTranslate"
        const val CLOUD_ENDPOINT = "https://translation.googleapis.com/language/translate/v2"
        const val CLOUD_MAX_SEGMENTS = 100
        const val CLOUD_MAX_CHARS = 20_000
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
