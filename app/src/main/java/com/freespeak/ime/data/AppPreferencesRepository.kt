package com.freespeak.ime.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "freespeak_prefs")

enum class RecordInteractionMode(val label: String, val description: String) {
    HYBRID("智能混合（短按开关 / 按住说话）", "轻点开始或结束录音；按住说话松手即识别，上滑取消"),
    TAP_ONLY("仅点击录音", "点击开始录音，再次点击结束并转写"),
    HOLD_ONLY("仅按住说话", "按住麦克风说话，松开立即转写，上滑取消"),
}

enum class MirrorSource(val label: String, val hfBaseUrl: String) {
    HF_MIRROR("HF-Mirror 国内镜像 (推荐)", "https://hf-mirror.com"),
    MODELSCOPE("ModelScope 魔搭优先", "https://hf-mirror.com"),
    HUGGINGFACE("HuggingFace 官方直连", "https://huggingface.co"),
    CUSTOM("自定义镜像源", ""),
}

data class UserSettings(
    val selectedModelId: String = "",
    val selectedLanguage: String = "auto",
    val interactionMode: RecordInteractionMode = RecordInteractionMode.HYBRID,
    val vadFilterEnabled: Boolean = true,
    val vadThreshold: Float = 0.45f,
    val cpuThreads: Int = defaultCpuThreads(),
    val idleUnloadMinutes: Int = 5,
    val mirrorSource: MirrorSource = MirrorSource.HF_MIRROR,
    val customMirrorUrl: String = "https://hf-mirror.com",
    val customCatalogUrls: Set<String> = emptySet(),
    val whisperStylePromptEnabled: Boolean = true,
) {
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
    }
}

class AppPreferencesRepository(private val context: Context) {

    val settingsFlow: Flow<UserSettings> = context.dataStore.data.map { prefs ->
        val modeName = prefs[KEY_INTERACTION_MODE]
        val mirrorName = prefs[KEY_MIRROR_SOURCE]
        UserSettings(
            selectedModelId = prefs[KEY_SELECTED_MODEL_ID] ?: "",
            selectedLanguage = prefs[KEY_SELECTED_LANGUAGE] ?: "auto",
            interactionMode = RecordInteractionMode.entries.find { it.name == modeName }
                ?: RecordInteractionMode.HYBRID,
            vadFilterEnabled = prefs[KEY_VAD_ENABLED] ?: true,
            vadThreshold = (prefs[KEY_VAD_THRESHOLD] ?: 0.45f).coerceIn(0.15f, 0.85f),
            cpuThreads = (prefs[KEY_CPU_THREADS] ?: UserSettings.defaultCpuThreads()).coerceIn(1, 8),
            idleUnloadMinutes = (prefs[KEY_IDLE_UNLOAD_MIN] ?: 5).coerceIn(0, 60),
            mirrorSource = MirrorSource.entries.find { it.name == mirrorName } ?: MirrorSource.HF_MIRROR,
            customMirrorUrl = prefs[KEY_CUSTOM_MIRROR_URL] ?: "https://hf-mirror.com",
            customCatalogUrls = prefs[KEY_CUSTOM_CATALOG_URLS] ?: emptySet(),
            whisperStylePromptEnabled = prefs[KEY_WHISPER_PROMPT_ENABLED] ?: true,
        )
    }

    suspend fun setSelectedModelId(modelId: String) {
        context.dataStore.edit { it[KEY_SELECTED_MODEL_ID] = modelId }
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
        private val KEY_SELECTED_MODEL_ID = stringPreferencesKey("selected_model_id")
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
