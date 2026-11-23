package com.freespeak.ime.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.freespeak.ime.R
import com.freespeak.ime.data.SupportedLanguages
import com.freespeak.ime.data.UserSettings
import com.freespeak.ime.model.EngineType
import com.freespeak.ime.model.ModelManifest
import com.freespeak.ime.model.canHintLanguage
import com.freespeak.ime.model.canTranslateToEnglish
import com.freespeak.ime.text.ChineseOutputMode

internal fun ChineseOutputMode.titleRes(): Int = when (this) {
    ChineseOutputMode.SYSTEM -> R.string.chinese_system
    ChineseOutputMode.SIMPLIFIED -> R.string.chinese_simplified
    ChineseOutputMode.TRADITIONAL -> R.string.chinese_traditional
    ChineseOutputMode.TAIWAN -> R.string.chinese_taiwan
    ChineseOutputMode.HONG_KONG -> R.string.chinese_hong_kong
    ChineseOutputMode.ORIGINAL -> R.string.chinese_original
}

@Composable
internal fun LanguageSettingsScreen(
    settings: UserSettings,
    model: ModelManifest?,
    onBack: () -> Unit,
    onHintChange: (String) -> Unit,
    onTranslationChange: (Boolean) -> Unit,
    onWhisperPromptChange: (Boolean) -> Unit,
) {
    SubPageScaffold(stringResource(R.string.pref_preferred_languages), onBack) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp)) {
            Text(stringResource(R.string.language_hint_description), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(20.dp))
            if (model?.canTranslateToEnglish == true) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.translate_english_title), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.translate_english_description), style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(settings.translateToEnglish, onTranslationChange)
                }
                HorizontalDivider(Modifier.padding(vertical = 20.dp))
            }
            if (model?.canHintLanguage != true) {
                Text(stringResource(R.string.language_builtin_description), color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                OptionRow(stringResource(R.string.language_auto), settings.spokenLanguageHint == "auto") { onHintChange("auto") }
                SupportedLanguages.all.filter { model.languages.isEmpty() || it.code in model.languages }.forEach { language ->
                    OptionRow(language.nativeLabel, settings.spokenLanguageHint == language.code) { onHintChange(language.code) }
                }
            }
            if (model?.engineType == EngineType.WHISPER_CPP) {
                HorizontalDivider(Modifier.padding(vertical = 16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.sub_language_whisper_prompt), Modifier.weight(1f))
                    Switch(settings.whisperStylePromptEnabled, onWhisperPromptChange,
                        enabled = !(model.canTranslateToEnglish && settings.translateToEnglish))
                }
            }
        }
    }
}

@Composable
internal fun ChineseOutputScreen(mode: ChineseOutputMode, onBack: () -> Unit, onSelect: (ChineseOutputMode) -> Unit) {
    SubPageScaffold(stringResource(R.string.chinese_output_title), onBack) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp)) {
            Text(stringResource(R.string.chinese_output_description), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(20.dp))
            ChineseOutputMode.entries.forEach { option ->
                OptionRow(stringResource(option.titleRes()), mode == option) { onSelect(option) }
            }
        }
    }
}

@Composable
private fun OptionRow(title: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().selectable(selected, role = Role.RadioButton, onClick = onClick).padding(vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, onClick = null)
        Spacer(Modifier.width(16.dp))
        Text(title, style = MaterialTheme.typography.bodyLarge)
    }
}
