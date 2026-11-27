package com.freespeak.ime.itn

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI

@Serializable
data class ItnFile(val name: String, val url: String, val bytes: Long, val sha256: String)

@Serializable
data class ItnPack(val language: String, val revision: String, val files: List<ItnFile>) {
    val diskBytes get() = files.sumOf { it.bytes }
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

/** Never infer Russian from Cyrillic or Chinese from Han: both scripts are shared. */
internal fun resolveItnLanguage(override: String, target: String?, detected: String?, source: String): String? {
    fun code(value: String?) = value?.lowercase(java.util.Locale.ROOT)?.substringBefore('-')?.substringBefore('_')
    val explicitTarget = code(target)?.takeUnless { it == "same" || it == "auto" }
    val selected = if (override != "auto") code(override) else explicitTarget ?: code(detected)?.takeUnless { it == "auto" } ?: code(source)
    return selected?.takeIf { it in ItnPackCatalog.languages }
}
