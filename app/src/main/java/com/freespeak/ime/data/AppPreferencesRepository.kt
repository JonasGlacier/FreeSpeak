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
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.util.Locale
import com.freespeak.ime.text.ChineseOutputMode

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "freespeak_prefs")

enum class RecordInteractionMode(
    @get:StringRes val shortTitleRes: Int,
    @get:StringRes val descriptionRes: Int,
) {
    HYBRID(R.string.mode_hybrid_short, R.string.mode_hybrid_desc),
    TAP_ONLY(R.string.mode_tap_short, R.string.mode_tap_desc),
    HOLD_ONLY(R.string.mode_hold_short, R.string.mode_hold_desc),
}

enum class ImeColorScheme(
    @get:StringRes val titleRes: Int,
    @get:StringRes val descriptionRes: Int,
) {
    DYNAMIC(R.string.color_scheme_dynamic, R.string.color_scheme_dynamic_desc),
    CLASSIC(R.string.color_scheme_classic, R.string.color_scheme_classic_desc),
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

    fun defaultPreferredForLocale(locale: Locale = Locale.getDefault()): Set<String> =
        com.freespeak.ime.model.LanguageFilter.defaultFor(locale)
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
    val spokenLanguageHint: String = "auto",
    val chineseOutputMode: ChineseOutputMode = ChineseOutputMode.SYSTEM,
    val itnEnabled: Boolean = false,
    val itnLanguage: String = "auto",
    val itnCatalogUrl: String = "",
    val targetImeId: String = "",
    val imeColorScheme: ImeColorScheme = ImeColorScheme.DYNAMIC,
    /** Per-model option values: modelId -> (option key -> value). See ModelOptions. */
    val modelOptions: Map<String, Map<String, String>> = emptyMap(),
) {
    /** Installed model the user is effectively using (selected, else first installed). */
    fun resolveActiveModelId(installedIds: List<String>): String? =
        installedIds.firstOrNull { it == selectedModelId } ?: installedIds.firstOrNull()

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
            // Do not migrate the old forced language: default back to genuine auto-detection.
            spokenLanguageHint = prefs[KEY_SPOKEN_LANGUAGE_HINT] ?: "auto",
            chineseOutputMode = ChineseOutputMode.entries.find { it.name == prefs[KEY_CHINESE_OUTPUT] }
                ?: ChineseOutputMode.SYSTEM,
            targetImeId = prefs[KEY_TARGET_IME_ID] ?: "",
            imeColorScheme = ImeColorScheme.entries.find { it.name == prefs[KEY_IME_COLOR_SCHEME] }
                ?: ImeColorScheme.DYNAMIC,
            modelOptions = decodeModelOptions(prefs[KEY_MODEL_OPTIONS]),
            itnEnabled = prefs[KEY_ITN_ENABLED] ?: false,
            itnLanguage = prefs[KEY_ITN_LANGUAGE] ?: "auto",
            itnCatalogUrl = prefs[KEY_ITN_CATALOG] ?: "",
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

    suspend fun setSpokenLanguageHint(language: String) {
        context.dataStore.edit { it[KEY_SPOKEN_LANGUAGE_HINT] = language }
    }

    suspend fun setChineseOutputMode(mode: ChineseOutputMode) {
        context.dataStore.edit { it[KEY_CHINESE_OUTPUT] = mode.name }
    }

    suspend fun setItnEnabled(enabled: Boolean) {
        context.dataStore.edit { it[KEY_ITN_ENABLED] = enabled }
    }

    suspend fun setItnLanguage(language: String) {
        require(language == "auto" || language in com.freespeak.ime.itn.ItnPackCatalog.languages)
        context.dataStore.edit { it[KEY_ITN_LANGUAGE] = language }
    }

    suspend fun setItnCatalogUrl(url: String) {
        context.dataStore.edit { it[KEY_ITN_CATALOG] = url.trim() }
    }

    suspend fun setModelOption(modelId: String, key: String, value: String) {
        if (modelId.isBlank()) return
        context.dataStore.edit { prefs ->
            val all = decodeModelOptions(prefs[KEY_MODEL_OPTIONS]).toMutableMap()
            all[modelId] = all[modelId].orEmpty() + (key to value)
            prefs[KEY_MODEL_OPTIONS] = modelOptionsJson.encodeToString(modelOptionsSerializer, all)
        }
    }

    suspend fun clearModelOptions(modelId: String) {
        context.dataStore.edit { prefs ->
            val all = decodeModelOptions(prefs[KEY_MODEL_OPTIONS])
            if (modelId in all) prefs[KEY_MODEL_OPTIONS] = modelOptionsJson.encodeToString(modelOptionsSerializer, all - modelId)
        }
    }

    suspend fun setTargetImeId(id: String) {
        context.dataStore.edit { it[KEY_TARGET_IME_ID] = id }
    }

    suspend fun setImeColorScheme(scheme: ImeColorScheme) {
        context.dataStore.edit { it[KEY_IME_COLOR_SCHEME] = scheme.name }
    }

    companion object {
        private val modelOptionsJson = Json { ignoreUnknownKeys = true }
        private val modelOptionsSerializer = MapSerializer(String.serializer(), MapSerializer(String.serializer(), String.serializer()))

        internal fun decodeModelOptions(raw: String?): Map<String, Map<String, String>> =
            if (raw.isNullOrBlank()) emptyMap()
            else runCatching { modelOptionsJson.decodeFromString(modelOptionsSerializer, raw) }.getOrDefault(emptyMap())

        private val KEY_MODEL_OPTIONS = stringPreferencesKey("model_options")
        private val KEY_ITN_ENABLED = booleanPreferencesKey("itn_enabled")
        private val KEY_ITN_LANGUAGE = stringPreferencesKey("itn_language")
        private val KEY_ITN_CATALOG = stringPreferencesKey("itn_catalog_url")
        private val KEY_TARGET_IME_ID = stringPreferencesKey("target_ime_id")
        private val KEY_IME_COLOR_SCHEME = stringPreferencesKey("ime_color_scheme")
        private val KEY_ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
        private val KEY_SPOKEN_LANGUAGE_HINT = stringPreferencesKey("spoken_language_hint")
        private val KEY_CHINESE_OUTPUT = stringPreferencesKey("chinese_output_mode")
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
    }
}
