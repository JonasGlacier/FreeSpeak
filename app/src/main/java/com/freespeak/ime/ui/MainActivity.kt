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
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.freespeak.ime.R
import com.freespeak.ime.data.ImeColorScheme
import com.freespeak.ime.data.MirrorSource
import com.freespeak.ime.data.RecordInteractionMode
import com.freespeak.ime.data.SupportedLanguages
import com.freespeak.ime.data.UserSettings
import com.freespeak.ime.engine.EngineRuntimeStatus
import com.freespeak.ime.ime.ImePanelTheme
import com.freespeak.ime.model.EngineType
import com.freespeak.ime.model.ManagedModelItem
import com.freespeak.ime.model.ModelInstallState
import com.freespeak.ime.model.ModelManifest
import com.freespeak.ime.model.canHintLanguage
import com.freespeak.ime.text.ChineseOutputMode
import com.freespeak.ime.ui.theme.FreeSpeakTheme
import kotlin.math.roundToInt

enum class SettingsSubPage {
    HOME,
    VOICE_MODEL,
    PREFERRED_LANGUAGES,
    CHINESE_OUTPUT,
    RECORDING_MODE,
    COLOR_SCHEME,
    TARGET_IME,
    VAD_FILTER,
    DOWNLOAD_MIRROR,
    PERFORMANCE,
    KEYBOARD_SETUP,
    TEST_INPUT,
}

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FreeSpeakTheme {
                FreeSpeakRoot(viewModel = viewModel)
            }
        }
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

    var currentSubPage by rememberSaveable { mutableStateOf(SettingsSubPage.HOME) }
    var pendingLocalImportUri by remember { mutableStateOf<Uri?>(null) }
    var showDirectUrlDialog by remember { mutableStateOf(false) }

    val micPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) {
        viewModel.refreshSystemStatus()
    }

    val safImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) pendingLocalImportUri = uri
    }

    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshSystemStatus()
            }
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
            ContextCompat.RECEIVER_EXPORTED
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

    val settings = settingsOrNull
    if (settings == null) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {}
        return
    }

    if (!settings.onboardingCompleted) {
        OnboardingScreen(
            imeStatus = imeStatus,
            models = models,
            settings = settings,
            onRequestMicPermission = { micPermLauncher.launch(Manifest.permission.RECORD_AUDIO) },
            onMirrorChange = viewModel::setMirrorSource,
            onDownloadModel = viewModel::downloadModel,
            onCancelDownload = viewModel::cancelDownload,
            onSelectModel = viewModel::selectActiveModel,
            onImportLocalFile = { safImportLauncher.launch(arrayOf("*/*")) },
            onOpenImeSettings = {
                context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
            },
            onShowImePicker = {
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.showInputMethodPicker()
            },
            onFinishOnboarding = viewModel::completeOnboarding,
        )
    } else {
        BackHandler(enabled = currentSubPage != SettingsSubPage.HOME) {
            currentSubPage = SettingsSubPage.HOME
        }

        AnimatedContent(targetState = currentSubPage, label = "settings_nav") { page ->
            when (page) {
                SettingsSubPage.HOME -> SettingsHomeListScreen(
                    settings = settings,
                    models = models,
                    imeStatus = imeStatus,
                    onNavigate = { currentSubPage = it },
                )

                SettingsSubPage.VOICE_MODEL -> VoiceModelSubScreen(
                    models = models,
                    settings = settings,
                    onBack = { currentSubPage = SettingsSubPage.HOME },
                    onDownload = viewModel::downloadModel,
                    onCancel = viewModel::cancelDownload,
                    onSelect = viewModel::selectActiveModel,
                    onDelete = viewModel::deleteModel,
                    onOpenMirrorSettings = { currentSubPage = SettingsSubPage.DOWNLOAD_MIRROR },
                )

                SettingsSubPage.PREFERRED_LANGUAGES -> LanguageSettingsScreen(
                    settings = settings,
                    model = models.firstOrNull { it.manifest.id == settings.selectedModelId && it.state is ModelInstallState.Installed }?.manifest
                        ?: models.firstOrNull { it.state is ModelInstallState.Installed }?.manifest,
                    onBack = { currentSubPage = SettingsSubPage.HOME },
                    onHintChange = viewModel::setSpokenLanguageHint,
                    onTranslationChange = viewModel::setTranslateToEnglish,
                    onWhisperPromptChange = viewModel::setWhisperPromptEnabled,
                )

                SettingsSubPage.CHINESE_OUTPUT -> ChineseOutputScreen(
                    settings.chineseOutputMode,
                    onBack = { currentSubPage = SettingsSubPage.HOME },
                    onSelect = viewModel::setChineseOutputMode,
                )

                SettingsSubPage.RECORDING_MODE -> RecordingModeSubScreen(
                    settings = settings,
                    onBack = { currentSubPage = SettingsSubPage.HOME },
                    onSelectMode = viewModel::setInteractionMode,
                )

                SettingsSubPage.COLOR_SCHEME -> ColorSchemeSubScreen(
                    settings = settings,
                    onBack = { currentSubPage = SettingsSubPage.HOME },
                    onSelectScheme = viewModel::setImeColorScheme,
                )

                SettingsSubPage.TARGET_IME -> TargetImeSubScreen(
                    settings = settings,
                    onBack = { currentSubPage = SettingsSubPage.HOME },
                    onSelect = viewModel::setTargetImeId,
                )

                SettingsSubPage.VAD_FILTER -> VadFilterSubScreen(
                    settings = settings,
                    onBack = { currentSubPage = SettingsSubPage.HOME },
                    onVadEnabledChange = viewModel::setVadEnabled,
                    onVadThresholdChange = viewModel::setVadThreshold,
                )

                SettingsSubPage.DOWNLOAD_MIRROR -> DownloadMirrorSubScreen(
                    settings = settings,
                    onBack = { currentSubPage = SettingsSubPage.HOME },
                    onMirrorChange = viewModel::setMirrorSource,
                    onCustomMirrorUrlChange = viewModel::setCustomMirrorUrl,
                    onImportLocal = { safImportLauncher.launch(arrayOf("*/*")) },
                    onOpenDirectUrlDialog = { showDirectUrlDialog = true },
                    onAddCatalogUrl = viewModel::addCatalogSubscription,
                    onRemoveCatalogUrl = viewModel::removeCatalogSubscription,
                    onSyncCatalogs = viewModel::syncCatalogsNow,
                )

                SettingsSubPage.PERFORMANCE -> PerformanceSubScreen(
                    settings = settings,
                    engineStatus = engineStatus,
                    onBack = { currentSubPage = SettingsSubPage.HOME },
                    onCpuThreadsChange = viewModel::setCpuThreads,
                    onIdleUnloadChange = viewModel::setIdleUnloadMinutes,
                    onUnloadNow = viewModel::unloadMemoryNow,
                )

                SettingsSubPage.KEYBOARD_SETUP -> KeyboardSetupSubScreen(
                    imeStatus = imeStatus,
                    onBack = { currentSubPage = SettingsSubPage.HOME },
                    onRequestMicPermission = { micPermLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                    onOpenImeSettings = {
                        context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
                    },
                    onShowImePicker = {
                        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                        imm?.showInputMethodPicker()
                    },
                    onRerunOnboarding = {
                        currentSubPage = SettingsSubPage.HOME
                        viewModel.reopenOnboarding()
                    },
                )

                SettingsSubPage.TEST_INPUT -> TestInputSubScreen(
                    onBack = { currentSubPage = SettingsSubPage.HOME },
                )
            }
        }
    }

    pendingLocalImportUri?.let { uri ->
        LocalModelImportDialog(
            onDismiss = { pendingLocalImportUri = null },
            onConfirm = { customName, preferredEngine ->
                viewModel.importLocalModel(uri, customName, preferredEngine)
                pendingLocalImportUri = null
            }
        )
    }

    if (showDirectUrlDialog) {
        DirectUrlModelDialog(
            onDismiss = { showDirectUrlDialog = false },
            onConfirm = { name, url, engine ->
                viewModel.addDirectUrlModelAndDownload(name, url, engine)
                showDirectUrlDialog = false
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsHomeListScreen(
    settings: UserSettings,
    models: List<ManagedModelItem>,
    imeStatus: SystemImeStatus,
    onNavigate: (SettingsSubPage) -> Unit,
) {
    val installedModels = models.filter { it.state is ModelInstallState.Installed }
    val activeModel = installedModels.find { it.manifest.id == settings.selectedModelId }
        ?: installedModels.firstOrNull()

    val idleLabel = if (settings.idleUnloadMinutes <= 0) {
        stringResource(R.string.idle_never)
    } else {
        stringResource(R.string.idle_minutes, settings.idleUnloadMinutes)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.app_name),
                        fontWeight = FontWeight.SemiBold
                    )
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
        ) {
            SectionHeader(stringResource(R.string.section_general))

            SettingsRowItem(
                title = stringResource(R.string.pref_voice_model),
                value = activeModel?.manifest?.name ?: stringResource(R.string.pref_voice_model_none),
                onClick = { onNavigate(SettingsSubPage.VOICE_MODEL) },
            )
            SettingsDivider()

            SettingsRowItem(
                title = stringResource(R.string.pref_preferred_languages),
                value = when {
                    activeModel?.manifest?.canHintLanguage != true -> stringResource(R.string.language_builtin_auto)
                    settings.spokenLanguageHint == "auto" -> stringResource(R.string.pref_lang_auto_mixed)
                    else -> SupportedLanguages.labelFor(settings.spokenLanguageHint)
                },
                onClick = { onNavigate(SettingsSubPage.PREFERRED_LANGUAGES) },
            )
            SettingsDivider()

            SettingsRowItem(
                title = stringResource(R.string.chinese_output_title),
                value = stringResource(settings.chineseOutputMode.titleRes()),
                onClick = { onNavigate(SettingsSubPage.CHINESE_OUTPUT) },
            )
            SettingsDivider()

            SettingsRowItem(
                title = stringResource(R.string.pref_recording_mode),
                value = stringResource(settings.interactionMode.shortTitleRes),
                onClick = { onNavigate(SettingsSubPage.RECORDING_MODE) },
            )
            SettingsDivider()

            SettingsRowItem(
                title = stringResource(R.string.pref_color_scheme),
                value = stringResource(settings.imeColorScheme.titleRes),
                onClick = { onNavigate(SettingsSubPage.COLOR_SCHEME) },
            )
            SettingsDivider()

            val ctx = LocalContext.current
            val targetImeLabel = remember(settings.targetImeId) {
                val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.enabledInputMethodList.firstOrNull { it.id == settings.targetImeId }
                    ?.loadLabel(ctx.packageManager)?.toString()
            }
            SettingsRowItem(
                title = stringResource(R.string.pref_target_ime),
                value = targetImeLabel ?: stringResource(R.string.pref_target_ime_none),
                onClick = { onNavigate(SettingsSubPage.TARGET_IME) },
            )
            SettingsDivider()

            SettingsRowItem(
                title = stringResource(R.string.pref_vad_filter),
                value = stringResource(if (settings.vadFilterEnabled) R.string.state_on else R.string.state_off),
                onClick = { onNavigate(SettingsSubPage.VAD_FILTER) },
            )

            Spacer(Modifier.height(16.dp))
            SectionHeader(stringResource(R.string.section_system))

            SettingsRowItem(
                title = stringResource(R.string.pref_download_mirror),
                value = stringResource(settings.mirrorSource.titleRes),
                onClick = { onNavigate(SettingsSubPage.DOWNLOAD_MIRROR) },
            )
            SettingsDivider()

            SettingsRowItem(
                title = stringResource(R.string.pref_performance),
                value = stringResource(R.string.pref_performance_summary, settings.cpuThreads, idleLabel),
                onClick = { onNavigate(SettingsSubPage.PERFORMANCE) },
            )
            SettingsDivider()

            SettingsRowItem(
                title = stringResource(R.string.pref_keyboard_setup),
                value = stringResource(
                    if (imeStatus.isFullyReady) R.string.setup_status_ready else R.string.setup_status_incomplete
                ),
                highlightValueWarning = !imeStatus.isFullyReady,
                onClick = { onNavigate(SettingsSubPage.KEYBOARD_SETUP) },
            )
            SettingsDivider()

            SettingsRowItem(
                title = stringResource(R.string.pref_test_input),
                value = stringResource(R.string.pref_test_input_summary),
                onClick = { onNavigate(SettingsSubPage.TEST_INPUT) },
            )

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
    )
}

@Composable
private fun SettingsRowItem(
    title: String,
    value: String,
    highlightValueWarning: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = if (highlightValueWarning) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(4.dp))
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline
        )
    }
}

@Composable
private fun SettingsDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SubPageScaffold(
    title: String,
    onBack: () -> Unit,
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back)
                        )
                    }
                }
            )
        },
        content = content
    )
}

// ---------- Sub-screens ----------

@Composable
private fun VoiceModelSubScreen(
    models: List<ManagedModelItem>,
    settings: UserSettings,
    onBack: () -> Unit,
    onDownload: (ModelManifest) -> Unit,
    onCancel: (String) -> Unit,
    onSelect: (String) -> Unit,
    onDelete: (ModelManifest) -> Unit,
    onOpenMirrorSettings: () -> Unit,
) {
    SubPageScaffold(
        title = stringResource(R.string.pref_voice_model),
        onBack = onBack
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp)
        ) {
            item {
                Text(
                    text = stringResource(R.string.sub_models_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = onOpenMirrorSettings,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.pref_download_mirror))
                }
                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            }

            items(models, key = { it.manifest.id }) { item ->
                val isSelected = item.manifest.id == settings.selectedModelId ||
                    (settings.selectedModelId.isBlank() &&
                        item.state is ModelInstallState.Installed &&
                        models.firstOrNull { it.state is ModelInstallState.Installed }?.manifest?.id == item.manifest.id)

                CompactModelRow(
                    item = item,
                    isActive = isSelected && item.state is ModelInstallState.Installed,
                    onDownload = { onDownload(item.manifest) },
                    onCancel = { onCancel(item.manifest.id) },
                    onSelect = { onSelect(item.manifest.id) },
                    onDelete = { onDelete(item.manifest) },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            }
        }
    }
}

@Composable
private fun TargetImeSubScreen(
    settings: UserSettings,
    onBack: () -> Unit,
    onSelect: (String) -> Unit,
) {
    val ctx = LocalContext.current
    val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    val methods = remember {
        imm.enabledInputMethodList.filter { it.packageName != ctx.packageName }
    }
    SubPageScaffold(
        title = stringResource(R.string.pref_target_ime),
        onBack = onBack
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            Text(
                text = stringResource(R.string.sub_target_ime_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(16.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelect("") }
                    .padding(vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(selected = settings.targetImeId.isEmpty(), onClick = null)
                Spacer(Modifier.width(14.dp))
                Text(stringResource(R.string.pref_target_ime_none), style = MaterialTheme.typography.bodyLarge)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))

            methods.forEach { info ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(info.id) }
                        .padding(vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = settings.targetImeId == info.id, onClick = null)
                    Spacer(Modifier.width(14.dp))
                    Text(
                        text = info.loadLabel(ctx.packageManager).toString(),
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            }
        }
    }
}

@Composable
private fun ColorSchemeSubScreen(
    settings: UserSettings,
    onBack: () -> Unit,
    onSelectScheme: (ImeColorScheme) -> Unit,
) {
    val ctx = LocalContext.current
    val isDark = isSystemInDarkTheme()

    SubPageScaffold(
        title = stringResource(R.string.pref_color_scheme),
        onBack = onBack
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            Text(
                text = stringResource(R.string.sub_color_scheme_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(16.dp))

            ImeColorScheme.entries.forEach { scheme ->
                val palette = remember(scheme, isDark) {
                    ImePanelTheme.resolvePalette(ctx, scheme, isDark)
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelectScheme(scheme) }
                        .padding(vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = settings.imeColorScheme == scheme,
                        onClick = null
                    )
                    Spacer(Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(scheme.titleRes),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = stringResource(scheme.descriptionRes),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Row(
                        modifier = Modifier
                            .background(Color(palette.panelBg), RoundedCornerShape(16.dp))
                            .border(
                                width = 1.dp,
                                color = MaterialTheme.colorScheme.outlineVariant,
                                shape = RoundedCornerShape(16.dp)
                            )
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(14.dp)
                                .background(Color(palette.keyBg), CircleShape)
                                .border(0.5.dp, Color(palette.keyFg).copy(alpha = 0.2f), CircleShape)
                        )
                        Box(
                            modifier = Modifier
                                .size(14.dp)
                                .background(Color(palette.roundKeyBg), CircleShape)
                        )
                        Box(
                            modifier = Modifier
                                .size(14.dp)
                                .background(Color(palette.micBg), CircleShape)
                        )
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            }
        }
    }
}

@Composable
private fun RecordingModeSubScreen(
    settings: UserSettings,
    onBack: () -> Unit,
    onSelectMode: (RecordInteractionMode) -> Unit,
) {
    SubPageScaffold(
        title = stringResource(R.string.pref_recording_mode),
        onBack = onBack
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            Text(
                text = stringResource(R.string.sub_recording_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(16.dp))

            RecordInteractionMode.entries.forEach { mode ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelectMode(mode) }
                        .padding(vertical = 14.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    RadioButton(
                        selected = settings.interactionMode == mode,
                        onClick = null
                    )
                    Spacer(Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(mode.shortTitleRes),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = stringResource(mode.descriptionRes),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            }
        }
    }
}

@Composable
private fun VadFilterSubScreen(
    settings: UserSettings,
    onBack: () -> Unit,
    onVadEnabledChange: (Boolean) -> Unit,
    onVadThresholdChange: (Float) -> Unit,
) {
    SubPageScaffold(
        title = stringResource(R.string.pref_vad_filter),
        onBack = onBack
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Text(
                text = stringResource(R.string.sub_vad_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onVadEnabledChange(!settings.vadFilterEnabled) }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.sub_vad_enable),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = settings.vadFilterEnabled,
                    onCheckedChange = onVadEnabledChange
                )
            }

            if (settings.vadFilterEnabled) {
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = stringResource(
                            R.string.sub_vad_sensitivity,
                            "%.2f".format(settings.vadThreshold)
                        ),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                    Slider(
                        value = settings.vadThreshold,
                        onValueChange = onVadThresholdChange,
                        valueRange = 0.20f..0.75f,
                    )
                    Text(
                        text = stringResource(R.string.sub_vad_sensitivity_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun DownloadMirrorSubScreen(
    settings: UserSettings,
    onBack: () -> Unit,
    onMirrorChange: (MirrorSource) -> Unit,
    onCustomMirrorUrlChange: (String) -> Unit,
    onImportLocal: () -> Unit,
    onOpenDirectUrlDialog: () -> Unit,
    onAddCatalogUrl: (String) -> Unit,
    onRemoveCatalogUrl: (String) -> Unit,
    onSyncCatalogs: () -> Unit,
) {
    var catalogInput by remember { mutableStateOf("") }

    SubPageScaffold(
        title = stringResource(R.string.pref_download_mirror),
        onBack = onBack
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = stringResource(R.string.sub_mirror_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Text(
                text = stringResource(R.string.mirror_section_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold
            )

            Column {
                MirrorSource.entries.forEach { src ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onMirrorChange(src) }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = settings.mirrorSource == src,
                            onClick = null
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(src.titleRes), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }

            if (settings.mirrorSource == MirrorSource.CUSTOM) {
                OutlinedTextField(
                    value = settings.customMirrorUrl,
                    onValueChange = onCustomMirrorUrlChange,
                    label = { Text(stringResource(R.string.mirror_custom_url_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

            Text(
                text = stringResource(R.string.custom_import_section),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold
            )

            OutlinedButton(onClick = onImportLocal, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.action_import_local_file))
            }

            OutlinedButton(onClick = onOpenDirectUrlDialog, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.action_add_direct_url))
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

            Text(
                text = stringResource(R.string.catalog_section_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = catalogInput,
                    onValueChange = { catalogInput = it },
                    label = { Text(stringResource(R.string.catalog_url_label)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = {
                        if (catalogInput.isNotBlank()) {
                            onAddCatalogUrl(catalogInput)
                            catalogInput = ""
                        }
                    }
                ) {
                    Text(stringResource(R.string.action_subscribe))
                }
            }

            settings.customCatalogUrls.forEach { url ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = url,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    IconButton(onClick = { onRemoveCatalogUrl(url) }) {
                        Icon(Icons.Default.DeleteOutline, contentDescription = stringResource(R.string.action_delete))
                    }
                }
            }

            if (settings.customCatalogUrls.isNotEmpty()) {
                TextButton(onClick = onSyncCatalogs) {
                    Text(stringResource(R.string.action_sync_catalogs))
                }
            }
        }
    }
}

@Composable
private fun PerformanceSubScreen(
    settings: UserSettings,
    engineStatus: EngineRuntimeStatus,
    onBack: () -> Unit,
    onCpuThreadsChange: (Int) -> Unit,
    onIdleUnloadChange: (Int) -> Unit,
    onUnloadNow: () -> Unit,
) {
    val idleOptions = listOf(
        1 to R.string.perf_idle_1m,
        5 to R.string.perf_idle_5m,
        15 to R.string.perf_idle_15m,
        0 to R.string.perf_idle_never,
    )

    SubPageScaffold(
        title = stringResource(R.string.pref_performance),
        onBack = onBack
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Text(
                text = stringResource(R.string.sub_perf_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Column {
                Text(
                    text = stringResource(R.string.perf_cpu_threads, settings.cpuThreads),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                Slider(
                    value = settings.cpuThreads.toFloat(),
                    onValueChange = { onCpuThreadsChange(it.roundToInt()) },
                    valueRange = 1f..8f,
                    steps = 6,
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

            Text(
                text = stringResource(R.string.perf_idle_unload_title),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )

            Column {
                idleOptions.forEach { (mins, labelRes) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onIdleUnloadChange(mins) }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = settings.idleUnloadMinutes == mins,
                            onClick = null
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(labelRes), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }

            if (engineStatus is EngineRuntimeStatus.Ready) {
                OutlinedButton(
                    onClick = onUnloadNow,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.perf_unload_now))
                }
            }
        }
    }
}

@Composable
private fun KeyboardSetupSubScreen(
    imeStatus: SystemImeStatus,
    onBack: () -> Unit,
    onRequestMicPermission: () -> Unit,
    onOpenImeSettings: () -> Unit,
    onShowImePicker: () -> Unit,
    onRerunOnboarding: () -> Unit,
) {
    SubPageScaffold(
        title = stringResource(R.string.pref_keyboard_setup),
        onBack = onBack
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            SetupStatusRow(
                done = imeStatus.hasMicPermission,
                label = stringResource(R.string.onboarding_perm_title),
                onClick = onRequestMicPermission,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

            SetupStatusRow(
                done = imeStatus.isImeEnabled,
                label = stringResource(R.string.onboarding_enable_ime),
                onClick = onOpenImeSettings,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

            SetupStatusRow(
                done = imeStatus.isImeSelected,
                label = stringResource(R.string.onboarding_select_ime),
                onClick = onShowImePicker,
            )

            Spacer(Modifier.height(16.dp))

            OutlinedButton(
                onClick = onRerunOnboarding,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.sub_setup_rerun_onboarding))
            }
        }
    }
}

@Composable
private fun SetupStatusRow(
    done: Boolean,
    label: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (done) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
            contentDescription = null,
            tint = if (done) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outline
        )
        Spacer(Modifier.width(14.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline
        )
    }
}

@Composable
private fun TestInputSubScreen(
    onBack: () -> Unit,
) {
    var text by rememberSaveable { mutableStateOf("") }

    SubPageScaffold(
        title = stringResource(R.string.pref_test_input),
        onBack = onBack
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.sub_test_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text(stringResource(R.string.sub_test_placeholder)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
            )

            if (text.isNotEmpty()) {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                    TextButton(onClick = { text = "" }) {
                        Text(stringResource(R.string.action_clear))
                    }
                }
            }
        }
    }
}

// ---------- Clean Dialogs ----------

@Composable
private fun LocalModelImportDialog(
    onDismiss: () -> Unit,
    onConfirm: (customName: String?, preferredEngine: EngineType?) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var selectedEngine by remember { mutableStateOf<EngineType?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dialog_import_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.dialog_model_name_optional)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { selectedEngine = null },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = selectedEngine == null, onClick = { selectedEngine = null })
                    Text(stringResource(R.string.dialog_engine_auto), style = MaterialTheme.typography.bodyMedium)
                }
                EngineType.entries.forEach { eng ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedEngine = eng },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = selectedEngine == eng, onClick = { selectedEngine = eng })
                        Text(eng.displayName, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(name.takeIf { it.isNotBlank() }, selectedEngine) }) {
                Text(stringResource(R.string.action_next))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
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
        title = { Text(stringResource(R.string.dialog_direct_url_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.dialog_model_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(stringResource(R.string.dialog_model_url)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                EngineType.entries.forEach { eng ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { engine = eng },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = engine == eng, onClick = { engine = eng })
                        Text(eng.displayName, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(name, url, engine) },
                enabled = name.isNotBlank() && url.startsWith("http")
            ) {
                Text(stringResource(R.string.action_download))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}
