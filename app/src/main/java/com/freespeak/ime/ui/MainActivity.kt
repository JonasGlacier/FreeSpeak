package com.freespeak.ime.ui

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.ViewInAr
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.freespeak.ime.R
import com.freespeak.ime.model.EngineType
import com.freespeak.ime.model.ModelInstallState
import com.freespeak.ime.ui.theme.FreeSpeakTheme
import com.freespeak.ime.ui.theme.NothingRed

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            FreeSpeakTheme {
                FreeSpeakRoot(viewModel = viewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val name = intent?.getStringExtra(EXTRA_TAB) ?: return
        MainTab.entries.firstOrNull { it.name == name }?.let(viewModel::requestTab)
        intent.removeExtra(EXTRA_TAB)
    }

    companion object {
        const val EXTRA_TAB = "com.freespeak.ime.extra.TAB"
    }
}

@Composable
fun FreeSpeakRoot(viewModel: MainViewModel) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val settingsOrNull by viewModel.settingsState.collectAsStateWithLifecycle()
    val models by viewModel.models.collectAsStateWithLifecycle()
    val imeStatus by viewModel.imeStatus.collectAsStateWithLifecycle()
    val engineStatus by viewModel.engineStatus.collectAsStateWithLifecycle()
    val toastMsg by viewModel.toastMessage.collectAsStateWithLifecycle()
    val tabRequest by viewModel.tabRequest.collectAsStateWithLifecycle()
    val hubSearch by viewModel.hubSearch.collectAsStateWithLifecycle()
    val hubDetail by viewModel.hubDetail.collectAsStateWithLifecycle()

    var tab by rememberSaveable { mutableStateOf(MainTab.HOME) }
    var subPage by rememberSaveable { mutableStateOf<SettingsSubPage?>(null) }
    var choiceKey by rememberSaveable { mutableStateOf("") }
    var pendingLocalImportUri by remember { mutableStateOf<Uri?>(null) }
    var showDirectUrlDialog by remember { mutableStateOf(false) }

    val micPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        viewModel.refreshSystemStatus()
    }
    val safImportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) pendingLocalImportUri = uri
    }

    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshSystemStatus()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        val imeReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                viewModel.refreshSystemStatus()
            }
        }
        ContextCompat.registerReceiver(
            context,
            imeReceiver,
            IntentFilter(Intent.ACTION_INPUT_METHOD_CHANGED),
            ContextCompat.RECEIVER_EXPORTED,
        )
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            runCatching { context.unregisterReceiver(imeReceiver) }
        }
    }

    LaunchedEffect(toastMsg) {
        toastMsg?.let { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            viewModel.consumeToast()
        }
    }

    LaunchedEffect(tabRequest) {
        tabRequest?.let {
            tab = it
            subPage = null
            viewModel.closeHfRepo()
            viewModel.consumeTabRequest()
        }
    }

    val settings = settingsOrNull
    if (settings == null) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {}
        return
    }

    val openImeSettings = { context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
    val showImePicker = {
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.showInputMethodPicker()
        Unit
    }
    val requestMic = { micPermLauncher.launch(Manifest.permission.RECORD_AUDIO) }
    val importLocal = { safImportLauncher.launch(arrayOf("*/*")) }

    if (!settings.onboardingCompleted) {
        OnboardingScreen(
            imeStatus = imeStatus,
            models = models,
            settings = settings,
            onRequestMicPermission = requestMic,
            onMirrorChange = viewModel::setMirrorSource,
            onDownloadModel = viewModel::downloadModel,
            onCancelDownload = viewModel::cancelDownload,
            onSelectModel = viewModel::selectActiveModel,
            onImportLocalFile = importLocal,
            onOpenImeSettings = openImeSettings,
            onShowImePicker = showImePicker,
            onFinishOnboarding = viewModel::completeOnboarding,
        )
    } else {
        val modelActions = remember(viewModel) {
            ModelActions(
                onDownload = viewModel::downloadModel,
                onCancel = viewModel::cancelDownload,
                onSelect = viewModel::selectActiveModel,
                onDelete = viewModel::deleteModel,
            )
        }
        val modelOptionHandlers = remember(viewModel) {
            ModelOptionHandlers(
                onSet = viewModel::setModelOption,
                onOpenChoice = { key ->
                    choiceKey = key
                    subPage = SettingsSubPage.MODEL_CHOICE
                },
            )
        }
        val installed = models.filter { it.state is ModelInstallState.Installed }
        val activeItem = installed.firstOrNull { it.manifest.id == settings.selectedModelId } ?: installed.firstOrNull()

        BackHandler(enabled = subPage != null || hubDetail != null || tab != MainTab.HOME) {
            when {
                subPage != null -> subPage = null
                hubDetail != null -> viewModel.closeHfRepo()
                else -> tab = MainTab.HOME
            }
        }

        val tabStateHolder = rememberSaveableStateHolder()
        Box(Modifier.fillMaxSize()) {
            Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                contentWindowInsets = WindowInsets(0),
                bottomBar = { BottomBar(tab) { tab = it } },
            ) { padding ->
                Crossfade(targetState = tab, animationSpec = tween(180), label = "tab") { current ->
                  tabStateHolder.SaveableStateProvider(current.name) {
                    when (current) {
                        MainTab.HOME -> HomeScreen(
                            settings = settings,
                            activeModel = activeItem,
                            engineStatus = engineStatus,
                            imeStatus = imeStatus,
                            contentPadding = padding,
                            onRequestMicPermission = requestMic,
                            onOpenImeSettings = openImeSettings,
                            onShowImePicker = showImePicker,
                            onOpenModels = { tab = MainTab.MODELS },
                            onUnload = viewModel::unloadMemoryNow,
                            modelOptionHandlers = modelOptionHandlers,
                            onNavigate = { subPage = it },
                        )
                        MainTab.MODELS -> ModelHubScreen(
                            models = models,
                            settings = settings,
                            search = hubSearch,
                            contentPadding = padding,
                            actions = modelActions,
                            onQueryChange = viewModel::updateHubQuery,
                            onSubmitSearch = viewModel::submitHubSearch,
                            onLoadMore = viewModel::loadMoreHubResults,
                            onOpenRepo = viewModel::openHfRepo,
                            onImportLocal = importLocal,
                            onAddDirectUrl = { showDirectUrlDialog = true },
                            onOpenCatalogs = { subPage = SettingsSubPage.CATALOGS },
                            onOpenMirror = { subPage = SettingsSubPage.DOWNLOAD_MIRROR },
                        )
                        MainTab.SETTINGS -> SettingsScreen(
                            settings = settings,
                            activeModel = activeItem?.manifest,
                            imeStatus = imeStatus,
                            contentPadding = padding,
                            modelOptionHandlers = modelOptionHandlers,
                            onNavigate = { subPage = it },
                        )
                    }
                  }
                }
            }

            // Overlay pages slide over the tabs so tab state (scroll, search) is preserved.
            val lastSub = remember { mutableStateOf<SettingsSubPage?>(null) }.apply { if (subPage != null) value = subPage }
            val lastDetail = remember { mutableStateOf<HubDetailUi?>(null) }.apply { if (hubDetail != null) value = hubDetail }
            val showSub = subPage != null
            val showDetail = hubDetail != null

            AnimatedVisibility(
                visible = showDetail,
                enter = slideInHorizontally { it / 3 } + fadeIn(tween(160)),
                exit = slideOutHorizontally { it / 3 } + fadeOut(tween(160)),
            ) {
                lastDetail.value?.let { d ->
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        HuggingFaceRepoDetailScreen(
                            detail = d,
                            models = models,
                            settings = settings,
                            manifestFor = viewModel::hfManifestFor,
                            onBack = viewModel::closeHfRepo,
                            onRetry = viewModel::retryHfRepo,
                            onDownloadVariant = viewModel::downloadHfVariant,
                            onOpenMirror = { subPage = SettingsSubPage.DOWNLOAD_MIRROR },
                            actions = modelActions,
                        )
                    }
                }
            }

            AnimatedVisibility(
                visible = showSub,
                enter = slideInHorizontally { it / 3 } + fadeIn(tween(160)),
                exit = slideOutHorizontally { it / 3 } + fadeOut(tween(160)),
            ) {
                lastSub.value?.let { page ->
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        SettingsSubPageContent(
                            page = page,
                            viewModel = viewModel,
                            settings = settings,
                            activeModel = activeItem?.manifest,
                            imeStatus = imeStatus,
                            engineStatus = engineStatus,
                            choiceKey = choiceKey,
                            onBack = { subPage = null },
                            onRequestMic = requestMic,
                            onOpenImeSettings = openImeSettings,
                            onShowImePicker = showImePicker,
                        )
                    }
                }
            }
        }
    }

    pendingLocalImportUri?.let { uri ->
        LocalModelImportDialog(
            onDismiss = { pendingLocalImportUri = null },
            onConfirm = { customName, preferredEngine ->
                viewModel.importLocalModel(uri, customName, preferredEngine)
                pendingLocalImportUri = null
            },
        )
    }

    if (showDirectUrlDialog) {
        DirectUrlModelDialog(
            onDismiss = { showDirectUrlDialog = false },
            onConfirm = { name, url, engine ->
                viewModel.addDirectUrlModelAndDownload(name, url, engine)
                showDirectUrlDialog = false
            },
        )
    }
}

@Composable
private fun SettingsSubPageContent(
    page: SettingsSubPage,
    viewModel: MainViewModel,
    settings: com.freespeak.ime.data.UserSettings,
    activeModel: com.freespeak.ime.model.ModelManifest?,
    imeStatus: SystemImeStatus,
    engineStatus: com.freespeak.ime.engine.EngineRuntimeStatus,
    choiceKey: String,
    onBack: () -> Unit,
    onRequestMic: () -> Unit,
    onOpenImeSettings: () -> Unit,
    onShowImePicker: () -> Unit,
) {
    when (page) {
        SettingsSubPage.MODEL_CHOICE -> if (activeModel != null) {
            ModelChoiceScreen(
                model = activeModel,
                optionKey = choiceKey,
                settings = settings,
                onBack = onBack,
                onSelect = { viewModel.setModelOption(activeModel.id, choiceKey, it) },
            )
        } else {
            LaunchedEffect(Unit) { onBack() }
        }
        SettingsSubPage.CHINESE_OUTPUT -> ChineseOutputScreen(settings.chineseOutputMode, onBack, viewModel::setChineseOutputMode)
        SettingsSubPage.RECORDING_MODE -> RecordingModeSubScreen(settings, onBack, viewModel::setInteractionMode)
        SettingsSubPage.COLOR_SCHEME -> ColorSchemeSubScreen(settings, onBack, viewModel::setImeColorScheme)
        SettingsSubPage.TARGET_IME -> TargetImeSubScreen(settings, onBack, viewModel::setTargetImeId)
        SettingsSubPage.VAD_FILTER -> VadFilterSubScreen(settings, onBack, viewModel::setVadEnabled, viewModel::setVadThreshold)
        SettingsSubPage.DOWNLOAD_MIRROR -> DownloadMirrorSubScreen(settings, onBack, viewModel::setMirrorSource, viewModel::setCustomMirrorUrl)
        SettingsSubPage.CATALOGS -> CatalogsSubScreen(
            settings = settings,
            onBack = onBack,
            onAddCatalogUrl = viewModel::addCatalogSubscription,
            onRemoveCatalogUrl = viewModel::removeCatalogSubscription,
            onSyncCatalogs = viewModel::syncCatalogsNow,
        )
        SettingsSubPage.PERFORMANCE -> PerformanceSubScreen(
            settings = settings,
            engineStatus = engineStatus,
            onBack = onBack,
            onCpuThreadsChange = viewModel::setCpuThreads,
            onIdleUnloadChange = viewModel::setIdleUnloadMinutes,
            onUnloadNow = viewModel::unloadMemoryNow,
        )
        SettingsSubPage.KEYBOARD_SETUP -> KeyboardSetupSubScreen(
            imeStatus = imeStatus,
            onBack = onBack,
            onRequestMicPermission = onRequestMic,
            onOpenImeSettings = onOpenImeSettings,
            onShowImePicker = onShowImePicker,
            onRerunOnboarding = {
                onBack()
                viewModel.reopenOnboarding()
            },
        )
    }
}

// ---------------------------------------------------------------- bottom bar

@Composable
private fun BottomBar(selected: MainTab, onSelect: (MainTab) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Column {
            HairlineDivider()
            Row(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .height(64.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NavItem(MainTab.HOME, selected, Icons.Filled.Home, Icons.Outlined.Home, stringResource(R.string.nav_home), onSelect)
                NavItem(MainTab.MODELS, selected, Icons.Filled.ViewInAr, Icons.Outlined.ViewInAr, stringResource(R.string.nav_models), onSelect)
                NavItem(MainTab.SETTINGS, selected, Icons.Filled.Settings, Icons.Outlined.Settings, stringResource(R.string.nav_settings), onSelect)
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.NavItem(
    tab: MainTab,
    selected: MainTab,
    filled: ImageVector,
    outlined: ImageVector,
    label: String,
    onSelect: (MainTab) -> Unit,
) {
    val isSelected = tab == selected
    Box(
        Modifier
            .weight(1f)
            .fillMaxHeight()
            .clickable(role = Role.Tab) { onSelect(tab) }
            .semantics {
                contentDescription = label
                this.selected = isSelected
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                if (isSelected) filled else outlined,
                contentDescription = null,
                tint = if (isSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
            )
            Spacer(Modifier.height(5.dp))
            Dot(if (isSelected) NothingRed else Color.Transparent, 4.dp)
        }
    }
}

// ---------------------------------------------------------------- dialogs

@Composable
private fun LocalModelImportDialog(
    onDismiss: () -> Unit,
    onConfirm: (customName: String?, preferredEngine: EngineType?) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var selectedEngine by remember { mutableStateOf<EngineType?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(24.dp),
        title = { Text(stringResource(R.string.dialog_import_title), style = MaterialTheme.typography.titleMedium) },
        text = {
            Column {
                MonoTextField(name, { name = it }, stringResource(R.string.dialog_model_name_optional))
                Spacer(Modifier.height(8.dp))
                DialogChoice(stringResource(R.string.dialog_engine_auto), selectedEngine == null) { selectedEngine = null }
                EngineType.entries.filterNot { it == EngineType.FLUID_TDT }.forEach { eng ->
                    DialogChoice(eng.displayName, selectedEngine == eng) { selectedEngine = eng }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name.takeIf { it.isNotBlank() }, selectedEngine) }) {
                Text(stringResource(R.string.action_next), color = MaterialTheme.colorScheme.onSurface)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        },
    )
}

@Composable
private fun DirectUrlModelDialog(
    onDismiss: () -> Unit,
    onConfirm: (name: String, url: String, engine: EngineType) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var engine by remember { mutableStateOf(EngineType.WHISPER_CPP) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(24.dp),
        title = { Text(stringResource(R.string.dialog_direct_url_title), style = MaterialTheme.typography.titleMedium) },
        text = {
            Column {
                MonoTextField(name, { name = it }, stringResource(R.string.dialog_model_name))
                Spacer(Modifier.height(8.dp))
                MonoTextField(url, { url = it }, stringResource(R.string.dialog_model_url), keyboardType = KeyboardType.Uri)
                Spacer(Modifier.height(8.dp))
                EngineType.entries.filterNot { it == EngineType.FLUID_TDT }.forEach { eng ->
                    DialogChoice(eng.displayName, engine == eng) { engine = eng }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name, url, engine) },
                enabled = name.isNotBlank() && url.startsWith("http"),
            ) {
                Text(stringResource(R.string.action_download))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        },
    )
}

@Composable
private fun DialogChoice(text: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SelectDot(selected)
        Spacer(Modifier.width(14.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}
