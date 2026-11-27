package com.freespeak.ime.itn

/** NeMo's structured tag syntax; token order is fixed, field order may be permuted.
 * See NVIDIA NeMo text_normalization/{token_parser,normalize}.py (Apache-2.0).
 * Bounded lazy generation prevents factorial memory growth on malformed/long input.
 */
internal object NemoTokenParser {
    private data class Field(val key: String, val literal: String? = null, val children: List<Field>? = null)

    fun variants(text: String, limit: Int = 729): Sequence<String> {
        require(text.length <= 32768)
        val parser = Parser(text)
        val tokens = parser.fields(0)
        require(parser.pos == text.length && tokens.all { it.key == "tokens" && it.children != null })
        return combine(tokens.map { fieldVariants(it) }, limit).take(limit)
    }

    private fun fieldVariants(field: Field): Sequence<String> =
        field.children?.let { children ->
            val orders = if (children.any { it.key == "preserve_order" }) sequenceOf(children) else permutations(children)
            orders.flatMap { order ->
                combine(order.map { fieldVariants(it) }, 729).map { "${field.key} { $it} " }
            }.take(729)
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
        private fun ws() { while (pos < text.length && text[pos].isWhitespace()) pos++ }
        fun fields(depth: Int): List<Field> {
            require(depth <= 8)
            val result = mutableListOf<Field>()
            ws()
            while (pos < text.length && text[pos] != '}') {
                require(result.size < 128)
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
                        // NeMo emits literal quotes, not JSON strings. A closing quote is
                        // followed by whitespace (or a closing brace/end for strict input).
                        while (pos < text.length && !(text[pos] == '"' &&
                            (pos + 1 == text.length || text[pos + 1].isWhitespace() || text[pos + 1] == '}'))) pos++
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
