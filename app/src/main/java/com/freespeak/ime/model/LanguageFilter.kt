package com.freespeak.ime.model

import java.util.Locale

/**
 * Language filtering for the model list. The user picks the languages they speak; a model
 * "fits" when it supports all of them. Only models with a trustworthy language list are checked:
 * imported / direct-URL models keep the manifest default list, so they are never greyed out.
 */
object LanguageFilter {

    /** Normalizes a locale to the 2/3-letter codes used in [ModelManifest.languages]. */
    fun codeFor(locale: Locale): String {
        val raw = locale.language.lowercase(Locale.ROOT)
        return when (raw) {
            "in" -> "id"
            "iw" -> "he"
            "ji" -> "yi"
            "nb", "nn", "no" -> "no"
            "tl" -> "fil"
            "zh" -> if (locale.country.uppercase(Locale.ROOT) in setOf("HK", "MO")) "yue" else "zh"
            "" -> "en"
            else -> raw
        }
    }

    /** Default selection: the system language plus English. */
    fun defaultFor(locale: Locale = Locale.getDefault()): Set<String> {
        val sys = codeFor(locale)
        return if (sys == "en") setOf("en") else linkedSetOf(sys, "en")
    }

    /** Every language offered by at least one model with a known language list. */
    fun availableLanguages(models: List<ModelManifest>): Set<String> =
        models.filter { it.hasKnownLanguages }.flatMap { it.normalizedLanguages }.toSet()
}

val ModelManifest.hasKnownLanguages: Boolean
    get() = sourceTag == "builtin" || sourceTag == "catalog" || engineType.isCanary

internal val ModelManifest.normalizedLanguages: Set<String>
    get() = languages.map { it.lowercase(Locale.ROOT) }.filter { it != "auto" }.toSet()

/** True when the model supports every language in [preferred] (or its languages are unknown). */
fun ModelManifest.supportsAll(preferred: Set<String>): Boolean {
    if (!hasKnownLanguages || preferred.isEmpty()) return true
    val supported = normalizedLanguages
    if (supported.isEmpty()) return true
    return preferred.all { it.lowercase(Locale.ROOT) in supported }
}
