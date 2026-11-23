package com.freespeak.ime.text

import java.io.Reader
import java.util.Locale

enum class ChineseOutputMode {
    SYSTEM, SIMPLIFIED, TRADITIONAL, TAIWAN, HONG_KONG, ORIGINAL;

    val isTraditional: Boolean
        get() = this in setOf(TRADITIONAL, TAIWAN, HONG_KONG)

    fun resolved(locale: Locale = Locale.getDefault()): ChineseOutputMode {
        if (this != SYSTEM) return this
        return when {
            locale.country in setOf("HK", "MO") -> HONG_KONG
            locale.country == "TW" -> TAIWAN
            locale.script == "Hant" -> TRADITIONAL
            else -> SIMPLIFIED
        }
    }
}

/**
 * Offline OpenCC dictionary-based conversion. Uses the pinned upstream 1.1.9 dictionaries,
 * first-candidate mappings and longest-prefix phrase matching, not an LLM rewrite.
 * This small Kotlin adapter is not the upstream C++ implementation of MMSEG.
 * Dictionary groups respect priority: phrases before character fallbacks.
 */
class OpenCcConverter(private val openDictionary: (String) -> Reader) {
    // Keep only the selected mode's dictionaries resident; rebuilding is rare (a settings change).
    private var cachedMode: ChineseOutputMode? = null
    private var cachedChain: List<Dictionary> = emptyList()

    @Synchronized
    fun convert(
        text: String,
        mode: ChineseOutputMode,
        language: String? = null,
        translatedToEnglish: Boolean = false,
        locale: Locale = Locale.getDefault(),
    ): String {
        val resolved = mode.resolved(locale)
        // Avoid changing Japanese kanji. Unknown mixed Japanese/Chinese also stays untouched.
        if (resolved == ChineseOutputMode.ORIGINAL || translatedToEnglish || language == "ja" ||
            text.any { it in '\u3040'..'\u30FF' } || text.none { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }
        ) return text
        if (cachedMode != resolved) {
            cachedChain = emptyList()
            cachedMode = null
            val names = when (resolved) {
                ChineseOutputMode.SIMPLIFIED -> listOf(listOf("TSPhrases", "TSCharacters"))
                ChineseOutputMode.TRADITIONAL -> listOf(listOf("STPhrases", "STCharacters"))
                ChineseOutputMode.TAIWAN -> listOf(
                    listOf("STPhrases", "STCharacters"),
                    listOf("TWPhrasesIT", "TWPhrasesName", "TWPhrasesOther"), listOf("TWVariants"),
                )
                ChineseOutputMode.HONG_KONG -> listOf(
                    listOf("STPhrases", "STCharacters"), listOf("HKVariants"),
                )
                else -> emptyList()
            }
            cachedChain = names.map { group -> Dictionary(group.map { name -> load(name) }) }
            cachedMode = resolved
        }
        return cachedChain.fold(text) { current, dictionary -> dictionary.convert(current) }
    }

    private fun load(name: String): Trie {
        val root = Trie()
        openDictionary("$name.txt").buffered().useLines { lines ->
            lines.filter { it.isNotBlank() && !it.startsWith('#') }.forEach { line ->
                val fields = line.split('\t', limit = 2)
                if (fields.size == 2) {
                    var node = root
                    for (char in fields[0]) node = node.children.getOrPut(char) { Trie() }
                    node.output = fields[1].substringBefore(' ')
                }
            }
        }
        return root
    }

    private class Trie {
        val children = HashMap<Char, Trie>()
        var output: String? = null

        fun match(text: String, start: Int): Pair<Int, String>? {
            var node = this
            var result: Pair<Int, String>? = null
            var end = start
            while (end < text.length) {
                node = node.children[text[end]] ?: break
                end++
                node.output?.let { result = end to it }
            }
            return result
        }
    }

    private class Dictionary(val dictionaries: List<Trie>) {
        fun convert(text: String): String = buildString(text.length) {
            var index = 0
            while (index < text.length) {
                val match = dictionaries.firstNotNullOfOrNull { it.match(text, index) }
                if (match == null) append(text[index++]) else {
                    append(match.second)
                    index = match.first
                }
            }
        }
    }
}
