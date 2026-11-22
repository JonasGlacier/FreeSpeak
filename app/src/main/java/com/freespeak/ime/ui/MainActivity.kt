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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddLink
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.freespeak.ime.data.MirrorSource
import com.freespeak.ime.data.RecordInteractionMode
import com.freespeak.ime.data.UserSettings
import com.freespeak.ime.engine.EngineRuntimeStatus
import com.freespeak.ime.model.EngineType
import com.freespeak.ime.model.ManagedModelItem
import com.freespeak.ime.model.ModelInstallState
import com.freespeak.ime.ui.theme.FreeSpeakTheme
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FreeSpeakTheme {
                FreeSpeakAppRoot(viewModel = viewModel)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FreeSpeakAppRoot(viewModel: MainViewModel) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val models by viewModel.models.collectAsStateWithLifecycle()
    val imeStatus by viewModel.imeStatus.collectAsStateWithLifecycle()
    val engineStatus by viewModel.engineStatus.collectAsStateWithLifecycle()
    val toastMsg by viewModel.toastMessage.collectAsStateWithLifecycle()

    var currentTab by rememberSaveable { mutableIntStateOf(0) }
    var pendingLocalImportUri by remember { mutableStateOf<Uri?>(null) }
    var showCustomSourceDialog by remember { mutableStateOf(false) }

    val micPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) {
        viewModel.refreshSystemStatus()
    }

    val safImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            pendingLocalImportUri = uri
        }
    }

    // Refresh status on resume and when user switches system input method
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("FreeSpeak 语音输入法", fontWeight = FontWeight.Bold)
                        Text(
                            text = "免费开源 · 100% 本地离线 · 无审查 · 模型可扩展",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = currentTab == 0,
                    onClick = { currentTab = 0 },
                    icon = { Icon(Icons.Default.Home, contentDescription = null) },
                    label = { Text("启用与试说") }
                )
                NavigationBarItem(
                    selected = currentTab == 1,
                    onClick = { currentTab = 1 },
                    icon = { Icon(Icons.Default.Download, contentDescription = null) },
                    label = { Text("模型中心") }
                )
                NavigationBarItem(
                    selected = currentTab == 2,
                    onClick = { currentTab = 2 },
                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    label = { Text("设置") }
                )
            }
        }
    ) { innerPadding ->
        when (currentTab) {
            0 -> HomeSetupScreen(
                padding = innerPadding,
                imeStatus = imeStatus,
                models = models,
                settings = settings,
                engineStatus = engineStatus,
                onRequestMicPermission = { micPermLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                onGoToModelHub = { currentTab = 1 },
                onOpenImeSettings = {
                    context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
                },
                onShowImePicker = {
                    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                    imm?.showInputMethodPicker()
                }
            )

            1 -> ModelHubScreen(
                padding = innerPadding,
                models = models,
                settings = settings,
                onMirrorChange = viewModel::setMirrorSource,
                onCustomMirrorUrlChange = viewModel::setCustomMirrorUrl,
                onDownload = viewModel::downloadModel,
                onCancel = viewModel::cancelDownload,
                onSelectDefault = viewModel::selectActiveModel,
                onDelete = viewModel::deleteModel,
                onTriggerLocalImport = { safImportLauncher.launch(arrayOf("*/*")) },
                onShowCustomSourceDialog = { showCustomSourceDialog = true },
                onRefreshCatalogs = viewModel::syncCatalogsNow,
            )

            2 -> SettingsScreen(
                padding = innerPadding,
                settings = settings,
                engineStatus = engineStatus,
                onInteractionModeChange = viewModel::setInteractionMode,
                onLanguageChange = viewModel::setLanguage,
                onVadEnabledChange = viewModel::setVadEnabled,
                onVadThresholdChange = viewModel::setVadThreshold,
                onCpuThreadsChange = viewModel::setCpuThreads,
                onIdleUnloadChange = viewModel::setIdleUnloadMinutes,
                onWhisperPromptChange = viewModel::setWhisperPromptEnabled,
                onUnloadMemoryNow = viewModel::unloadMemoryNow,
            )
        }
    }

    pendingLocalImportUri?.let { uri ->
        LocalModelImportDialog(
            uri = uri,
            onDismiss = { pendingLocalImportUri = null },
            onConfirm = { customName, preferredEngine ->
                viewModel.importLocalModel(uri, customName, preferredEngine)
                pendingLocalImportUri = null
            }
        )
    }

    if (showCustomSourceDialog) {
        CustomModelSourceDialog(
            subscribedUrls = settings.customCatalogUrls,
            onDismiss = { showCustomSourceDialog = false },
            onAddDirectModel = { name, url, engine ->
                viewModel.addDirectUrlModelAndDownload(name, url, engine)
                showCustomSourceDialog = false
            },
            onAddCatalogUrl = { url ->
                viewModel.addCatalogSubscription(url)
            },
            onRemoveCatalogUrl = viewModel::removeCatalogSubscription,
        )
    }
}

@Composable
private fun HomeSetupScreen(
    padding: PaddingValues,
    imeStatus: SystemImeStatus,
    models: List<ManagedModelItem>,
    settings: UserSettings,
    engineStatus: EngineRuntimeStatus,
    onRequestMicPermission: () -> Unit,
    onGoToModelHub: () -> Unit,
    onOpenImeSettings: () -> Unit,
    onShowImePicker: () -> Unit,
) {
    val installedModels = models.filter { it.state is ModelInstallState.Installed }
    val hasInstalledModel = installedModels.isNotEmpty()
    val activeModel = installedModels.find { it.manifest.id == settings.selectedModelId }
        ?: installedModels.firstOrNull()

    var playgroundText by rememberSaveable { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Privacy & Product Philosophy Card
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Shield,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(32.dp)
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        text = "隐私优先 · 本地推理无审查",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "输入法进程零联网运行，语音数据从不离开手机；原生输出标点符号，支持中英日韩粤混输与开放模型接入。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }

        // 4-Step Onboarding Checklist
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("快速启用指南（4 步完成）", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

                SetupStepRow(
                    stepNumber = 1,
                    title = "授予麦克风权限",
                    subtitle = if (imeStatus.hasMicPermission) "已授权录音权限" else "仅用于本地实时采集语音",
                    done = imeStatus.hasMicPermission,
                    actionLabel = "授权",
                    onAction = onRequestMicPermission,
                )

                HorizontalDivider()

                SetupStepRow(
                    stepNumber = 2,
                    title = "准备本地语音识别小模型",
                    subtitle = if (activeModel != null) {
                        "当前模型：${activeModel.manifest.name}"
                    } else {
                        "推荐下载 Fun-ASR-Nano、Qwen3-ASR-0.6B 或 Whisper Small"
                    },
                    done = hasInstalledModel,
                    actionLabel = if (hasInstalledModel) "管理" else "去下载",
                    onAction = onGoToModelHub,
                )

                HorizontalDivider()

                SetupStepRow(
                    stepNumber = 3,
                    title = "在系统设置中开启 FreeSpeak",
                    subtitle = if (imeStatus.isImeEnabled) "系统已启用 FreeSpeak 语音输入法" else "进入系统「管理键盘」打开 FreeSpeak 开关",
                    done = imeStatus.isImeEnabled,
                    actionLabel = "去开启",
                    onAction = onOpenImeSettings,
                )

                HorizontalDivider()

                SetupStepRow(
                    stepNumber = 4,
                    title = "切换默认输入法为 FreeSpeak",
                    subtitle = if (imeStatus.isImeSelected) "当前正在使用 FreeSpeak 语音输入法" else "弹出输入法选择器切换至 FreeSpeak",
                    done = imeStatus.isImeSelected,
                    actionLabel = "切换键盘",
                    onAction = onShowImePicker,
                )
            }
        }

        // Live IME Playground
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Mic, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text("现场试说测试区", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }

                Text(
                    text = "点击下方输入框弹出语音面板，试试说一句带英文词的长句（例如：「今天下午三点 review 一下 Qwen3 模型的代码，没问题就发版！」）验证标点与中英混输效果：",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                OutlinedTextField(
                    value = playgroundText,
                    onValueChange = { playgroundText = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(128.dp),
                    placeholder = { Text("点击此处唤起键盘开始语音输入…") },
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = when (engineStatus) {
                            EngineRuntimeStatus.Unloaded -> "引擎状态：待机（按需懒加载）"
                            is EngineRuntimeStatus.Loading -> "引擎状态：正在加载 ${engineStatus.modelName}…"
                            is EngineRuntimeStatus.Ready -> "引擎就绪：${engineStatus.modelName}"
                            is EngineRuntimeStatus.Transcribing -> "引擎状态：正在本地转写…"
                            is EngineRuntimeStatus.Error -> "引擎异常：${engineStatus.message}"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f)
                    )
                    if (playgroundText.isNotEmpty()) {
                        TextButton(onClick = { playgroundText = "" }) {
                            Text("清空")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SetupStepRow(
    stepNumber: Int,
    title: String,
    subtitle: String,
    done: Boolean,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (done) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
            contentDescription = null,
            tint = if (done) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(24.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text("$stepNumber. $title", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(8.dp))
        if (!done || stepNumber == 2 || stepNumber == 4) {
            FilledTonalButton(onClick = onAction) {
                Text(actionLabel)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModelHubScreen(
    padding: PaddingValues,
    models: List<ManagedModelItem>,
    settings: UserSettings,
    onMirrorChange: (MirrorSource) -> Unit,
    onCustomMirrorUrlChange: (String) -> Unit,
    onDownload: (com.freespeak.ime.model.ModelManifest) -> Unit,
    onCancel: (String) -> Unit,
    onSelectDefault: (String) -> Unit,
    onDelete: (com.freespeak.ime.model.ModelManifest) -> Unit,
    onTriggerLocalImport: () -> Unit,
    onShowCustomSourceDialog: () -> Unit,
    onRefreshCatalogs: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Mirror Selector & Open Import Actions
        item {
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("下载镜像源与开放模型接入", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        MirrorSource.entries.forEach { src ->
                            FilterChip(
                                selected = settings.mirrorSource == src,
                                onClick = { onMirrorChange(src) },
                                label = { Text(src.label, style = MaterialTheme.typography.labelMedium) }
                            )
                        }
                    }

                    if (settings.mirrorSource == MirrorSource.CUSTOM) {
                        OutlinedTextField(
                            value = settings.customMirrorUrl,
                            onValueChange = onCustomMirrorUrlChange,
                            label = { Text("自定义 HuggingFace 镜像前缀") },
                            placeholder = { Text("https://hf-mirror.com") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilledTonalButton(
                            onClick = onTriggerLocalImport,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("本地导入")
                        }
                        FilledTonalButton(
                            onClick = onShowCustomSourceDialog,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.AddLink, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("接入新模型")
                        }
                        if (settings.customCatalogUrls.isNotEmpty()) {
                            IconButton(onClick = onRefreshCatalogs) {
                                Icon(Icons.Default.Refresh, contentDescription = "刷新外部模型源")
                            }
                        }
                    }
                }
            }
        }

        items(models, key = { it.manifest.id }) { item ->
            val isSelected = item.manifest.id == settings.selectedModelId ||
                (settings.selectedModelId.isBlank() &&
                    item.state is ModelInstallState.Installed &&
                    models.firstOrNull { it.state is ModelInstallState.Installed }?.manifest?.id == item.manifest.id)

            ModelItemCard(
                item = item,
                isDefaultSelected = isSelected && item.state is ModelInstallState.Installed,
                onDownload = { onDownload(item.manifest) },
                onCancel = { onCancel(item.manifest.id) },
                onSelectDefault = { onSelectDefault(item.manifest.id) },
                onDelete = { onDelete(item.manifest) },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModelItemCard(
    item: ManagedModelItem,
    isDefaultSelected: Boolean,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onSelectDefault: () -> Unit,
    onDelete: () -> Unit,
) {
    val manifest = item.manifest
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(manifest.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        text = manifest.engineType.displayName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                if (isDefaultSelected) {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = MaterialTheme.shapes.small
                    ) {
                        Text(
                            text = "当前使用",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            Text(
                text = manifest.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AssistChip(
                    onClick = {},
                    label = {
                        val sizeMb = if (manifest.sizeBytes > 0) "${manifest.sizeBytes / (1024 * 1024)} MB" else "自定义大小"
                        Text("体积 $sizeMb", style = MaterialTheme.typography.labelSmall)
                    }
                )
                AssistChip(
                    onClick = {},
                    label = { Text("量化 ${manifest.quantization}", style = MaterialTheme.typography.labelSmall) }
                )
                AssistChip(
                    onClick = {},
                    label = { Text("建议内存 ${manifest.recommendedRamMb}MB", style = MaterialTheme.typography.labelSmall) }
                )
            }

            when (val st = item.state) {
                ModelInstallState.NotInstalled -> {
                    Button(onClick = onDownload, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("下载离线模型")
                    }
                }

                is ModelInstallState.Downloading -> {
                    LinearProgressIndicator(
                        progress = { st.progress },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val doneMb = st.downloadedBytes / (1024 * 1024)
                        val totalMb = st.totalBytes / (1024 * 1024)
                        val speedStr = formatSpeed(st.speedBytesPerSec)
                        Text(
                            text = "${st.currentFile}: $doneMb / $totalMb MB ($speedStr)",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = onCancel) {
                            Text("暂停/取消")
                        }
                    }
                }

                ModelInstallState.Extracting -> {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text("正在校验并解压模型包…", style = MaterialTheme.typography.labelSmall)
                }

                is ModelInstallState.Installed -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (!isDefaultSelected) {
                            Button(
                                onClick = onSelectDefault,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("设为默认模型")
                            }
                        } else {
                            OutlinedButton(
                                onClick = {},
                                enabled = false,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("已设为默认 (${st.diskBytes / (1024 * 1024)} MB)")
                            }
                        }
                        IconButton(onClick = onDelete) {
                            Icon(
                                imageVector = Icons.Default.DeleteOutline,
                                contentDescription = "删除模型",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }

                is ModelInstallState.Error -> {
                    Text(
                        text = st.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onDownload, modifier = Modifier.weight(1f)) {
                            Text("断点续传重试")
                        }
                        OutlinedButton(onClick = onDelete) {
                            Text("清理缓存")
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SettingsScreen(
    padding: PaddingValues,
    settings: UserSettings,
    engineStatus: EngineRuntimeStatus,
    onInteractionModeChange: (RecordInteractionMode) -> Unit,
    onLanguageChange: (String) -> Unit,
    onVadEnabledChange: (Boolean) -> Unit,
    onVadThresholdChange: (Float) -> Unit,
    onCpuThreadsChange: (Int) -> Unit,
    onIdleUnloadChange: (Int) -> Unit,
    onWhisperPromptChange: (Boolean) -> Unit,
    onUnloadMemoryNow: () -> Unit,
) {
    val languages = listOf(
        "auto" to "自动识别 / 中英混输",
        "zh" to "中文 (普通话)",
        "en" to "English",
        "yue" to "粵語",
        "ja" to "日本語",
        "ko" to "한국어",
        "fr" to "Français",
        "de" to "Deutsch",
        "es" to "Español",
        "ru" to "Русский",
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Recording Interaction Mode
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Keyboard, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text("录音交互模式", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }

                RecordInteractionMode.entries.forEach { mode ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = settings.interactionMode == mode,
                            onClick = { onInteractionModeChange(mode) }
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(mode.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            Text(mode.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }

        // Default Language
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("默认识别语言", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    text = "建议保持「自动识别 / 中英混输」，Qwen3-ASR 与 Fun-ASR-Nano 可直接输出中英夹杂句子与全套标点。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    languages.forEach { (code, label) ->
                        FilterChip(
                            selected = settings.selectedLanguage == code,
                            onClick = { onLanguageChange(code) },
                            label = { Text(label) }
                        )
                    }
                }
            }
        }

        // VAD Silence Filtering (Never splits sentences!)
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Silero VAD 语音检测与静音过滤", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(
                            text = "仅用于过滤无效静音与过长留白、消除静音幻觉；绝不切分句子，整段语音一次性转写以保留自然标点。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = settings.vadFilterEnabled,
                        onCheckedChange = onVadEnabledChange
                    )
                }

                if (settings.vadFilterEnabled) {
                    Text(
                        text = "人声触发阈值：${"%.2f".format(settings.vadThreshold)}（越小越灵敏，嘈杂环境可调高）",
                        style = MaterialTheme.typography.labelMedium
                    )
                    Slider(
                        value = settings.vadThreshold,
                        onValueChange = onVadThresholdChange,
                        valueRange = 0.20f..0.75f,
                    )
                }

                HorizontalDivider()

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Whisper 简体标点与混输引导词", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        Text(
                            text = "使用 Whisper 系列模型时自动注入标点与中英混输提示词",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = settings.whisperStylePromptEnabled,
                        onCheckedChange = onWhisperPromptChange
                    )
                }
            }
        }

        // Lightweight Performance & Idle RAM Unload
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Memory, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text("轻量性能与后台内存回收", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }

                Text("本地推理 CPU 线程数：${settings.cpuThreads} 线程（推荐 3–4 线程兼顾速度与低发热）", style = MaterialTheme.typography.labelMedium)
                Slider(
                    value = settings.cpuThreads.toFloat(),
                    onValueChange = { onCpuThreadsChange(it.roundToInt()) },
                    valueRange = 1f..8f,
                    steps = 6,
                )

                Text("键盘收起后自动释放模型内存", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(1 to "1 分钟", 5 to "5 分钟", 15 to "15 分钟", 0 to "常驻不卸载").forEach { (mins, label) ->
                        FilterChip(
                            selected = settings.idleUnloadMinutes == mins,
                            onClick = { onIdleUnloadChange(mins) },
                            label = { Text(label) }
                        )
                    }
                }

                if (engineStatus is EngineRuntimeStatus.Ready) {
                    OutlinedButton(onClick = onUnloadMemoryNow, modifier = Modifier.fillMaxWidth()) {
                        Text("立即卸载「${engineStatus.modelName}」释放内存")
                    }
                }
            }
        }
    }
}

@Composable
private fun LocalModelImportDialog(
    uri: Uri,
    onDismiss: () -> Unit,
    onConfirm: (customName: String?, preferredEngine: EngineType?) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var selectedEngine by remember { mutableStateOf<EngineType?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("导入本地语音模型") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "支持导入 .bin / .gguf (Whisper)、.onnx 或包含模型权重的 .zip / .tar.gz / .tar.bz2 压缩包（自动识别 model.json 或目录结构）。",
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("模型显示名称（留空则按文件名自动命名）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text("推理引擎（推荐自动识别）：", style = MaterialTheme.typography.labelMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = selectedEngine == null, onClick = { selectedEngine = null })
                    Text("根据模型文件自动识别 (推荐)", style = MaterialTheme.typography.bodySmall)
                }
                EngineType.entries.forEach { eng ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = selectedEngine == eng, onClick = { selectedEngine = eng })
                        Text(eng.displayName, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(name.takeIf { it.isNotBlank() }, selectedEngine) }) {
                Text("开始导入")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

@Composable
private fun CustomModelSourceDialog(
    subscribedUrls: Set<String>,
    onDismiss: () -> Unit,
    onAddDirectModel: (name: String, url: String, engine: EngineType) -> Unit,
    onAddCatalogUrl: (String) -> Unit,
    onRemoveCatalogUrl: (String) -> Unit,
) {
    var tabIdx by remember { mutableIntStateOf(0) }
    var directName by remember { mutableStateOf("") }
    var directUrl by remember { mutableStateOf("") }
    var directEngine by remember { mutableStateOf(EngineType.WHISPER_CPP) }
    var catalogUrl by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("自由接入新模型（无需等待应用更新）") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                TabRow(selectedTabIndex = tabIdx) {
                    Tab(selected = tabIdx == 0, onClick = { tabIdx = 0 }, text = { Text("模型直链下载") })
                    Tab(selected = tabIdx == 1, onClick = { tabIdx = 1 }, text = { Text("订阅 JSON 源") })
                }

                if (tabIdx == 0) {
                    OutlinedTextField(
                        value = directName,
                        onValueChange = { directName = it },
                        label = { Text("模型名称") },
                        placeholder = { Text("例如 Whisper Medium Q5") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = directUrl,
                        onValueChange = { directUrl = it },
                        label = { Text("权重或压缩包 URL (.bin/.gguf/.zip/.tar.gz)") },
                        placeholder = { Text("https://...") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    EngineType.entries.forEach { eng ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = directEngine == eng, onClick = { directEngine = eng })
                            Text(eng.displayName, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                } else {
                    Text(
                        text = "添加社区维护的 JSON 模型清单 URL（ExternalModelCatalog 规范），一键获取最新开源语音模型：",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = catalogUrl,
                            onValueChange = { catalogUrl = it },
                            label = { Text("Catalog JSON URL") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(6.dp))
                        FilledTonalButton(
                            onClick = {
                                if (catalogUrl.isNotBlank()) {
                                    onAddCatalogUrl(catalogUrl)
                                    catalogUrl = ""
                                }
                            }
                        ) {
                            Text("订阅")
                        }
                    }
                    subscribedUrls.forEach { url ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(url, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                            IconButton(onClick = { onRemoveCatalogUrl(url) }) {
                                Icon(Icons.Default.DeleteOutline, contentDescription = "移除")
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (tabIdx == 0) {
                Button(
                    onClick = {
                        if (directName.isNotBlank() && directUrl.startsWith("http")) {
                            onAddDirectModel(directName, directUrl, directEngine)
                        }
                    },
                    enabled = directName.isNotBlank() && directUrl.startsWith("http")
                ) {
                    Text("添加并下载")
                }
            } else {
                TextButton(onClick = onDismiss) {
                    Text("完成")
                }
            }
        },
        dismissButton = {
            if (tabIdx == 0) {
                TextButton(onClick = onDismiss) {
                    Text("取消")
                }
            }
        }
    )
}

private fun formatSpeed(bytesPerSec: Long): String {
    if (bytesPerSec <= 0L) return "连接中"
    val kb = bytesPerSec / 1024.0
    return if (kb >= 1024.0) {
        String.format("%.1f MB/s", kb / 1024.0)
    } else {
        String.format("%.0f KB/s", kb)
    }
}
