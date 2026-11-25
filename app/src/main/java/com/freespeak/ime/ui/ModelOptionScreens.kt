package com.freespeak.ime.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
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

/**
 * Renders the options declared by [ModelOptions.specsFor] for [model]. Used both in the
 * Settings tab ("<Model> Settings") and on Home, so they always stay in sync.
 */
@Composable
internal fun ModelOptionRows(model: ModelManifest, settings: UserSettings, handlers: ModelOptionHandlers) {
    val specs = ModelOptions.specsFor(model, settings)
    val values = settings.optionsFor(model)
    val autoLabel = stringResource(R.string.pref_lang_auto_mixed)
    specs.forEach { spec ->
        when (spec) {
            is ChoiceModelOption -> ValueRow(
                title = stringResource(spec.titleRes),
                value = ModelOptions.labelFor(spec, values.choice(spec), autoLabel),
                onClick = { handlers.onOpenChoice(spec.key) },
            )
            is ToggleModelOption -> {
                val blocker = spec.disabledWhenOn?.let { k -> specs.firstOrNull { it.key == k } as? ToggleModelOption }
                SwitchRow(
                    title = stringResource(spec.titleRes),
                    checked = values.bool(spec),
                    enabled = blocker == null || !values.bool(blocker),
                    onChange = { handlers.onSet(model.id, spec.key, it.toString()) },
                )
            }
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
    SubPageScaffold(stringResource(spec.titleRes), onBack) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            spec.choices.forEach { value ->
                ChoiceRow(ModelOptions.labelFor(spec, value, autoLabel), value == current, onClick = { onSelect(value) })
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
