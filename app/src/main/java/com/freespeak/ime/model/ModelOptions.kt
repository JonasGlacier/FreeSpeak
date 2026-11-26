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
    const val RUMBLE_DIGITS = "rumble_digits"
    const val RUMBLE_FILLER = "rumble_filler"
    const val RUMBLE_VERBATIM = "rumble_verbatim"
    const val FLUID_CLEAN_MODE = "fluid_clean_mode"

    const val AUTO = "auto"

    fun specsFor(model: ModelManifest, settings: UserSettings = UserSettings()): List<ModelOption> = buildList {
        if (model.engineType == EngineType.FLUID_TDT) {
            add(ToggleModelOption(FLUID_CLEAN_MODE, R.string.fluid_clean_mode, false))
        }
        if (model.engineType.isCanary) {
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
            val langs = if (model.hasRumbleFormatting || model.id == BuiltinModelCatalog.FUN_ASR_MLT_ID) {
                model.languages.filterNot { it == AUTO }
            } else {
                SupportedLanguages.all.map { it.code }.filter { model.languages.isEmpty() || it in model.languages }
            }
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
        if (model.hasRumbleFormatting) {
            val defaults = RumbleFormatting()
            add(ToggleModelOption(RUMBLE_DIGITS, R.string.rumble_digits, defaults.digits))
            add(ToggleModelOption(RUMBLE_FILLER, R.string.rumble_filler, defaults.filler))
            add(ToggleModelOption(RUMBLE_VERBATIM, R.string.rumble_verbatim, defaults.verbatim))
        }
    }

    /** Display label for a choice value (language codes today). */
    fun labelFor(option: ChoiceModelOption, value: String, autoLabel: String, sameAsSourceLabel: String): String = when (option.key) {
        LANGUAGE -> if (value == AUTO) autoLabel else languageLabel(value)
        TARGET_LANGUAGE -> if (value == CanaryLanguages.SAME_AS_SOURCE) sameAsSourceLabel else languageLabel(value)
        else -> value
    }

    private fun languageLabel(code: String): String = SupportedLanguages.all.firstOrNull { it.code == code }?.nativeLabel
        ?: java.util.Locale.forLanguageTag(code).let { it.getDisplayLanguage(it) }
}

/** Typed, validated view of a model's option values (stored value -> legacy global -> default). */
data class ResolvedModelOptions(
    val language: String,
    val raw: Map<String, String>,
    val targetLanguage: String? = null,
    val rumbleFormatting: RumbleFormatting? = null,
    val fluidCleanMode: Boolean = false,
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
        raw = stored,
        targetLanguage = choice(ModelOptions.TARGET_LANGUAGE),
        fluidCleanMode = toggle(ModelOptions.FLUID_CLEAN_MODE) ?: false,
        rumbleFormatting = model?.takeIf { it.hasRumbleFormatting }?.let {
            val defaults = RumbleFormatting()
            RumbleFormatting(
                digits = toggle(ModelOptions.RUMBLE_DIGITS) ?: defaults.digits,
                filler = toggle(ModelOptions.RUMBLE_FILLER) ?: defaults.filler,
                verbatim = toggle(ModelOptions.RUMBLE_VERBATIM) ?: defaults.verbatim,
            )
        },
    )
}
