package com.freespeak.ime.itn

/**
 * NeMo structured tag parser (`tokens { ... }`).
 * Adapted from NVIDIA NeMo `text_normalization/{token_parser,normalize}.py` (Apache-2.0).
 *
 * Supports both:
 * 1. Per-token parsing ([parseTokens]), allowing plain word tokens (`tokens { name: "..." }`) to be
 *    emitted directly and semiotic tokens to be verbalized independently without sentence-wide
 *    permutation blowup or 128-token limits.
 * 2. Legacy whole-sequence [variants] helper for unit tests.
 */
internal object NemoTokenParser {
    private data class Field(val key: String, val literal: String? = null, val children: List<Field>? = null)

    internal sealed interface ParsedToken {
        data class PlainWord(val text: String) : ParsedToken
        data class Semiotic(val variants: Sequence<String>) : ParsedToken
    }

    internal data class RenderedToken(
        val text: String,
        val isPlainWord: Boolean,
    )

    private val CLOSING_PUNCT = setOf(".", ",", "!", "?", ":", ";", ")", "]", "}", "»")
    private val OPENING_PUNCT = setOf("(", "[", "{", "¿", "¡", "«")

    fun parseTokens(text: String, perTokenLimit: Int = 120): List<ParsedToken> {
        require(text.length <= 65536)
        val parser = Parser(text)
        val tokens = parser.fields(0)
        require(parser.pos == text.length && tokens.all { it.key == "tokens" && it.children != null })
        return tokens.map { tok ->
            val children = tok.children.orEmpty()
            val single = children.singleOrNull()
            if (single != null && single.key == "name" && single.literal != null && single.children == null) {
                val unquoted = unquoteLiteral(single.literal).replace('\u00A0', ' ')
                ParsedToken.PlainWord(unquoted)
            } else {
                ParsedToken.Semiotic(fieldVariants(tok, perTokenLimit).map { it.trimEnd() })
            }
        }
    }

    fun joinRenderedTokens(tokens: List<RenderedToken>, language: String): String {
        if (tokens.isEmpty()) return ""
        val unspacedScript = language == "ja" || language == "zh"
        if (unspacedScript) {
            return tokens.joinToString("") { it.text }
        }
        return buildString(tokens.sumOf { it.text.length + 1 }) {
            for (i in tokens.indices) {
                val cur = tokens[i]
                if (cur.text.isEmpty()) continue
                if (i > 0 && isNotEmpty()) {
                    val prev = tokens[i - 1]
                    val glueToPrev = (cur.isPlainWord && cur.text in CLOSING_PUNCT) ||
                        (prev.isPlainWord && prev.text in OPENING_PUNCT)
                    if (!glueToPrev) append(' ')
                }
                append(cur.text)
            }
        }
    }

    fun variants(text: String, limit: Int = 729): Sequence<String> {
        require(text.length <= 65536)
        val parser = Parser(text)
        val tokens = parser.fields(0)
        require(parser.pos == text.length && tokens.all { it.key == "tokens" && it.children != null })
        return combine(tokens.map { fieldVariants(it, limit) }, limit).take(limit)
    }

    private fun unquoteLiteral(literal: String): String {
        return if (literal.length >= 2 && literal.first() == '"' && literal.last() == '"') {
            literal.substring(1, literal.length - 1)
        } else {
            literal
        }
    }

    private fun fieldVariants(field: Field, limit: Int = 729): Sequence<String> =
        field.children?.let { children ->
            val orders = if (children.any { it.key == "preserve_order" }) sequenceOf(children) else permutations(children)
            orders.flatMap { order ->
                combine(order.map { fieldVariants(it, limit) }, limit).map { "${field.key} { $it} " }
            }.take(limit)
        } ?: sequenceOf("${field.key}: ${field.literal} ")

    private fun permutations(fields: List<Field>): Sequence<List<Field>> = sequence {
        if (fields.isEmpty()) yield(emptyList())
        else for (i in fields.indices) {
            for (rest in permutations(fields.filterIndexed { index, _ -> index != i })) yield(listOf(fields[i]) + rest)
        }
    }

    private fun combine(parts: List<Sequence<String>>, limit: Int): Sequence<String> {
        fun visit(index: Int, prefix: String): Sequence<String> = sequence {
            if (index == parts.size) yield(prefix)
            else for (part in parts[index]) yieldAll(visit(index + 1, prefix + part))
        }
        return visit(0, "").take(limit)
    }

    private class Parser(val text: String) {
        var pos = 0

        // Match NeMo Python token_parser.py: skip ASCII whitespace only (never U+00A0 non-breaking space).
        private fun isAsciiWs(ch: Char): Boolean = ch == ' ' || ch == '\t' || ch == '\n' || ch == '\r'
        private fun ws() { while (pos < text.length && isAsciiWs(text[pos])) pos++ }

        fun fields(depth: Int): List<Field> {
            require(depth <= 8)
            val result = mutableListOf<Field>()
            ws()
            while (pos < text.length && text[pos] != '}') {
                require(result.size < 2048)
                val start = pos
                while (pos < text.length && (text[pos] in 'a'..'z' || text[pos] in 'A'..'Z' || text[pos] == '_')) pos++
                require(pos > start)
                val key = text.substring(start, pos)
                ws()
                require(pos < text.length)
                if (text[pos] == '{') {
                    pos++
                    val children = fields(depth + 1)
                    require(pos < text.length && text[pos++] == '}')
                    result += Field(key, children = children)
                } else {
                    require(text[pos++] == ':'); ws()
                    val valueStart = pos
                    require(pos < text.length)
                    if (text[pos] == '"') {
                        pos++
                        // NeMo emits literal quotes; a closing quote is followed by ASCII space, '}' or EOF.
                        // U+00A0 non-breaking spaces inside multi-word values must NOT terminate the string!
                        while (pos < text.length && !(text[pos] == '"' &&
                            (pos + 1 == text.length || text[pos + 1] == ' ' || text[pos + 1] == '}'))) pos++
                        require(pos < text.length)
                        pos++
                    } else {
                        require(key == "preserve_order" && text.startsWith("true", pos))
                        pos += 4
                    }
                    result += Field(key, text.substring(valueStart, pos))
                }
                ws()
            }
            return result
        }
    }
}
