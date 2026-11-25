package com.freespeak.ime.model

/** English is the translation pivot for both Canary variants. */
internal object CanaryLanguages {
    const val ENGLISH = "en"
    const val SAME_AS_SOURCE = "same_as_source"
    val supported = listOf(ENGLISH, "de", "es", "fr")
    val v2 = supported + listOf("bg", "hr", "cs", "da", "nl", "et", "fi", "el", "hu", "it", "lv", "lt", "mt", "pl", "pt", "ro", "sk", "sl", "sv", "ru", "uk")

    fun sources(model: ModelManifest): List<String> =
        v2.filter { it in model.languages }.ifEmpty { supported }

    fun source(model: ModelManifest, requested: String): String =
        sources(model).let { requested.takeIf(it::contains) ?: it.first() }

    fun targets(model: ModelManifest, source: String): List<String> =
        listOf(SAME_AS_SOURCE) + sources(model).filter {
            it != source && (source == ENGLISH || it == ENGLISH)
        }

    fun target(model: ModelManifest, source: String, requested: String?): String =
        requested?.takeIf { it != SAME_AS_SOURCE && it in targets(model, source) } ?: source
}
