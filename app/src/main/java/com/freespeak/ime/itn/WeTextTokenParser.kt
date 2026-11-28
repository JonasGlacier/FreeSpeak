package com.freespeak.ime.itn

/**
 * Deterministic token parser for WeTextProcessing ITN grammars (`engine = "wetext"`).
 * Ported from WeTextProcessing `tn/token_parser.py` (Apache-2.0).
 *
 * Plain character tokens (`char { value: "..." }`) are emitted directly without invoking the
 * verbalizer FST, preserving spaces and arbitrary Unicode verbatim. Structured semiotic tokens
 * (`cardinal`, `measure`, `date`, `money`, `fraction`, `time`, `whitelist`, etc.) have their
 * fields ordered canonically in O(1) without combinatorial permutation search.
 */
internal object WeTextTokenParser {

    internal sealed interface ParsedToken {
        data class PlainChar(val value: String) : ParsedToken
        data class Semiotic(val canonicalTag: String, val rawFallback: String) : ParsedToken
    }

    private val ITN_ORDERS: Map<String, List<String>> = mapOf(
        "date" to listOf("year", "month", "day", "preserve_order"),
        "fraction" to listOf("sign", "numerator", "denominator"),
        "measure" to listOf("numerator", "denominator", "value", "units"),
        "money" to listOf("currency", "value", "decimal", "quantity"),
        "time" to listOf("hour", "minute", "second", "noon", "zone"),
        "telephone" to listOf("country_code", "number_part"),
        "electronic" to listOf("username", "domain", "protocol"),
    )

    fun parse(tagged: String): List<ParsedToken> {
        require(tagged.isNotEmpty() && tagged.length <= 65536) { "Invalid WeText token stream length" }
        val p = Parser(tagged)
        val result = ArrayList<ParsedToken>(tagged.length / 16 + 4)
        while (p.skipSpaces()) {
            val name = p.parseKey()
            p.expect(" { ")
            val order = ArrayList<String>(4)
            val members = LinkedHashMap<String, String>(4)
            var closed = false
            while (p.skipSpaces()) {
                if (p.peek() == '}') {
                    p.advance()
                    closed = true
                    break
                }
                val key = p.parseKey()
                p.expect(": \"")
                val value = p.parseQuotedValue()
                p.expect("\"")
                require(members.put(key, value) == null) { "Duplicate field '$key' in token '$name'" }
                order.add(key)
            }
            require(closed) { "Unterminated WeText token '$name'" }

            if (name == "char" && members.size == 1 && members.containsKey("value")) {
                result.add(ParsedToken.PlainChar(members.getValue("value")))
            } else {
                val canonical = formatCanonical(name, order, members)
                val fallback = order.mapNotNull { k -> members[k]?.takeUnless { k == "preserve_order" } }.joinToString("")
                result.add(ParsedToken.Semiotic(canonical, fallback))
            }
        }
        return result
    }

    private fun formatCanonical(name: String, order: List<String>, members: Map<String, String>): String {
        val canonicalKeys = ITN_ORDERS[name]
        val finalOrder = if (canonicalKeys != null && members["preserve_order"] != "true") {
            canonicalKeys + order.filterNot { it in canonicalKeys }
        } else {
            order
        }
        return buildString(name.length + 32) {
            append(name).append(" {")
            for (key in finalOrder) {
                val v = members[key] ?: continue
                append(' ').append(key).append(": \"").append(escapeValue(v)).append('"')
            }
            append(" }")
        }
    }

    internal fun escapeValue(raw: String): String = buildString(raw.length + 4) {
        for (ch in raw) {
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                else -> append(ch)
            }
        }
    }

    private class Parser(private val text: String) {
        private var pos = 0

        fun peek(): Char = if (pos < text.length) text[pos] else '\u0000'

        fun advance() {
            if (pos < text.length) pos++
        }

        fun skipSpaces(): Boolean {
            while (pos < text.length && text[pos] == ' ') pos++
            return pos < text.length
        }

        fun expect(literal: String) {
            require(text.startsWith(literal, pos)) { "Expected '$literal' at $pos" }
            pos += literal.length
        }

        fun parseKey(): String {
            val start = pos
            while (pos < text.length && (text[pos] in 'a'..'z' || text[pos] in 'A'..'Z' || text[pos] == '_')) {
                pos++
            }
            require(pos > start) { "Invalid key at $pos" }
            return text.substring(start, pos)
        }

        fun parseQuotedValue(): String = buildString {
            while (true) {
                require(pos < text.length) { "Unterminated quoted value" }
                val ch = text[pos]
                if (ch == '"') break
                if (ch == '\\') {
                    pos++
                    require(pos < text.length) { "Unterminated escape in quoted value" }
                    val next = text[pos]
                    if (next == '"' || next == '\\') {
                        append(next)
                    } else {
                        append('\\').append(next)
                    }
                    pos++
                } else {
                    append(ch)
                    pos++
                }
            }
        }
    }
}
