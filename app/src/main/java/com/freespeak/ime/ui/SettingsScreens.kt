package com.freespeak.ime.ui

import android.content.Context
import android.view.inputmethod.InputMethodInfo
import android.view.inputmethod.InputMethodManager
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Eject
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.freespeak.ime.R
import com.freespeak.ime.data.ImeColorScheme
import com.freespeak.ime.data.MirrorSource
import com.freespeak.ime.data.RecordInteractionMode
import com.freespeak.ime.data.UserSettings
import com.freespeak.ime.engine.EngineRuntimeStatus
import com.freespeak.ime.ime.ImePanelTheme
import com.freespeak.ime.model.ModelManifest
import com.freespeak.ime.ui.theme.FreeSpeakType
import com.freespeak.ime.ui.theme.NothingRed
import kotlin.math.roundToInt

enum class SettingsSubPage {
    PREFERRED_LANGUAGES,
    MODEL_SETTINGS,
    MODEL_CHOICE,
    CHINESE_OUTPUT,
    TEXT_NORMALIZATION,
    RECORDING_MODE,
    COLOR_SCHEME,
    TARGET_IME,
    VAD_FILTER,
    DOWNLOAD_MIRROR,
    CATALOGS,
    PERFORMANCE,
    KEYBOARD_SETUP,
}

// ---------------------------------------------------------------- shared scaffolding

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SubPageScaffold(
    title: String,
    onBack: () -> Unit,
    actions: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconAction(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.action_back), onBack)
                },
                actions = { actions() },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        content = content,
    )
}

@Composable
internal fun ChoiceRow(title: String, selected: Boolean, subtitle: String? = null, onClick: () -> Unit, trailing: @Composable (() -> Unit)? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SelectDot(selected)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        trailing?.invoke()
    }
}

@Composable
internal fun SwitchRow(title: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = RowHeight)
            .clickable(enabled = enabled) { onChange(!checked) }
            .padding(start = 20.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
        )
        Spacer(Modifier.width(8.dp))
        MonoSwitch(checked, enabled, onChange)
    }
}

/** Compact black-and-white switch (inverted in dark mode). Red stays reserved for status. */
@Composable
internal fun MonoSwitch(checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Switch(
        checked = checked,
        onCheckedChange = onChange,
        enabled = enabled,
        modifier = Modifier.scale(0.8f),
        colors = SwitchDefaults.colors(
            checkedTrackColor = cs.onSurface,
            checkedThumbColor = cs.surfaceContainer,
            checkedBorderColor = cs.onSurface,
            uncheckedTrackColor = cs.surfaceContainerHighest,
            uncheckedThumbColor = cs.outline,
            uncheckedBorderColor = Color.Transparent,
            disabledCheckedTrackColor = cs.outline,
            disabledCheckedThumbColor = cs.surfaceContainer,
            disabledCheckedBorderColor = Color.Transparent,
            disabledUncheckedTrackColor = cs.surfaceContainerHigh,
            disabledUncheckedThumbColor = cs.outlineVariant,
            disabledUncheckedBorderColor = Color.Transparent,
        ),
    )
}

@Composable
internal fun SectionLabel(text: String) {
    MonoLabel(text, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 28.dp, bottom = 6.dp))
}

@Composable
private fun monoSliderColors() = SliderDefaults.colors(
    thumbColor = MaterialTheme.colorScheme.onSurface,
    activeTrackColor = MaterialTheme.colorScheme.onSurface,
    inactiveTrackColor = MaterialTheme.colorScheme.outlineVariant,
    activeTickColor = MaterialTheme.colorScheme.surface,
    inactiveTickColor = MaterialTheme.colorScheme.outline,
)

// ---------------------------------------------------------------- settings tab

@Composable
internal fun SettingsScreen(
    settings: UserSettings,
    activeModel: ModelManifest?,
    imeStatus: SystemImeStatus,
    contentPadding: PaddingValues,
    onNavigate: (SettingsSubPage) -> Unit,
) {
    val ctx = LocalContext.current
    val enabledMethods = rememberEnabledInputMethods()
    val targetImeLabel = enabledMethods.firstOrNull { it.id == settings.targetImeId }
        ?.loadLabel(ctx.packageManager)?.toString()
    val idleLabel = if (settings.idleUnloadMinutes <= 0) {
        stringResource(R.string.idle_never)
    } else {
        stringResource(R.string.idle_minutes, settings.idleUnloadMinutes)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
    ) {
        PageTitle(stringResource(R.string.settings_title), Modifier.padding(start = 20.dp, end = 20.dp, top = 28.dp, bottom = 4.dp))

        if (activeModel != null && hasModelOptions(activeModel, settings)) {
            SectionLabel(stringResource(R.string.label_model))
            val summary = modelOptionSummary(activeModel, settings)
            RowGroup(rows = listOf {
                ValueRow(activeModel.name, summary, onClick = { onNavigate(SettingsSubPage.MODEL_SETTINGS) })
            })
        }

        SectionLabel(stringResource(R.string.section_general))
        RowGroup(rows = listOf(
            {
                ValueRow(
                    title = stringResource(R.string.itn_title),
                    value = stringResource(if (settings.itnEnabled) R.string.state_on else R.string.state_off),
                    onClick = { onNavigate(SettingsSubPage.TEXT_NORMALIZATION) },
                )
            },
            {
                ValueRow(
                    title = stringResource(R.string.chinese_output_title),
                    value = stringResource(settings.chineseOutputMode.titleRes()),
                    onClick = { onNavigate(SettingsSubPage.CHINESE_OUTPUT) },
                )
            },
            {
                ValueRow(
                    title = stringResource(R.string.pref_recording_mode),
                    value = stringResource(settings.interactionMode.shortTitleRes),
                    onClick = { onNavigate(SettingsSubPage.RECORDING_MODE) },
                )
            },
            {
                ValueRow(
                    title = stringResource(R.string.pref_vad_filter),
                    value = stringResource(if (settings.vadFilterEnabled) R.string.state_on else R.string.state_off),
                    onClick = { onNavigate(SettingsSubPage.VAD_FILTER) },
                )
            },
        ))

        SectionLabel(stringResource(R.string.section_keyboard))
        RowGroup(rows = listOf(
            {
                ValueRow(
                    title = stringResource(R.string.pref_color_scheme),
                    value = stringResource(settings.imeColorScheme.titleRes),
                    onClick = { onNavigate(SettingsSubPage.COLOR_SCHEME) },
                )
            },
            {
                ValueRow(
                    title = stringResource(R.string.pref_target_ime),
                    value = targetImeLabel ?: stringResource(R.string.pref_target_ime_none),
                    onClick = { onNavigate(SettingsSubPage.TARGET_IME) },
                )
            },
            {
                ValueRow(
                    title = stringResource(R.string.pref_keyboard_setup),
                    value = stringResource(if (imeStatus.isFullyReady) R.string.setup_status_ready else R.string.setup_status_incomplete),
                    valueColor = if (imeStatus.isFullyReady) MaterialTheme.colorScheme.onSurfaceVariant else NothingRed,
                    onClick = { onNavigate(SettingsSubPage.KEYBOARD_SETUP) },
                )
            },
        ))

        SectionLabel(stringResource(R.string.section_system))
        RowGroup(rows = listOf(
            {
                ValueRow(
                    title = stringResource(R.string.pref_download_mirror),
                    value = stringResource(settings.mirrorSource.titleRes),
                    onClick = { onNavigate(SettingsSubPage.DOWNLOAD_MIRROR) },
                )
            },
            {
                ValueRow(
                    title = stringResource(R.string.pref_performance),
                    value = stringResource(R.string.pref_performance_summary, settings.cpuThreads, idleLabel),
                    onClick = { onNavigate(SettingsSubPage.PERFORMANCE) },
                )
            },
        ))
        Spacer(Modifier.height(24.dp))
    }
}

// ---------------------------------------------------------------- sub pages

@Composable
internal fun rememberEnabledInputMethods(): List<InputMethodInfo> {
    val ctx = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val imm = remember(ctx) { ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager }
    var methods by remember(imm) { mutableStateOf(imm.enabledInputMethodList) }
    DisposableEffect(lifecycleOwner, imm) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) methods = imm.enabledInputMethodList
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return methods
}

@Composable
internal fun TargetImeSubScreen(settings: UserSettings, onBack: () -> Unit, onSelect: (String) -> Unit) {
    val ctx = LocalContext.current
    val methods = rememberEnabledInputMethods().filter { it.packageName != ctx.packageName }
    SubPageScaffold(stringResource(R.string.pref_target_ime), onBack) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            ChoiceRow(stringResource(R.string.pref_target_ime_none), settings.targetImeId.isEmpty(), onClick = { onSelect("") })
            methods.forEach { info ->
                ChoiceRow(info.loadLabel(ctx.packageManager).toString(), settings.targetImeId == info.id, onClick = { onSelect(info.id) })
            }
        }
    }
}

@Composable
internal fun ColorSchemeSubScreen(settings: UserSettings, onBack: () -> Unit, onSelectScheme: (ImeColorScheme) -> Unit) {
    val ctx = LocalContext.current
    val isDark = isSystemInDarkTheme()
    SubPageScaffold(stringResource(R.string.pref_color_scheme), onBack) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            ImeColorScheme.entries.forEach { scheme ->
                val palette = remember(scheme, isDark) { ImePanelTheme.resolvePalette(ctx, scheme, isDark) }
                ChoiceRow(
                    title = stringResource(scheme.titleRes),
                    selected = settings.imeColorScheme == scheme,
                    onClick = { onSelectScheme(scheme) },
                    trailing = {
                        Row(
                            modifier = Modifier
                                .background(Color(palette.panelBg), RoundedCornerShape(16.dp))
                                .border(0.5.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp))
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            Box(Modifier.size(14.dp).background(Color(palette.keyBg), CircleShape))
                            Box(Modifier.size(14.dp).background(Color(palette.roundKeyBg), CircleShape))
                            Box(Modifier.size(14.dp).background(Color(palette.micBg), CircleShape))
                        }
                    },
                )
            }
        }
    }
}

@Composable
internal fun RecordingModeSubScreen(settings: UserSettings, onBack: () -> Unit, onSelectMode: (RecordInteractionMode) -> Unit) {
    SubPageScaffold(stringResource(R.string.pref_recording_mode), onBack) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            RecordInteractionMode.entries.forEach { mode ->
                ChoiceRow(
                    title = stringResource(mode.shortTitleRes),
                    subtitle = stringResource(mode.descriptionRes),
                    selected = settings.interactionMode == mode,
                    onClick = { onSelectMode(mode) },
                )
            }
        }
    }
}

@Composable
internal fun VadFilterSubScreen(
    settings: UserSettings,
    onBack: () -> Unit,
    onVadEnabledChange: (Boolean) -> Unit,
    onVadThresholdChange: (Float) -> Unit,
) {
    SubPageScaffold(stringResource(R.string.pref_vad_filter), onBack) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            SwitchRow(stringResource(R.string.sub_vad_enable), settings.vadFilterEnabled, onChange = onVadEnabledChange)
            if (settings.vadFilterEnabled) {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                    Text(
                        stringResource(R.string.sub_vad_sensitivity, "%.2f".format(settings.vadThreshold)),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Slider(
                        value = settings.vadThreshold,
                        onValueChange = onVadThresholdChange,
                        valueRange = 0.20f..0.75f,
                        colors = monoSliderColors(),
                    )
                }
            }
        }
    }
}

@Composable
internal fun DownloadMirrorSubScreen(
    settings: UserSettings,
    onBack: () -> Unit,
    onMirrorChange: (MirrorSource) -> Unit,
    onCustomMirrorUrlChange: (String) -> Unit,
) {
    SubPageScaffold(stringResource(R.string.pref_download_mirror), onBack) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            MirrorSource.entries.forEach { src ->
                ChoiceRow(stringResource(src.titleRes), settings.mirrorSource == src, onClick = { onMirrorChange(src) })
            }
            if (settings.mirrorSource == MirrorSource.CUSTOM) {
                MonoTextField(
                    value = settings.customMirrorUrl,
                    onValueChange = onCustomMirrorUrlChange,
                    placeholder = stringResource(R.string.mirror_custom_url_label),
                    keyboardType = KeyboardType.Uri,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
internal fun CatalogsSubScreen(
    settings: UserSettings,
    onBack: () -> Unit,
    onAddCatalogUrl: (String) -> Unit,
    onRemoveCatalogUrl: (String) -> Unit,
    onSyncCatalogs: () -> Unit,
) {
    var input by remember { mutableStateOf("") }
    SubPageScaffold(
        title = stringResource(R.string.catalog_section_title),
        onBack = onBack,
        actions = {
            if (settings.customCatalogUrls.isNotEmpty()) {
                IconAction(Icons.Outlined.Refresh, stringResource(R.string.action_sync_catalogs), onSyncCatalogs)
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            Row(Modifier.padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                MonoTextField(
                    value = input,
                    onValueChange = { input = it },
                    placeholder = stringResource(R.string.catalog_url_label),
                    keyboardType = KeyboardType.Uri,
                    modifier = Modifier.weight(1f),
                )
                IconAction(
                    Icons.Outlined.Add,
                    stringResource(R.string.action_subscribe),
                    onClick = {
                        if (input.isNotBlank()) {
                            onAddCatalogUrl(input)
                            input = ""
                        }
                    },
                    enabled = input.startsWith("http"),
                )
            }
            settings.customCatalogUrls.forEach { url ->
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Readout(url, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface)
                    IconAction(Icons.Outlined.DeleteOutline, stringResource(R.string.action_delete), { onRemoveCatalogUrl(url) },
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HairlineDivider(Modifier.padding(horizontal = 20.dp))
            }
        }
    }
}

@Composable
internal fun PerformanceSubScreen(
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
        onBack = onBack,
        actions = {
            if (engineStatus is EngineRuntimeStatus.Ready) {
                IconAction(Icons.Outlined.Eject, stringResource(R.string.perf_unload_now), onUnloadNow)
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                Text(stringResource(R.string.perf_cpu_threads, settings.cpuThreads), style = MaterialTheme.typography.bodyLarge)
                Slider(
                    value = settings.cpuThreads.toFloat(),
                    onValueChange = { onCpuThreadsChange(it.roundToInt()) },
                    valueRange = 1f..8f,
                    steps = 6,
                    colors = monoSliderColors(),
                )
            }
            SectionLabel(stringResource(R.string.perf_idle_unload_title))
            idleOptions.forEach { (mins, labelRes) ->
                ChoiceRow(stringResource(labelRes), settings.idleUnloadMinutes == mins, onClick = { onIdleUnloadChange(mins) })
            }
        }
    }
}

@Composable
internal fun KeyboardSetupSubScreen(
    imeStatus: SystemImeStatus,
    onBack: () -> Unit,
    onRequestMicPermission: () -> Unit,
    onOpenImeSettings: () -> Unit,
    onShowImePicker: () -> Unit,
    onRerunOnboarding: () -> Unit,
) {
    SubPageScaffold(
        title = stringResource(R.string.pref_keyboard_setup),
        onBack = onBack,
        actions = { IconAction(Icons.Outlined.Replay, stringResource(R.string.sub_setup_rerun_onboarding), onRerunOnboarding) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            SetupSteps(imeStatus, onRequestMicPermission, onOpenImeSettings, onShowImePicker)
        }
    }
}

@Composable
internal fun SetupSteps(
    imeStatus: SystemImeStatus,
    onRequestMicPermission: () -> Unit,
    onOpenImeSettings: () -> Unit,
    onShowImePicker: () -> Unit,
    horizontalPadding: androidx.compose.ui.unit.Dp = 20.dp,
) {
    SetupStepRow(Icons.Outlined.Mic, stringResource(R.string.setup_mic), imeStatus.hasMicPermission, onRequestMicPermission, horizontalPadding)
    SetupStepRow(Icons.Outlined.Keyboard, stringResource(R.string.setup_enable), imeStatus.isImeEnabled, onOpenImeSettings, horizontalPadding)
    SetupStepRow(Icons.Outlined.TouchApp, stringResource(R.string.setup_select), imeStatus.isImeSelected, onShowImePicker, horizontalPadding)
}

@Composable
private fun SetupStepRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, done: Boolean, onClick: () -> Unit, hPad: androidx.compose.ui.unit.Dp) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !done, onClick = onClick)
            .padding(horizontal = hPad, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(16.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        if (done) {
            Icon(Icons.Outlined.CheckCircle, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Icon(Icons.Outlined.RadioButtonUnchecked, contentDescription = null, modifier = Modifier.size(20.dp), tint = NothingRed)
        }
    }
}

@Composable
internal fun MonoTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = { Text(placeholder, style = FreeSpeakType.mono.readout) },
        textStyle = FreeSpeakType.mono.readout.copy(color = MaterialTheme.colorScheme.onSurface),
        singleLine = true,
        shape = RoundedCornerShape(16.dp),
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Done),
        colors = monoFieldColors(),
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
internal fun monoFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = MaterialTheme.colorScheme.onSurface,
    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
    cursorColor = NothingRed,
)
