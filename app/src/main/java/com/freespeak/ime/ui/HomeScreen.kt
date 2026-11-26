package com.freespeak.ime.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Eject
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.freespeak.ime.R
import com.freespeak.ime.data.UserSettings
import com.freespeak.ime.engine.EngineRuntimeStatus
import com.freespeak.ime.model.ManagedModelItem
import com.freespeak.ime.ui.theme.NothingRed

@Composable
internal fun HomeScreen(
    settings: UserSettings,
    activeModel: ManagedModelItem?,
    engineStatus: EngineRuntimeStatus,
    imeStatus: SystemImeStatus,
    contentPadding: PaddingValues,
    onRequestMicPermission: () -> Unit,
    onOpenImeSettings: () -> Unit,
    onShowImePicker: () -> Unit,
    onOpenModels: () -> Unit,
    onUnload: () -> Unit,
    modelOptionHandlers: ModelOptionHandlers,
    onNavigate: (SettingsSubPage) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .imePadding()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PageTitle(
            stringResource(R.string.app_name),
            Modifier.padding(start = 4.dp, top = 28.dp, bottom = 12.dp),
            accentDot = true,
        )

        if (!imeStatus.isFullyReady) {
            Panel(contentPadding = PaddingValues(vertical = 6.dp)) {
                SetupSteps(imeStatus, onRequestMicPermission, onOpenImeSettings, onShowImePicker)
            }
        }

        ModelPanel(activeModel, engineStatus, onOpenModels, onUnload)

        val chineseRow: @Composable () -> Unit = {
            ValueRow(
                title = stringResource(R.string.chinese_output_title),
                value = stringResource(settings.chineseOutputMode.titleRes()),
                onClick = { onNavigate(SettingsSubPage.CHINESE_OUTPUT) },
            )
        }
        val optionRows = activeModel?.manifest?.let { modelOptionRows(it, settings, modelOptionHandlers) }.orEmpty()
        RowGroup(horizontalPadding = 0.dp, rows = optionRows + chineseRow)

        TestField()
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun ModelPanel(
    item: ManagedModelItem?,
    engineStatus: EngineRuntimeStatus,
    onOpenModels: () -> Unit,
    onUnload: () -> Unit,
) {
    val id = item?.manifest?.id
    val (statusRes, live) = when (engineStatus) {
        is EngineRuntimeStatus.Ready -> if (engineStatus.modelId == id) R.string.model_state_ready to true else R.string.model_state_unloaded to false
        is EngineRuntimeStatus.Loading -> R.string.model_state_loading to true
        is EngineRuntimeStatus.Transcribing -> R.string.model_state_busy to true
        is EngineRuntimeStatus.Error -> R.string.model_state_error to true
        EngineRuntimeStatus.Unloaded -> R.string.model_state_unloaded to false
    }
    val pulsing = engineStatus is EngineRuntimeStatus.Loading || engineStatus is EngineRuntimeStatus.Transcribing
    val pulse = rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 1f,
        targetValue = 0.25f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "pulseAlpha",
    )

    Panel(onClick = onOpenModels, contentPadding = PaddingValues(start = 20.dp, end = 8.dp, top = 20.dp, bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 12.dp)) {
            MonoLabel(stringResource(R.string.label_model), Modifier.weight(1f))
            if (item != null) {
                Dot(
                    if (live) NothingRed else MaterialTheme.colorScheme.outline,
                    modifier = Modifier.alpha(if (pulsing) pulse.value else 1f),
                )
                Spacer(Modifier.width(8.dp))
                MonoLabel(stringResource(statusRes), color = if (engineStatus is EngineRuntimeStatus.Error) NothingRed else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(20.dp))
        Text(
            text = item?.manifest?.name ?: stringResource(R.string.pref_voice_model_none),
            style = MaterialTheme.typography.headlineSmall,
            color = if (item != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(end = 12.dp),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Readout(item?.let { modelReadout(it) } ?: "", Modifier.weight(1f))
            if (item == null) {
                IconAction(Icons.Outlined.Add, stringResource(R.string.nav_models), onOpenModels)
            } else {
                if (engineStatus is EngineRuntimeStatus.Ready && engineStatus.modelId == id) {
                    IconAction(Icons.Outlined.Eject, stringResource(R.string.perf_unload_now), onUnload)
                }
                IconAction(Icons.Outlined.SwapHoriz, stringResource(R.string.action_switch_model), onOpenModels)
            }
        }
    }
}

@Composable
private fun TestField() {
    var text by rememberSaveable { mutableStateOf("") }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        placeholder = { Text(stringResource(R.string.sub_test_placeholder), color = MaterialTheme.colorScheme.onSurfaceVariant) },
        trailingIcon = if (text.isNotEmpty()) {
            { IconAction(Icons.Outlined.Close, stringResource(R.string.action_clear), { text = "" }) }
        } else null,
        shape = RoundedCornerShape(24.dp),
        colors = monoFieldColors(),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 160.dp),
    )
}
