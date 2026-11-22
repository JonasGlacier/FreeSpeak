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
import com.freespeak.ime.data.MirrorSource
import com.freespeak.ime.data.RecordInteractionMode
import com.freespeak.ime.data.UserSettings
import com.freespeak.ime.engine.EngineRuntimeStatus
import com.freespeak.ime.model.EngineType
import com.freespeak.ime.model.ManagedModelItem
import com.freespeak.ime.model.ModelInstallState
import com.freespeak.ime.model.ModelManifest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SystemImeStatus(
    val hasMicPermission: Boolean = false,
    val isImeEnabled: Boolean = false,
    val isImeSelected: Boolean = false,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as FreeSpeakApplication
    private val prefs = app.preferencesRepository
    private val modelRepo = app.modelRepository
    private val engineMgr = app.engineManager

    val settings: StateFlow<UserSettings> = prefs.settingsFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = UserSettings(),
    )

    val models: StateFlow<List<ManagedModelItem>> = modelRepo.models

    val engineStatus: StateFlow<EngineRuntimeStatus> = engineMgr.status

    private val _imeStatus = MutableStateFlow(SystemImeStatus())
    val imeStatus: StateFlow<SystemImeStatus> = _imeStatus.asStateFlow()

    private val _toastMessage = MutableStateFlow<String?>(null)
    val toastMessage: StateFlow<String?> = _toastMessage.asStateFlow()

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

    fun downloadModel(manifest: ModelManifest) {
        modelRepo.startDownload(
            manifest = manifest,
            settings = settings.value,
            onInstalled = { installed ->
                viewModelScope.launch {
                    val cur = settings.value.selectedModelId
                    if (cur.isBlank() || modelRepo.findInstalledManifest(cur) == null) {
                        prefs.setSelectedModelId(installed.id)
                    }
                    _toastMessage.value = "模型「${installed.name}」已就绪"
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
            _toastMessage.value = "已切换默认语音模型"
        }
    }

    fun deleteModel(manifest: ModelManifest) {
        viewModelScope.launch {
            if (settings.value.selectedModelId == manifest.id) {
                engineMgr.unloadNow()
            }
            modelRepo.deleteModel(manifest.id)
            val remaining = modelRepo.listInstalledManifests()
            if (settings.value.selectedModelId == manifest.id) {
                prefs.setSelectedModelId(remaining.firstOrNull()?.id ?: "")
            }
            _toastMessage.value = "已删除 ${manifest.name}"
        }
    }

    fun importLocalModel(uri: Uri, customName: String?, engineType: EngineType?) {
        viewModelScope.launch {
            modelRepo.importLocalModelUri(uri, customName, engineType).fold(
                onSuccess = { imported ->
                    prefs.setSelectedModelId(imported.id)
                    _toastMessage.value = "成功导入本地模型：${imported.name}"
                },
                onFailure = { err ->
                    _toastMessage.value = "导入失败：${err.message}"
                }
            )
        }
    }

    fun addDirectUrlModelAndDownload(name: String, url: String, engineType: EngineType) {
        val manifest = modelRepo.registerDirectUrlModel(name, url, engineType)
        downloadModel(manifest)
    }

    fun addCatalogSubscription(url: String) {
        viewModelScope.launch {
            prefs.addCustomCatalogUrl(url)
            val updatedUrls = settings.value.customCatalogUrls + url.trim()
            modelRepo.syncExternalCatalogs(updatedUrls).fold(
                onSuccess = { count -> _toastMessage.value = "已同步外部模型源（共 $count 款模型）" },
                onFailure = { err -> _toastMessage.value = "同步模型源失败：${err.message}" }
            )
        }
    }

    fun removeCatalogSubscription(url: String) {
        viewModelScope.launch {
            prefs.removeCustomCatalogUrl(url)
            val updatedUrls = settings.value.customCatalogUrls - url
            modelRepo.syncExternalCatalogs(updatedUrls)
        }
    }

    fun syncCatalogsNow() {
        viewModelScope.launch {
            val urls = settings.value.customCatalogUrls
            if (urls.isEmpty()) {
                _toastMessage.value = "尚未添加自定义模型源地址"
                return@launch
            }
            modelRepo.syncExternalCatalogs(urls).fold(
                onSuccess = { count -> _toastMessage.value = "刷新成功，共加载 $count 款社区模型" },
                onFailure = { err -> _toastMessage.value = "刷新失败：${err.message}" }
            )
        }
    }

    fun setMirrorSource(source: MirrorSource) {
        viewModelScope.launch { prefs.setMirrorSource(source) }
    }

    fun setCustomMirrorUrl(url: String) {
        viewModelScope.launch { prefs.setCustomMirrorUrl(url) }
    }

    fun setInteractionMode(mode: RecordInteractionMode) {
        viewModelScope.launch { prefs.setInteractionMode(mode) }
    }

    fun setLanguage(lang: String) {
        viewModelScope.launch { prefs.setSelectedLanguage(lang) }
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

    fun setWhisperPromptEnabled(enabled: Boolean) {
        viewModelScope.launch { prefs.setWhisperPromptEnabled(enabled) }
    }

    fun unloadMemoryNow() {
        viewModelScope.launch {
            engineMgr.unloadNow()
            _toastMessage.value = "已释放本地语音模型占用的内存"
        }
    }

    val hasAnyInstalledModel: Boolean
        get() = models.value.any { it.state is ModelInstallState.Installed }
}
