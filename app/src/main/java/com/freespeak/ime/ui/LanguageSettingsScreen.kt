package com.freespeak.ime.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
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
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            if (model?.canTranslateToEnglish == true) {
                SwitchRow(stringResource(R.string.translate_english_title), settings.translateToEnglish, onChange = onTranslationChange)
            }
            if (model?.engineType == EngineType.WHISPER_CPP) {
                SwitchRow(
                    stringResource(R.string.sub_language_whisper_prompt),
                    settings.whisperStylePromptEnabled,
                    enabled = !(model.canTranslateToEnglish && settings.translateToEnglish),
                    onChange = onWhisperPromptChange,
                )
            }
            if (model?.canHintLanguage != true) {
                Text(
                    stringResource(R.string.language_builtin_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                )
            } else {
                if (model.engineType == EngineType.WHISPER_CPP) HairlineDivider(Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                ChoiceRow(stringResource(R.string.language_auto), settings.spokenLanguageHint == "auto", onClick = { onHintChange("auto") })
                SupportedLanguages.all.filter { model.languages.isEmpty() || it.code in model.languages }.forEach { language ->
                    ChoiceRow(language.nativeLabel, settings.spokenLanguageHint == language.code, onClick = { onHintChange(language.code) })
                }
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
