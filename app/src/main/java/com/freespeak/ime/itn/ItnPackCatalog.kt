package com.freespeak.ime.itn

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI

@Serializable
data class ItnFile(val name: String, val url: String, val bytes: Long, val sha256: String)

@Serializable
data class ItnPack(
    val language: String,
    val revision: String,
    val files: List<ItnFile>,
    val engine: String = ENGINE_NEMO,
) {
    val diskBytes get() = files.sumOf { it.bytes }

    companion object {
        const val ENGINE_NEMO = "nemo"
        const val ENGINE_WETEXT = "wetext"
        val supportedEngines = setOf(ENGINE_NEMO, ENGINE_WETEXT)
    }
}

@Serializable
data class ItnPackCatalog(val format: Int = 1, val packs: List<ItnPack>) {
    companion object {
        val languages = listOf("en", "zh", "de", "fr", "es", "ja", "ru", "pt")
        val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
        fun parse(text: String): ItnPackCatalog = json.decodeFromString<ItnPackCatalog>(text).also { catalog ->
            require(catalog.format == 1) { "Unsupported ITN pack format" }
            require(catalog.packs.size <= languages.size && catalog.packs.map { it.language }.distinct().size == catalog.packs.size)
            catalog.packs.forEach { pack ->
                require(pack.language in languages && pack.revision.matches(Regex("[a-zA-Z0-9._-]{1,80}")))
                require(pack.engine in ItnPack.supportedEngines) { "Unsupported ITN engine: ${pack.engine}" }
                require(pack.files.map { it.name }.sorted() == listOf("classify.far", "verbalize.far"))
                pack.files.forEach { file ->
                    require(file.bytes in 1..536_870_912L && file.sha256.matches(Regex("[a-f0-9]{64}")))
                    requireHttps(file.url)
                }
            }
        }
        fun requireHttps(url: String) {
            val uri = URI(url)
            require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null) { "An HTTPS URL is required" }
        }
    }
}

private val LATIN_ITN_LANGUAGES = setOf("en", "de", "fr", "es", "pt")
private val NON_LATIN_LANGUAGE_CODES = setOf(
    "zh", "yue", "ja", "ko", "ru", "uk", "sr", "mk", "bg", "be",
    "ar", "fa", "he", "hi", "bn", "mr", "th", "el", "hy",
)

private fun hasHanScript(text: String): Boolean =
    text.codePoints().anyMatch { Character.UnicodeScript.of(it) == Character.UnicodeScript.HAN }

private fun hasKanaScript(text: String): Boolean =
    text.codePoints().anyMatch {
        val s = Character.UnicodeScript.of(it)
        s == Character.UnicodeScript.HIRAGANA || s == Character.UnicodeScript.KATAKANA
    }

private fun hasCyrillicScript(text: String): Boolean =
    text.codePoints().anyMatch { Character.UnicodeScript.of(it) == Character.UnicodeScript.CYRILLIC }

private fun hasLatinScript(text: String): Boolean =
    text.codePoints().anyMatch { Character.UnicodeScript.of(it) == Character.UnicodeScript.LATIN }

/**
 * Resolves the ITN language pack to apply while preventing cross-script corruption
 * (such as sending pure English utterances into a Chinese/Japanese FST) and never guessing
 * shared scripts (Han / Cyrillic / Latin) unless the user's preferred languages disambiguate them.
 */
internal fun resolveItnLanguage(
    override: String,
    target: String?,
    detected: String?,
    source: String,
    text: String = "",
    preferredLanguages: Set<String> = emptySet(),
): String? {
    fun code(value: String?) = value?.lowercase(java.util.Locale.ROOT)?.substringBefore('-')?.substringBefore('_')

    if (override != "auto") {
        val forced = code(override)?.takeIf { it in ItnPackCatalog.languages } ?: return null
        if (text.isNotBlank()) {
            if (forced == "zh" && !hasHanScript(text)) return null
            if (forced == "ja" && !hasHanScript(text) && !hasKanaScript(text)) return null
        }
        return forced
    }

    val explicitTarget = code(target)?.takeUnless { it == "same" || it == "auto" }
    val explicit = explicitTarget
        ?: code(detected)?.takeUnless { it == "auto" }
        ?: code(source)?.takeUnless { it == "auto" }

    if (explicit != null) {
        if (text.isNotBlank()) {
            val cjkMismatch = (explicit == "zh" && !hasHanScript(text)) ||
                (explicit == "ja" && !hasHanScript(text) && !hasKanaScript(text))
            if (cjkMismatch) {
                return inferUnambiguousFromScript(text, preferredLanguages)
            }
        }
        return explicit.takeIf { it in ItnPackCatalog.languages }
    }

    return if (text.isNotBlank()) inferUnambiguousFromScript(text, preferredLanguages) else null
}

private fun inferUnambiguousFromScript(text: String, preferredLanguages: Set<String>): String? {
    fun code(value: String) = value.lowercase(java.util.Locale.ROOT).substringBefore('-').substringBefore('_')
    val prefs = preferredLanguages.map(::code).filter { it.isNotBlank() }.toSet()

    if (hasKanaScript(text)) return "ja"
    if (hasHanScript(text)) {
        val hanPrefs = prefs.intersect(setOf("zh", "yue", "ja"))
        return if (hanPrefs == setOf("zh")) "zh" else null
    }
    if (hasCyrillicScript(text)) {
        val cyrPrefs = prefs.intersect(setOf("ru", "uk", "sr", "mk", "bg", "be"))
        return if (cyrPrefs == setOf("ru")) "ru" else null
    }
    if (hasLatinScript(text)) {
        val latinPrefs = prefs.filterNot { it in NON_LATIN_LANGUAGE_CODES }
        return latinPrefs.singleOrNull()?.takeIf { it in LATIN_ITN_LANGUAGES }
    }
    return null
}
