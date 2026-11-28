package com.freespeak.ime.itn

import java.util.Locale

internal data class ItnSegment(
    val text: String,
    val shouldNormalize: Boolean,
)

internal object ItnTextPreprocessor {

    private val ENGLISH_TENS = setOf(
        "twenty", "thirty", "forty", "fourty", "fifty", "sixty", "seventy", "eighty", "ninety",
    )

    private val ENGLISH_UNITS_AND_ORDINALS = setOf(
        "one", "two", "three", "four", "five", "six", "seven", "eight", "nine",
        "first", "second", "third", "fourth", "fifth", "sixth", "seventh", "eighth", "ninth",
    )

    private val ENGLISH_SCALES = setOf(
        "hundred", "thousand", "million", "billion", "trillion",
        "hundredth", "thousandth", "millionth", "billionth",
    )

    private val ENGLISH_ALL_NUM_PARTS = ENGLISH_TENS + ENGLISH_UNITS_AND_ORDINALS + ENGLISH_SCALES + setOf(
        "zero", "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen",
        "sixteen", "seventeen", "eighteen", "nineteen",
        "tenth", "eleventh", "twelfth", "thirteenth", "fourteenth", "fifteenth",
        "sixteenth", "seventeenth", "eighteenth", "nineteenth",
        "twentieth", "thirtieth", "fortieth", "fiftieth", "sixtieth",
        "seventieth", "eightieth", "ninetieth",
    )

    // Matches standalone hyphenated alphabetic compounds not attached to surrounding hyphens/digits/slashes.
    private val HYPHENATED_COMPOUND_REGEX = Regex("(?<![A-Za-z0-9/-])[A-Za-z]+(?:-[A-Za-z]+)+(?![A-Za-z0-9/-])")

    private val CJK_DELIMITER_CHARS = setOf(
        '\r', '\n', '。', '！', '？', '；', '，', '、', '：',
        '“', '”', '‘', '’', '「', '」', '『', '』', '（', '）', '【', '】', '《', '》',
    )

    private val SPACED_SENTENCE_SPLIT_REGEX = Regex("(?<=[.!?;])\\s+|\\r?\\n+")

    /**
     * Expands hyphens inside English compound numerals (e.g. "thirty-five" -> "thirty five",
     * "twenty-first" -> "twenty first", "two-hundred-fifty" -> "two hundred fifty") so NeMo's
     * space-tokenized English grammar recognizes them without disturbing ordinary hyphenated words
     * ("well-known", "twenty-five-year-old", or numeric ranges like "twenty-thirty").
     */
    fun preprocessClause(clause: String, language: String): String {
        if (language != "en" || '-' !in clause) return clause
        return HYPHENATED_COMPOUND_REGEX.replace(clause) { match ->
            val parts = match.value.split('-')
            if (isValidEnglishHyphenatedNumeral(parts)) parts.joinToString(" ") else match.value
        }
    }

    private fun isValidEnglishHyphenatedNumeral(parts: List<String>): Boolean {
        if (parts.size < 2) return false
        val lower = parts.map { it.lowercase(Locale.ROOT) }
        if (lower.any { it !in ENGLISH_ALL_NUM_PARTS }) return false
        if (lower.size == 2) {
            val (a, b) = lower
            // Standard English compound 21-99 / 21st-99th ("thirty-five", "twenty-first")
            if (a in ENGLISH_TENS && b in ENGLISH_UNITS_AND_ORDINALS) return true
            // ASR-hyphenated scale ("two-hundred", "three-thousand", "hundred-fifty")
            if (a in ENGLISH_SCALES || b in ENGLISH_SCALES) return true
            return false
        }
        // 3+ parts (e.g. "two-hundred-fifty", "one-hundred-twenty-five"): require at least one scale word
        // and disallow two adjacent tens ("twenty-thirty-...").
        if (lower.none { it in ENGLISH_SCALES }) return false
        for (i in 0 until lower.size - 1) {
            if (lower[i] in ENGLISH_TENS && lower[i + 1] in ENGLISH_TENS) return false
        }
        return true
    }

    /**
     * Splits text into bounded clauses while preserving all delimiters verbatim so long dictations
     * stay fast and isolated per clause.
     */
    fun splitIntoSegments(text: String, language: String): List<ItnSegment> {
        if (text.isEmpty()) return emptyList()
        return if (language == "zh" || language == "ja") {
            splitCjkSegments(text, requireHanOnly = language == "zh")
        } else {
            splitSpacedSegments(text)
        }
    }

    private fun splitCjkSegments(text: String, requireHanOnly: Boolean): List<ItnSegment> {
        val out = ArrayList<ItnSegment>(8)
        var i = 0
        while (i < text.length) {
            val isDelim = text[i] in CJK_DELIMITER_CHARS
            val start = i
            while (i < text.length && (text[i] in CJK_DELIMITER_CHARS) == isDelim) {
                i++
            }
            val slice = text.substring(start, i)
            if (isDelim) {
                out.add(ItnSegment(slice, shouldNormalize = false))
            } else {
                val hasTargetScript = if (requireHanOnly) {
                    slice.codePoints().anyMatch { Character.UnicodeScript.of(it) == Character.UnicodeScript.HAN }
                } else {
                    slice.codePoints().anyMatch {
                        val s = Character.UnicodeScript.of(it)
                        s == Character.UnicodeScript.HAN ||
                            s == Character.UnicodeScript.HIRAGANA ||
                            s == Character.UnicodeScript.KATAKANA
                    }
                }
                out.add(ItnSegment(slice, shouldNormalize = slice.isNotBlank() && hasTargetScript))
            }
        }
        return out
    }

    private fun splitSpacedSegments(text: String): List<ItnSegment> {
        val out = ArrayList<ItnSegment>(8)
        var cursor = 0
        for (match in SPACED_SENTENCE_SPLIT_REGEX.findAll(text)) {
            if (match.range.first > cursor) {
                val sentence = text.substring(cursor, match.range.first)
                out.add(ItnSegment(sentence, shouldNormalize = sentence.isNotBlank()))
            }
            out.add(ItnSegment(match.value, shouldNormalize = false))
            cursor = match.range.last + 1
        }
        if (cursor < text.length) {
            val tail = text.substring(cursor)
            out.add(ItnSegment(tail, shouldNormalize = tail.isNotBlank()))
        }
        return out
    }
}
