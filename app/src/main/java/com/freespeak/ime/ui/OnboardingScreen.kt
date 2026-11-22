package com.freespeak.ime.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.freespeak.ime.R
import com.freespeak.ime.data.MirrorSource
import com.freespeak.ime.data.UserSettings
import com.freespeak.ime.model.BuiltinModelCatalog
import com.freespeak.ime.model.ManagedModelItem
import com.freespeak.ime.model.ModelInstallState
import com.freespeak.ime.model.ModelManifest

@Composable
fun OnboardingScreen(
    imeStatus: SystemImeStatus,
    models: List<ManagedModelItem>,
    settings: UserSettings,
    onRequestMicPermission: () -> Unit,
    onMirrorChange: (MirrorSource) -> Unit,
    onDownloadModel: (ModelManifest) -> Unit,
    onCancelDownload: (String) -> Unit,
    onSelectModel: (String) -> Unit,
    onImportLocalFile: () -> Unit,
    onOpenImeSettings: () -> Unit,
    onShowImePicker: () -> Unit,
    onFinishOnboarding: () -> Unit,
) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    val totalSteps = 4

    BackHandler(enabled = step > 0) {
        step--
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 16.dp)
        ) {
            // Top bar: Step dots + Skip
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    repeat(totalSteps) { index ->
                        Box(
                            modifier = Modifier
                                .height(6.dp)
                                .width(if (index == step) 24.dp else 8.dp)
                                .clip(CircleShape)
                                .background(
                                    if (index <= step) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.surfaceVariant
                                )
                        )
                    }
                }

                TextButton(onClick = onFinishOnboarding) {
                    Text(stringResource(R.string.action_skip))
                }
            }

            Spacer(Modifier.height(24.dp))

            // Main step content
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                when (step) {
                    0 -> WelcomeStepContent()
                    1 -> PermissionStepContent(
                        granted = imeStatus.hasMicPermission,
                        onGrant = onRequestMicPermission,
                    )
                    2 -> ModelStepContent(
                        models = models,
                        settings = settings,
                        onMirrorChange = onMirrorChange,
                        onDownload = onDownloadModel,
                        onCancel = onCancelDownload,
                        onSelect = onSelectModel,
                        onImportLocal = onImportLocalFile,
                    )
                    3 -> EnableImeStepContent(
                        isEnabled = imeStatus.isImeEnabled,
                        isSelected = imeStatus.isImeSelected,
                        onEnable = onOpenImeSettings,
                        onSelect = onShowImePicker,
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // Bottom Navigation Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (step > 0) {
                    TextButton(onClick = { step-- }) {
                        Text(stringResource(R.string.action_back))
                    }
                } else {
                    Spacer(Modifier.width(1.dp))
                }

                Button(
                    onClick = {
                        if (step < totalSteps - 1) {
                            step++
                        } else {
                            onFinishOnboarding()
                        }
                    }
                ) {
                    Text(
                        stringResource(
                            if (step < totalSteps - 1) R.string.action_next else R.string.action_done
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun WelcomeStepContent() {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = stringResource(R.string.onboarding_welcome_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.onboarding_welcome_subtitle),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(36.dp))

        PillarRow(
            icon = Icons.Default.Code,
            title = stringResource(R.string.onboarding_pillar_free),
            subtitle = stringResource(R.string.onboarding_pillar_free_desc),
        )
        Spacer(Modifier.height(24.dp))
        PillarRow(
            icon = Icons.Default.WifiOff,
            title = stringResource(R.string.onboarding_pillar_offline),
            subtitle = stringResource(R.string.onboarding_pillar_offline_desc),
        )
        Spacer(Modifier.height(24.dp))
        PillarRow(
            icon = Icons.Default.Lock,
            title = stringResource(R.string.onboarding_pillar_uncensored),
            subtitle = stringResource(R.string.onboarding_pillar_uncensored_desc),
        )
    }
}

@Composable
private fun PillarRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
) {
    Row(verticalAlignment = Alignment.Top) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(42.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        Spacer(Modifier.width(16.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun PermissionStepContent(
    granted: Boolean,
    onGrant: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.Start
    ) {
        Icon(
            imageVector = if (granted) Icons.Default.CheckCircle else Icons.Default.Mic,
            contentDescription = null,
            tint = if (granted) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(48.dp)
        )
        Spacer(Modifier.height(20.dp))
        Text(
            text = stringResource(R.string.onboarding_perm_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.onboarding_perm_desc),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(32.dp))

        if (granted) {
            Text(
                text = stringResource(R.string.onboarding_perm_granted),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.secondary,
                fontWeight = FontWeight.SemiBold
            )
        } else {
            Button(onClick = onGrant) {
                Text(stringResource(R.string.onboarding_perm_grant))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModelStepContent(
    models: List<ManagedModelItem>,
    settings: UserSettings,
    onMirrorChange: (MirrorSource) -> Unit,
    onDownload: (ModelManifest) -> Unit,
    onCancel: (String) -> Unit,
    onSelect: (String) -> Unit,
    onImportLocal: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = stringResource(R.string.onboarding_model_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.onboarding_model_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(MirrorSource.HF_MIRROR, MirrorSource.MODELSCOPE, MirrorSource.HUGGINGFACE).forEach { src ->
                FilterChip(
                    selected = settings.mirrorSource == src,
                    onClick = { onMirrorChange(src) },
                    label = { Text(stringResource(src.titleRes)) }
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(vertical = 4.dp),
        ) {
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
                    onDelete = null,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            }

            item {
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = onImportLocal,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.onboarding_import_local))
                }
            }
        }
    }
}

@Composable
private fun EnableImeStepContent(
    isEnabled: Boolean,
    isSelected: Boolean,
    onEnable: () -> Unit,
    onSelect: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Keyboard,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(48.dp)
        )
        Spacer(Modifier.height(20.dp))
        Text(
            text = stringResource(R.string.onboarding_ime_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.onboarding_ime_desc),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(32.dp))

        if (isEnabled) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.onboarding_ime_enabled), fontWeight = FontWeight.Medium)
            }
        } else {
            Button(onClick = onEnable, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.onboarding_enable_ime))
            }
        }

        Spacer(Modifier.height(16.dp))

        if (isSelected) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.onboarding_ime_selected), fontWeight = FontWeight.Medium)
            }
        } else {
            OutlinedButton(
                onClick = onSelect,
                enabled = isEnabled,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.onboarding_select_ime))
            }
        }
    }
}

@Composable
internal fun CompactModelRow(
    item: ManagedModelItem,
    isActive: Boolean,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onSelect: () -> Unit,
    onDelete: (() -> Unit)?,
) {
    val manifest = item.manifest
    val descRes = BuiltinModelCatalog.localizedDescriptionRes(manifest.id)
    val descriptionText = if (descRes != null) stringResource(descRes) else manifest.description
    val sizeMb = (manifest.sizeBytes / (1024 * 1024)).toInt()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = manifest.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold
                )
                if (sizeMb > 0) {
                    Text(
                        text = stringResource(R.string.model_size_format, sizeMb, manifest.quantization),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Spacer(Modifier.width(12.dp))

            when (item.state) {
                ModelInstallState.NotInstalled -> {
                    OutlinedButton(onClick = onDownload) {
                        Text(stringResource(R.string.action_download))
                    }
                }
                is ModelInstallState.Downloading -> {
                    TextButton(onClick = onCancel) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }
                ModelInstallState.Extracting -> Unit
                is ModelInstallState.Installed -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (isActive) {
                            Text(
                                text = stringResource(R.string.label_in_use),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.secondary,
                                fontWeight = FontWeight.SemiBold
                            )
                        } else {
                            TextButton(onClick = onSelect) {
                                Text(stringResource(R.string.action_use))
                            }
                        }
                        if (onDelete != null) {
                            Spacer(Modifier.width(4.dp))
                            TextButton(onClick = onDelete) {
                                Text(
                                    text = stringResource(R.string.action_delete),
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
                is ModelInstallState.Error -> {
                    OutlinedButton(onClick = onDownload) {
                        Text(stringResource(R.string.action_retry))
                    }
                }
            }
        }

        Spacer(Modifier.height(4.dp))
        Text(
            text = descriptionText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        when (val st = item.state) {
            is ModelInstallState.Downloading -> {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { st.progress },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(
                        R.string.model_downloading_format,
                        st.currentFile,
                        (st.downloadedBytes / (1024 * 1024)).toInt(),
                        (st.totalBytes / (1024 * 1024)).toInt(),
                        formatSpeedLabel(st.speedBytesPerSec),
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            ModelInstallState.Extracting -> {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.model_extracting),
                    style = MaterialTheme.typography.labelSmall
                )
            }
            is ModelInstallState.Error -> {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = st.message,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            else -> Unit
        }
    }
}

internal fun formatSpeedLabel(bytesPerSec: Long): String {
    if (bytesPerSec <= 0L) return "…"
    val kb = bytesPerSec / 1024.0
    return if (kb >= 1024.0) {
        String.format("%.1f MB/s", kb / 1024.0)
    } else {
        String.format("%.0f KB/s", kb)
    }
}
