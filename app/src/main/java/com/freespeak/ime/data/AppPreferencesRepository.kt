package com.freespeak.ime.data

import android.content.Context
import androidx.annotation.StringRes
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.freespeak.ime.R
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.Locale

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "freespeak_prefs")

enum class RecordInteractionMode(
    @get:StringRes val shortTitleRes: Int,
    @get:StringRes val descriptionRes: Int,
) {
    HYBRID(R.string.mode_hybrid_short, R.string.mode_hybrid_desc),
    TAP_ONLY(R.string.mode_tap_short, R.string.mode_tap_desc),
    HOLD_ONLY(R.string.mode_hold_short, R.string.mode_hold_desc),
}

enum class MirrorSource(
    @get:StringRes val titleRes: Int,
    val hfBaseUrl: String,
) {
    HF_MIRROR(R.string.mirror_hf_mirror, "https://hf-mirror.com"),
    MODELSCOPE(R.string.mirror_modelscope, "https://hf-mirror.com"),
    HUGGINGFACE(R.string.mirror_huggingface, "https://huggingface.co"),
    CUSTOM(R.string.mirror_custom, ""),
}

data class SupportedLanguage(
    val code: String,
    val nativeLabel: String,
)

object SupportedLanguages {
    val all: List<SupportedLanguage> = listOf(
        SupportedLanguage("en", "English"),
        SupportedLanguage("zh", "中文"),
        SupportedLanguage("yue", "粵語"),
        SupportedLanguage("ja", "日本語"),
        SupportedLanguage("ko", "한국어"),
        SupportedLanguage("fr", "Français"),
        SupportedLanguage("de", "Deutsch"),
        SupportedLanguage("es", "Español"),
        SupportedLanguage("ru", "Русский"),
    )

    fun labelFor(code: String): String =
        all.find { it.code == code }?.nativeLabel ?: code.uppercase()

    fun defaultPreferredForLocale(locale: Locale = Locale.getDefault()): Set<String> {
        val sysLang = when (locale.language.lowercase()) {
            "zh" -> if (locale.country.equals("HK", true)) "yue" else "zh"
            "ja" -> "ja"
            "ko" -> "ko"
            "fr" -> "fr"
            "de" -> "de"
            "es" -> "es"
            "ru" -> "ru"
            else -> "en"
        }
        return if (sysLang == "en") setOf("en") else linkedSetOf("en", sysLang)
    }
}

data class UserSettings(
    val onboardingCompleted: Boolean = false,
    val selectedModelId: String = "",
    val preferredLanguages: Set<String> = SupportedLanguages.defaultPreferredForLocale(),
    val selectedLanguage: String = "auto",
    val interactionMode: RecordInteractionMode = RecordInteractionMode.HYBRID,
    val vadFilterEnabled: Boolean = true,
    val vadThreshold: Float = 0.45f,
    val cpuThreads: Int = defaultCpuThreads(),
    val idleUnloadMinutes: Int = 5,
    val mirrorSource: MirrorSource = defaultMirrorForLocale(),
    val customMirrorUrl: String = "https://hf-mirror.com",
    val customCatalogUrls: Set<String> = emptySet(),
    val whisperStylePromptEnabled: Boolean = true,
) {
    /**
     * Ordered list of user's preferred languages following [SupportedLanguages.all] order.
     */
    val orderedPreferredLanguages: List<String>
        get() {
            val valid = SupportedLanguages.all.map { it.code }.filter { it in preferredLanguages }
            return valid.ifEmpty { listOf("en") }
        }

    /**
     * Effective ASR language code passed to inference engines.
     * - Single preferred language -> that language code directly.
     * - Multiple preferred languages -> [selectedLanguage] if still valid, otherwise "auto" (code-switching).
     */
    val effectiveAsrLanguage: String
        get() {
            val langs = orderedPreferredLanguages
            if (langs.size == 1) return langs[0]
            return if (selectedLanguage == "auto" || selectedLanguage in langs) {
                selectedLanguage
            } else {
                "auto"
            }
        }

    fun formatPreferredLanguagesSummary(): String {
        return orderedPreferredLanguages.joinToString(", ") { SupportedLanguages.labelFor(it) }
    }

    fun resolveDownloadUrl(hfUrl: String, modelScopeUrl: String? = null): String {
        if (mirrorSource == MirrorSource.MODELSCOPE && !modelScopeUrl.isNullOrBlank()) {
            return modelScopeUrl
        }
        val targetBase = when (mirrorSource) {
            MirrorSource.HUGGINGFACE -> "https://huggingface.co"
            MirrorSource.HF_MIRROR, MirrorSource.MODELSCOPE -> "https://hf-mirror.com"
            MirrorSource.CUSTOM -> customMirrorUrl.trim().trimEnd('/').ifEmpty { "https://hf-mirror.com" }
        }
        return if (hfUrl.startsWith("https://huggingface.co/")) {
            targetBase + hfUrl.removePrefix("https://huggingface.co")
        } else {
            hfUrl
        }
    }

    companion object {
        fun defaultCpuThreads(): Int {
            val cores = Runtime.getRuntime().availableProcessors()
            return when {
                cores >= 8 -> 4
                cores >= 4 -> 3
                else -> 2
            }
        }

        fun defaultMirrorForLocale(locale: Locale = Locale.getDefault()): MirrorSource {
            return if (locale.country.equals("CN", ignoreCase = true)) {
                MirrorSource.HF_MIRROR
            } else {
                MirrorSource.HUGGINGFACE
            }
        }
    }
}

class AppPreferencesRepository(private val context: Context) {

    val settingsFlow: Flow<UserSettings> = context.dataStore.data.map { prefs ->
        val modeName = prefs[KEY_INTERACTION_MODE]
        val mirrorName = prefs[KEY_MIRROR_SOURCE]
        val storedPreferred = prefs[KEY_PREFERRED_LANGUAGES]
        val defaultPreferred = SupportedLanguages.defaultPreferredForLocale()

        UserSettings(
            onboardingCompleted = prefs[KEY_ONBOARDING_COMPLETED] ?: false,
            selectedModelId = prefs[KEY_SELECTED_MODEL_ID] ?: "",
            preferredLanguages = if (storedPreferred.isNullOrEmpty()) defaultPreferred else storedPreferred,
            selectedLanguage = prefs[KEY_SELECTED_LANGUAGE] ?: "auto",
            interactionMode = RecordInteractionMode.entries.find { it.name == modeName }
                ?: RecordInteractionMode.HYBRID,
            vadFilterEnabled = prefs[KEY_VAD_ENABLED] ?: true,
            vadThreshold = (prefs[KEY_VAD_THRESHOLD] ?: 0.45f).coerceIn(0.15f, 0.85f),
            cpuThreads = (prefs[KEY_CPU_THREADS] ?: UserSettings.defaultCpuThreads()).coerceIn(1, 8),
            idleUnloadMinutes = (prefs[KEY_IDLE_UNLOAD_MIN] ?: 5).coerceIn(0, 60),
            mirrorSource = MirrorSource.entries.find { it.name == mirrorName }
                ?: UserSettings.defaultMirrorForLocale(),
            customMirrorUrl = prefs[KEY_CUSTOM_MIRROR_URL] ?: "https://hf-mirror.com",
            customCatalogUrls = prefs[KEY_CUSTOM_CATALOG_URLS] ?: emptySet(),
            whisperStylePromptEnabled = prefs[KEY_WHISPER_PROMPT_ENABLED] ?: true,
        )
    }

    suspend fun setOnboardingCompleted(completed: Boolean) {
        context.dataStore.edit { it[KEY_ONBOARDING_COMPLETED] = completed }
    }

    suspend fun setSelectedModelId(modelId: String) {
        context.dataStore.edit { it[KEY_SELECTED_MODEL_ID] = modelId }
    }

    suspend fun togglePreferredLanguage(langCode: String) {
        context.dataStore.edit { prefs ->
            val current = (prefs[KEY_PREFERRED_LANGUAGES]
                ?: SupportedLanguages.defaultPreferredForLocale()).toMutableSet()
            if (langCode in current) {
                if (current.size > 1) current.remove(langCode)
            } else {
                current.add(langCode)
            }
            prefs[KEY_PREFERRED_LANGUAGES] = current
            prefs[KEY_SELECTED_LANGUAGE] = if (current.size == 1) current.first() else "auto"
        }
    }

    suspend fun setSelectedLanguage(language: String) {
        context.dataStore.edit { it[KEY_SELECTED_LANGUAGE] = language }
    }

    suspend fun setInteractionMode(mode: RecordInteractionMode) {
        context.dataStore.edit { it[KEY_INTERACTION_MODE] = mode.name }
    }

    suspend fun setVadFilterEnabled(enabled: Boolean) {
        context.dataStore.edit { it[KEY_VAD_ENABLED] = enabled }
    }

    suspend fun setVadThreshold(threshold: Float) {
        context.dataStore.edit { it[KEY_VAD_THRESHOLD] = threshold.coerceIn(0.15f, 0.85f) }
    }

    suspend fun setCpuThreads(threads: Int) {
        context.dataStore.edit { it[KEY_CPU_THREADS] = threads.coerceIn(1, 8) }
    }

    suspend fun setIdleUnloadMinutes(minutes: Int) {
        context.dataStore.edit { it[KEY_IDLE_UNLOAD_MIN] = minutes.coerceIn(0, 60) }
    }

    suspend fun setMirrorSource(source: MirrorSource) {
        context.dataStore.edit { it[KEY_MIRROR_SOURCE] = source.name }
    }

    suspend fun setCustomMirrorUrl(url: String) {
        context.dataStore.edit { it[KEY_CUSTOM_MIRROR_URL] = url.trim() }
    }

    suspend fun addCustomCatalogUrl(url: String) {
        val clean = url.trim()
        if (clean.isEmpty()) return
        context.dataStore.edit { prefs ->
            val current = prefs[KEY_CUSTOM_CATALOG_URLS] ?: emptySet()
            prefs[KEY_CUSTOM_CATALOG_URLS] = current + clean
        }
    }

    suspend fun removeCustomCatalogUrl(url: String) {
        context.dataStore.edit { prefs ->
            val current = prefs[KEY_CUSTOM_CATALOG_URLS] ?: emptySet()
            prefs[KEY_CUSTOM_CATALOG_URLS] = current - url
        }
    }

    suspend fun setWhisperPromptEnabled(enabled: Boolean) {
        context.dataStore.edit { it[KEY_WHISPER_PROMPT_ENABLED] = enabled }
    }

    companion object {
        private val KEY_ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
        private val KEY_SELECTED_MODEL_ID = stringPreferencesKey("selected_model_id")
        private val KEY_PREFERRED_LANGUAGES = stringSetPreferencesKey("preferred_languages")
        private val KEY_SELECTED_LANGUAGE = stringPreferencesKey("selected_language")
        private val KEY_INTERACTION_MODE = stringPreferencesKey("interaction_mode")
        private val KEY_VAD_ENABLED = booleanPreferencesKey("vad_filter_enabled")
        private val KEY_VAD_THRESHOLD = floatPreferencesKey("vad_threshold")
        private val KEY_CPU_THREADS = intPreferencesKey("cpu_threads")
        private val KEY_IDLE_UNLOAD_MIN = intPreferencesKey("idle_unload_minutes")
        private val KEY_MIRROR_SOURCE = stringPreferencesKey("mirror_source")
        private val KEY_CUSTOM_MIRROR_URL = stringPreferencesKey("custom_mirror_url")
        private val KEY_CUSTOM_CATALOG_URLS = stringSetPreferencesKey("custom_catalog_urls")
        private val KEY_WHISPER_PROMPT_ENABLED = booleanPreferencesKey("whisper_prompt_enabled")
    }
}
