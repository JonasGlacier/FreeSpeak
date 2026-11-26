package com.freespeak.ime.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.freespeak.ime.R
import com.freespeak.ime.data.UserSettings
import com.freespeak.ime.model.ChoiceModelOption
import com.freespeak.ime.model.ModelManifest
import com.freespeak.ime.model.ModelOptions
import com.freespeak.ime.model.ToggleModelOption
import com.freespeak.ime.model.optionsFor
import com.freespeak.ime.text.ChineseOutputMode

internal fun ChineseOutputMode.titleRes(): Int = when (this) {
    ChineseOutputMode.SYSTEM -> R.string.chinese_system
    ChineseOutputMode.SIMPLIFIED -> R.string.chinese_simplified
    ChineseOutputMode.TRADITIONAL -> R.string.chinese_traditional
    ChineseOutputMode.TAIWAN -> R.string.chinese_taiwan
    ChineseOutputMode.HONG_KONG -> R.string.chinese_hong_kong
    ChineseOutputMode.ORIGINAL -> R.string.chinese_original
}

/** Callbacks shared by every place that renders model-specific options. */
internal class ModelOptionHandlers(
    val onSet: (modelId: String, key: String, value: String) -> Unit,
    val onOpenChoice: (key: String) -> Unit,
)

/** One row per declared option, ready to be placed in a [RowGroup]. Used on Home and in the model page. */
@Composable
internal fun modelOptionRows(model: ModelManifest, settings: UserSettings, handlers: ModelOptionHandlers): List<@Composable () -> Unit> {
    val specs = ModelOptions.specsFor(model, settings)
    val values = settings.optionsFor(model)
    val autoLabel = stringResource(R.string.pref_lang_auto_mixed)
    val sameAsSourceLabel = stringResource(R.string.canary_same_as_source)
    return specs.map { spec ->
        when (spec) {
            is ChoiceModelOption -> choiceRow(spec, values.choice(spec), autoLabel, sameAsSourceLabel, handlers)
            is ToggleModelOption -> {
                val blocker = spec.disabledWhenOn?.let { k -> specs.firstOrNull { it.key == k } as? ToggleModelOption }
                toggleRow(spec, values.bool(spec), blocker == null || !values.bool(blocker), model.id, handlers)
            }
        }
    }
}

private fun choiceRow(
    spec: ChoiceModelOption,
    value: String,
    autoLabel: String,
    sameAsSourceLabel: String,
    handlers: ModelOptionHandlers,
): @Composable () -> Unit = {
    ValueRow(
        title = stringResource(spec.titleRes),
        value = ModelOptions.labelFor(spec, value, autoLabel, sameAsSourceLabel),
        onClick = { handlers.onOpenChoice(spec.key) },
    )
}

private fun toggleRow(
    spec: ToggleModelOption,
    checked: Boolean,
    enabled: Boolean,
    modelId: String,
    handlers: ModelOptionHandlers,
): @Composable () -> Unit = {
    SwitchRow(
        title = stringResource(spec.titleRes),
        checked = checked,
        enabled = enabled,
        onChange = { handlers.onSet(modelId, spec.key, it.toString()) },
    )
}

/** Compact readout for the collapsed model row in Settings, e.g. "中文 · 2/3". */
@Composable
internal fun modelOptionSummary(model: ModelManifest, settings: UserSettings): String {
    val specs = ModelOptions.specsFor(model, settings)
    val values = settings.optionsFor(model)
    val autoLabel = stringResource(R.string.pref_lang_auto_mixed)
    val sameAsSourceLabel = stringResource(R.string.canary_same_as_source)
    val choices = specs.filterIsInstance<ChoiceModelOption>()
        .map { ModelOptions.labelFor(it, values.choice(it), autoLabel, sameAsSourceLabel) }
    val toggles = specs.filterIsInstance<ToggleModelOption>()
    val toggleSummary = toggles.takeIf { it.isNotEmpty() }?.let { t -> "${t.count { values.bool(it) }}/${t.size}" }
    return (choices + listOfNotNull(toggleSummary)).joinToString(" · ")
}

@Composable
internal fun ModelSettingsScreen(
    model: ModelManifest,
    settings: UserSettings,
    handlers: ModelOptionHandlers,
    onBack: () -> Unit,
) {
    SubPageScaffold(stringResource(R.string.model_settings_title, model.name), onBack) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(top = 8.dp, bottom = 24.dp)
        ) {
            RowGroup(rows = modelOptionRows(model, settings, handlers))
        }
    }
}

internal fun hasModelOptions(model: ModelManifest?, settings: UserSettings): Boolean =
    model != null && ModelOptions.specsFor(model, settings).isNotEmpty()

@Composable
internal fun ModelChoiceScreen(
    model: ModelManifest,
    optionKey: String,
    settings: UserSettings,
    onBack: () -> Unit,
    onSelect: (String) -> Unit,
) {
    val spec = ModelOptions.specsFor(model, settings).firstOrNull { it.key == optionKey } as? ChoiceModelOption
    if (spec == null) {
        androidx.compose.runtime.LaunchedEffect(Unit) { onBack() }
        return
    }
    val current = settings.optionsFor(model).choice(spec)
    val autoLabel = stringResource(R.string.language_auto)
    val sameAsSourceLabel = stringResource(R.string.canary_same_as_source)
    SubPageScaffold(stringResource(spec.titleRes), onBack) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            spec.choices.forEach { value ->
                ChoiceRow(ModelOptions.labelFor(spec, value, autoLabel, sameAsSourceLabel), value == current, onClick = { onSelect(value) })
            }
        }
    }
}

@Composable
internal fun ChineseOutputScreen(mode: ChineseOutputMode, onBack: () -> Unit, onSelect: (ChineseOutputMode) -> Unit) {
    SubPageScaffold(stringResource(R.string.chinese_output_title), onBack) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            ChineseOutputMode.entries.forEach { option ->
                ChoiceRow(stringResource(option.titleRes()), mode == option, onClick = { onSelect(option) })
            }
        }
    }
}
