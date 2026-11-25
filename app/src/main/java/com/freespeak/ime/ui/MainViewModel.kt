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
import com.freespeak.ime.model.ModelInstallState
import com.freespeak.ime.model.ModelManifest
import com.freespeak.ime.model.hf.HfModelSummary
import com.freespeak.ime.model.hf.HfRepoAnalysis
import com.freespeak.ime.model.hf.HfSearchCursor
import com.freespeak.ime.model.hf.HfVariant
import com.freespeak.ime.model.hf.HuggingFaceClient
import com.freespeak.ime.model.hf.HuggingFaceModelResolver
import com.freespeak.ime.text.ChineseOutputMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
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

data class HubSearchUi(
    val query: String = "",
    /** Last submitted query; blank shows the curated list. */
    val submitted: String = "",
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val results: List<HfModelSummary> = emptyList(),
    val cursor: HfSearchCursor? = null,
    val error: String? = null,
)

sealed interface HubDetailUi {
    val repoId: String

    data class Loading(override val repoId: String) : HubDetailUi
    data class Ready(val analysis: HfRepoAnalysis) : HubDetailUi {
        override val repoId: String get() = analysis.info.id
    }
    data class Failed(override val repoId: String, val message: String) : HubDetailUi
}

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

    val engineStatus: StateFlow<EngineRuntimeStatus> = engineMgr.status

    private val _imeStatus = MutableStateFlow(SystemImeStatus())
    val imeStatus: StateFlow<SystemImeStatus> = _imeStatus.asStateFlow()

    private val _toastMessage = MutableStateFlow<String?>(null)
    val toastMessage: StateFlow<String?> = _toastMessage.asStateFlow()

    private val _tabRequest = MutableStateFlow<MainTab?>(null)
    val tabRequest: StateFlow<MainTab?> = _tabRequest.asStateFlow()

    private val _hubSearch = MutableStateFlow(HubSearchUi())
    val hubSearch: StateFlow<HubSearchUi> = _hubSearch.asStateFlow()

    private val _hubDetail = MutableStateFlow<HubDetailUi?>(null)
    val hubDetail: StateFlow<HubDetailUi?> = _hubDetail.asStateFlow()

    private var searchJob: Job? = null
    private var detailJob: Job? = null

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

    // ---------------------------------------------------------------- Hugging Face

    private fun apiBase(): String = (settingsState.value ?: UserSettings()).hubApiBase

    fun updateHubQuery(query: String) {
        _hubSearch.update { it.copy(query = query) }
        if (query.isBlank()) clearHubSearch()
    }

    fun clearHubSearch() {
        searchJob?.cancel()
        _hubSearch.value = HubSearchUi()
    }

    fun submitHubSearch(raw: String = _hubSearch.value.query) {
        val query = raw.trim()
        if (query.isEmpty()) {
            clearHubSearch()
            return
        }
        // Direct repo ids / pasted links open the repo straight away.
        val looksLikeRef = query.contains('/') && !query.contains(' ')
        HuggingFaceClient.parseRepoReference(query)?.takeIf { looksLikeRef }?.let { repoId ->
            _hubSearch.update { it.copy(query = query) }
            openHfRepo(repoId)
            return
        }
        searchJob?.cancel()
        _hubSearch.value = HubSearchUi(query = query, submitted = query, loading = true)
        searchJob = viewModelScope.launch {
            try {
                val page = modelRepo.huggingFace.search(apiBase(), query)
                _hubSearch.update { it.copy(loading = false, results = page.items, cursor = page.nextCursor, error = null) }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                _hubSearch.update { it.copy(loading = false, error = e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    fun loadMoreHubResults() {
        val cur = _hubSearch.value
        val cursor = cur.cursor ?: return
        if (cur.loading || cur.loadingMore) return
        _hubSearch.update { it.copy(loadingMore = true) }
        searchJob = viewModelScope.launch {
            try {
                val page = modelRepo.huggingFace.search(apiBase(), cur.submitted, cursor)
                _hubSearch.update { s ->
                    val known = s.results.map { it.id }.toSet()
                    s.copy(
                        loadingMore = false,
                        results = s.results + page.items.filter { it.id !in known },
                        cursor = page.nextCursor,
                    )
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                _hubSearch.update { it.copy(loadingMore = false, cursor = null) }
                _toastMessage.value = e.message ?: e.javaClass.simpleName
            }
        }
    }

    fun openHfRepo(repoId: String) {
        detailJob?.cancel()
        _hubDetail.value = HubDetailUi.Loading(repoId)
        detailJob = viewModelScope.launch {
            try {
                val info = modelRepo.huggingFace.modelInfo(apiBase(), repoId)
                _hubDetail.value = HubDetailUi.Ready(HuggingFaceModelResolver.analyze(info))
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                _hubDetail.value = HubDetailUi.Failed(repoId, e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun retryHfRepo() {
        _hubDetail.value?.repoId?.let(::openHfRepo)
    }

    fun closeHfRepo() {
        detailJob?.cancel()
        _hubDetail.value = null
    }

    fun hfManifestFor(analysis: HfRepoAnalysis, variant: HfVariant): ModelManifest =
        HuggingFaceModelResolver.toManifest(analysis.info, variant, analysis.variants.size)

    fun downloadHfVariant(analysis: HfRepoAnalysis, variant: HfVariant) {
        if (analysis.blocked) return
        val manifest = hfManifestFor(analysis, variant)
        modelRepo.registerManifest(manifest)
        downloadModel(manifest)
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
