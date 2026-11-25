package com.freespeak.ime.model

import androidx.annotation.StringRes
import com.freespeak.ime.R
import com.freespeak.ime.data.SupportedLanguages
import com.freespeak.ime.data.UserSettings

/**
 * Declarative, per-model settings. Each model exposes only the options its engine actually
 * supports; values are stored per model id. New engine features are added by declaring a new
 * option here and reading it via [ResolvedModelOptions].
 */
sealed interface ModelOption {
    val key: String
    @get:StringRes val titleRes: Int
}

/** Single choice from [choices] (raw values; labels are resolved by the UI via [labelFor]). */
data class ChoiceModelOption(
    override val key: String,
    override val titleRes: Int,
    val choices: List<String>,
    val default: String,
) : ModelOption

data class ToggleModelOption(
    override val key: String,
    override val titleRes: Int,
    val default: Boolean,
    /** When the toggle with this key is on, this option is shown but disabled. */
    val disabledWhenOn: String? = null,
) : ModelOption

object ModelOptions {
    const val LANGUAGE = "language"
    const val TARGET_LANGUAGE = "target_language"
    const val TRANSLATE_EN = "translate_en"
    const val WHISPER_PROMPT = "whisper_prompt"

    const val AUTO = "auto"

    fun specsFor(model: ModelManifest, settings: UserSettings = UserSettings()): List<ModelOption> = buildList {
        if (model.engineType == EngineType.CANARY_ONNX) {
            val sources = CanaryLanguages.sources(model)
            val defaultSource = CanaryLanguages.source(model, settings.spokenLanguageHint)
            val source = settings.modelOptions[model.id]?.get(LANGUAGE)?.takeIf { it in sources } ?: defaultSource
            add(ChoiceModelOption(LANGUAGE, R.string.canary_source_language, sources, defaultSource))
            add(ChoiceModelOption(
                TARGET_LANGUAGE,
                R.string.canary_target_language,
                CanaryLanguages.targets(model, source),
                CanaryLanguages.SAME_AS_SOURCE,
            ))
        } else if (model.canHintLanguage) {
            val langs = SupportedLanguages.all.map { it.code }
                .filter { model.languages.isEmpty() || it in model.languages }
            val choices = listOf(AUTO) + langs
            add(
                ChoiceModelOption(
                    key = LANGUAGE,
                    titleRes = R.string.pref_preferred_languages,
                    choices = choices,
                    default = settings.spokenLanguageHint.takeIf { it in choices } ?: AUTO,
                )
            )
        }
        if (model.canTranslateToEnglish) {
            add(ToggleModelOption(TRANSLATE_EN, R.string.translate_english_title, settings.translateToEnglish))
        }
        if (model.engineType == EngineType.WHISPER_CPP) {
            add(
                ToggleModelOption(
                    key = WHISPER_PROMPT,
                    titleRes = R.string.sub_language_whisper_prompt,
                    default = settings.whisperStylePromptEnabled,
                    disabledWhenOn = TRANSLATE_EN.takeIf { model.canTranslateToEnglish },
                )
            )
        }
    }

    /** Display label for a choice value (language codes today). */
    fun labelFor(option: ChoiceModelOption, value: String, autoLabel: String, sameAsSourceLabel: String): String = when (option.key) {
        LANGUAGE -> if (value == AUTO) autoLabel else SupportedLanguages.labelFor(value)
        TARGET_LANGUAGE -> if (value == CanaryLanguages.SAME_AS_SOURCE) sameAsSourceLabel else SupportedLanguages.labelFor(value)
        else -> value
    }
}

/** Typed, validated view of a model's option values (stored value -> legacy global -> default). */
data class ResolvedModelOptions(
    val language: String,
    val translateToEnglish: Boolean,
    val whisperPrompt: Boolean,
    val raw: Map<String, String>,
    val targetLanguage: String? = null,
) {
    fun bool(option: ToggleModelOption): Boolean = raw[option.key]?.toBooleanStrictOrNull() ?: option.default
    fun choice(option: ChoiceModelOption): String = raw[option.key]?.takeIf { it in option.choices } ?: option.default
}

fun UserSettings.optionsFor(model: ModelManifest?): ResolvedModelOptions {
    val stored = model?.let { modelOptions[it.id] }.orEmpty()
    val specs = model?.let { ModelOptions.specsFor(it, this) }.orEmpty()
    fun choice(key: String) = (specs.firstOrNull { it.key == key } as? ChoiceModelOption)
        ?.let { s -> stored[key]?.takeIf { it in s.choices } ?: s.default }
    fun toggle(key: String) = (specs.firstOrNull { it.key == key } as? ToggleModelOption)
        ?.let { s -> stored[key]?.toBooleanStrictOrNull() ?: s.default }

    return ResolvedModelOptions(
        language = choice(ModelOptions.LANGUAGE) ?: ModelOptions.AUTO,
        translateToEnglish = toggle(ModelOptions.TRANSLATE_EN) ?: false,
        whisperPrompt = toggle(ModelOptions.WHISPER_PROMPT) ?: whisperStylePromptEnabled,
        raw = stored,
        targetLanguage = choice(ModelOptions.TARGET_LANGUAGE),
    )
}
