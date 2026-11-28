package com.freespeak.ime.ui

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.freespeak.ime.FreeSpeakApplication
import com.freespeak.ime.R
import com.freespeak.ime.data.ImeColorScheme
import com.freespeak.ime.data.MirrorSource
import com.freespeak.ime.data.RecordInteractionMode
import com.freespeak.ime.data.UserSettings
import com.freespeak.ime.engine.EngineRuntimeStatus
import com.freespeak.ime.model.EngineType
import com.freespeak.ime.model.ManagedModelItem
import com.freespeak.ime.model.ModelManifest
import com.freespeak.ime.text.ChineseOutputMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SystemImeStatus(
    val hasMicPermission: Boolean = false,
    val isImeEnabled: Boolean = false,
    val isImeSelected: Boolean = false,
) {
    val isFullyReady: Boolean
        get() = hasMicPermission && isImeEnabled && isImeSelected
}

enum class MainTab { HOME, MODELS, SETTINGS }

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as FreeSpeakApplication
    private val prefs = app.preferencesRepository
    private val modelRepo = app.modelRepository
    private val engineMgr = app.engineManager

    val settingsState: StateFlow<UserSettings?> = prefs.settingsFlow
        .map<UserSettings, UserSettings?> { it }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = null,
        )

    val models: StateFlow<List<ManagedModelItem>> = modelRepo.models

    val itnPacks = app.itnPackRepository.state
    private var itnJob: kotlinx.coroutines.Job? = null
    fun setItnEnabled(enabled: Boolean) { viewModelScope.launch { prefs.setItnEnabled(enabled) } }
    fun setItnLanguage(language: String) { viewModelScope.launch { prefs.setItnLanguage(language) } }
    fun downloadItnPack(language: String) {
        if (itnJob?.isActive == true) return
        itnJob = viewModelScope.launch { app.itnPackRepository.install(language) }
    }
    fun deleteItnPack(language: String) {
        if (itnJob?.isActive == true) return
        itnJob = viewModelScope.launch {
            app.itnPackRepository.delete(language)
            engineMgr.releaseItnPack(language)
        }
    }
    fun cancelItnDownload() { itnJob?.cancel() }

    val engineStatus: StateFlow<EngineRuntimeStatus> = engineMgr.status

    private val _imeStatus = MutableStateFlow(SystemImeStatus())
    val imeStatus: StateFlow<SystemImeStatus> = _imeStatus.asStateFlow()

    private val _toastMessage = MutableStateFlow<String?>(null)
    val toastMessage: StateFlow<String?> = _toastMessage.asStateFlow()

    private val _tabRequest = MutableStateFlow<MainTab?>(null)
    val tabRequest: StateFlow<MainTab?> = _tabRequest.asStateFlow()

    init {
        refreshSystemStatus()
        viewModelScope.launch {
            val initial = prefs.settingsFlow.first()
            if (initial.customCatalogUrls.isNotEmpty()) {
                modelRepo.syncExternalCatalogs(initial.customCatalogUrls)
            }
        }
    }

    fun consumeToast() {
        _toastMessage.value = null
    }

    fun requestTab(tab: MainTab) {
        _tabRequest.value = tab
    }

    fun consumeTabRequest() {
        _tabRequest.value = null
    }

    fun refreshSystemStatus() {
        val ctx = getApplication<Application>()
        val hasPerm = ContextCompat.checkSelfPermission(
            ctx,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        val pkgName = ctx.packageName
        val enabled = imm?.enabledInputMethodList?.any { it.packageName == pkgName } == true

        val defaultIme = runCatching {
            Settings.Secure.getString(ctx.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        }.getOrNull().orEmpty()
        val selected = defaultIme.startsWith("$pkgName/")

        _imeStatus.value = SystemImeStatus(
            hasMicPermission = hasPerm,
            isImeEnabled = enabled,
            isImeSelected = selected,
        )
        modelRepo.refreshLocalInventory()
    }

    fun completeOnboarding() {
        viewModelScope.launch {
            prefs.setOnboardingCompleted(true)
        }
    }

    fun reopenOnboarding() {
        viewModelScope.launch {
            prefs.setOnboardingCompleted(false)
        }
    }

    fun downloadModel(manifest: ModelManifest) {
        val currentSettings = settingsState.value ?: UserSettings()
        modelRepo.startDownload(
            manifest = manifest,
            settings = currentSettings,
            onInstalled = { installed ->
                viewModelScope.launch {
                    val cur = settingsState.value?.selectedModelId.orEmpty()
                    if (cur.isBlank() || modelRepo.findInstalledManifest(cur) == null) {
                        prefs.setSelectedModelId(installed.id)
                    }
                    _toastMessage.value = app.getString(R.string.toast_model_ready, installed.name)
                }
            }
        )
    }

    fun cancelDownload(modelId: String) {
        modelRepo.cancelDownload(modelId)
    }

    fun selectActiveModel(modelId: String) {
        viewModelScope.launch {
            prefs.setSelectedModelId(modelId)
        }
    }

    fun deleteModel(manifest: ModelManifest) {
        viewModelScope.launch {
            val curId = settingsState.value?.selectedModelId.orEmpty()
            if (curId == manifest.id) {
                engineMgr.unloadNow()
            }
            modelRepo.deleteModel(manifest.id)
            prefs.clearModelOptions(manifest.id)
            val remaining = modelRepo.listInstalledManifests()
            if (curId == manifest.id) {
                prefs.setSelectedModelId(remaining.firstOrNull()?.id ?: "")
            }
            _toastMessage.value = app.getString(R.string.toast_model_deleted, manifest.name)
        }
    }

    fun importLocalModel(uri: Uri, customName: String?, engineType: EngineType?) {
        viewModelScope.launch {
            modelRepo.importLocalModelUri(uri, customName, engineType).fold(
                onSuccess = { imported ->
                    prefs.setSelectedModelId(imported.id)
                    _toastMessage.value = app.getString(R.string.toast_model_ready, imported.name)
                },
                onFailure = { err ->
                    _toastMessage.value = app.getString(
                        R.string.toast_import_failed,
                        err.message ?: ""
                    )
                }
            )
        }
    }

    fun addDirectUrlModelAndDownload(name: String, url: String, engineType: EngineType) {
        runCatching { modelRepo.registerDirectUrlModel(name, url, engineType) }
            .onSuccess { downloadModel(it) }
            .onFailure { _toastMessage.value = app.getString(R.string.toast_import_failed, it.message.orEmpty()) }
    }

    fun addCatalogSubscription(url: String) {
        viewModelScope.launch {
            prefs.addCustomCatalogUrl(url)
            val curUrls = settingsState.value?.customCatalogUrls.orEmpty() + url.trim()
            modelRepo.syncExternalCatalogs(curUrls).fold(
                onSuccess = { count ->
                    _toastMessage.value = app.getString(R.string.toast_catalog_synced, count)
                },
                onFailure = { err ->
                    _toastMessage.value = app.getString(R.string.toast_sync_failed, err.message ?: "")
                }
            )
        }
    }

    fun removeCatalogSubscription(url: String) {
        viewModelScope.launch {
            prefs.removeCustomCatalogUrl(url)
            val updatedUrls = settingsState.value?.customCatalogUrls.orEmpty() - url
            modelRepo.syncExternalCatalogs(updatedUrls)
        }
    }

    fun syncCatalogsNow() {
        viewModelScope.launch {
            val urls = settingsState.value?.customCatalogUrls.orEmpty()
            if (urls.isEmpty()) return@launch
            modelRepo.syncExternalCatalogs(urls).fold(
                onSuccess = { count ->
                    _toastMessage.value = app.getString(R.string.toast_catalog_synced, count)
                },
                onFailure = { err ->
                    _toastMessage.value = app.getString(R.string.toast_sync_failed, err.message ?: "")
                }
            )
        }
    }

    fun togglePreferredLanguage(code: String) {
        viewModelScope.launch { prefs.togglePreferredLanguage(code) }
    }

    fun setModelOption(modelId: String, key: String, value: String) {
        viewModelScope.launch { prefs.setModelOption(modelId, key, value) }
    }

    fun setChineseOutputMode(mode: ChineseOutputMode) {
        viewModelScope.launch { prefs.setChineseOutputMode(mode) }
    }

    fun setMirrorSource(source: MirrorSource) {
        viewModelScope.launch { prefs.setMirrorSource(source) }
    }

    fun setCustomMirrorUrl(url: String) {
        viewModelScope.launch { prefs.setCustomMirrorUrl(url) }
    }

    fun setTargetImeId(id: String) {
        viewModelScope.launch { prefs.setTargetImeId(id) }
    }

    fun setImeColorScheme(scheme: ImeColorScheme) {
        viewModelScope.launch { prefs.setImeColorScheme(scheme) }
    }

    fun setInteractionMode(mode: RecordInteractionMode) {
        viewModelScope.launch { prefs.setInteractionMode(mode) }
    }

    fun setVadEnabled(enabled: Boolean) {
        viewModelScope.launch { prefs.setVadFilterEnabled(enabled) }
    }

    fun setVadThreshold(threshold: Float) {
        viewModelScope.launch { prefs.setVadThreshold(threshold) }
    }

    fun setCpuThreads(threads: Int) {
        viewModelScope.launch { prefs.setCpuThreads(threads) }
    }

    fun setIdleUnloadMinutes(minutes: Int) {
        viewModelScope.launch { prefs.setIdleUnloadMinutes(minutes) }
    }

    fun unloadMemoryNow() {
        viewModelScope.launch {
            engineMgr.unloadNow()
            _toastMessage.value = app.getString(R.string.toast_ram_released)
        }
    }
}
