package com.freespeak.engine.qwen3asr

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.io.Reader
import java.util.Base64

enum class TokenDecodeMode {
    BYTE_LEVEL_BPE,
    BASE64_BYTES,
    SENTENCEPIECE,
}

data class DecodedAsrOutput(
    val text: String,
    val detectedLanguage: String? = null,
)

/**
 * Memory-efficient offline tokenizer using a flat `Array<String?>` table (avoiding boxed Integer
 * HashMap & JsonObject DOM bloat on 152k-token Qwen vocabularies).
 */
class QwenBpeTokenizer private constructor(
    private val idToToken: Array<String?>,
    private val specialIds: Set<Int>,
    private val specialTokenStrings: List<String>,
    val mode: TokenDecodeMode,
) {
    // Lazily populated only when prompt encoding or token lookup is requested
    private val tokenToIdMap: Map<String, Int> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        val map = HashMap<String, Int>(idToToken.size * 4 / 3 + 16)
        for (i in idToToken.indices) {
            val tok = idToToken[i]
            if (tok != null) {
                map[tok] = i
            }
        }
        map
    }

    val vocabSize: Int
        get() = idToToken.count { it != null }

    fun tokenToId(token: String): Int? = tokenToIdMap[token]

    fun isSpecialToken(id: Int): Boolean = id in specialIds

    fun encode(text: String): IntArray {
        if (text.isEmpty()) return IntArray(0)
        val lookup = tokenToIdMap
        val out = ArrayList<Int>(text.length)
        var idx = 0
        while (idx < text.length) {
            var matchedSpecial: String? = null
            for (sp in specialTokenStrings) {
                if (sp.isNotEmpty() && text.startsWith(sp, idx)) {
                    matchedSpecial = sp
                    break
                }
            }
            if (matchedSpecial != null) {
                lookup[matchedSpecial]?.let { out.add(it) }
                idx += matchedSpecial.length
                continue
            }

            var nextSpecialPos = text.length
            for (sp in specialTokenStrings) {
                if (sp.isEmpty()) continue
                val pos = text.indexOf(sp, idx)
                if (pos in idx until nextSpecialPos) {
                    nextSpecialPos = pos
                }
            }

            val chunk = text.substring(idx, nextSpecialPos)
            encodePlainChunk(chunk, lookup, out)
            idx = nextSpecialPos
        }
        return out.toIntArray()
    }

    private fun encodePlainChunk(
        chunk: String,
        lookup: Map<String, Int>,
        out: MutableList<Int>,
    ) {
        if (chunk.isEmpty()) return
        if (mode == TokenDecodeMode.BYTE_LEVEL_BPE) {
            val utf8 = chunk.toByteArray(Charsets.UTF_8)
            val mapped = StringBuilder(utf8.size)
            for (b in utf8) {
                val ch = BYTE_TO_UNICODE[b.toInt() and 0xFF]
                if (ch != null) mapped.append(ch)
            }
            val s = mapped.toString()
            var i = 0
            while (i < s.length) {
                var end = minOf(s.length, i + 24)
                var foundId: Int? = null
                while (end > i) {
                    val cand = s.substring(i, end)
                    val tid = lookup[cand]
                    if (tid != null) {
                        foundId = tid
                        break
                    }
                    end--
                }
                if (foundId != null) {
                    out.add(foundId)
                    i = end
                } else {
                    i++
                }
            }
        } else {
            var i = 0
            while (i < chunk.length) {
                var end = minOf(chunk.length, i + 16)
                var foundId: Int? = null
                while (end > i) {
                    val cand = chunk.substring(i, end)
                    val tid = lookup[cand]
                    if (tid != null) {
                        foundId = tid
                        break
                    }
                    end--
                }
                if (foundId != null) {
                    out.add(foundId)
                    i = end
                } else {
                    i++
                }
            }
        }
    }

    fun decode(tokenIds: IntArray, skipSpecialTokens: Boolean = true): String =
        decodeWithMetadata(tokenIds, skipSpecialTokens).text

    fun decodeWithMetadata(tokenIds: IntArray, skipSpecialTokens: Boolean = true): DecodedAsrOutput {
        if (tokenIds.isEmpty()) return DecodedAsrOutput("")

        var specialTagLanguage: String? = null
        fun captureBracketLanguage(piece: String) {
            if (specialTagLanguage == null && piece.startsWith("<|") && piece.endsWith("|>")) {
                specialTagLanguage = parseAsrLanguageTag(piece.substring(2, piece.length - 2))
            }
        }

        val rawDecoded = when (mode) {
            TokenDecodeMode.BASE64_BYTES -> {
                val byteBuffer = ByteArrayOutputStream(tokenIds.size * 3)
                val b64Decoder = Base64.getDecoder()
                for (id in tokenIds) {
                    if (id < 0 || id >= idToToken.size) continue
                    val piece = idToToken[id] ?: continue
                    captureBracketLanguage(piece)
                    if (skipSpecialTokens && (id in specialIds || isBracketSpecial(piece))) continue
                    val decodedBytes = runCatching { b64Decoder.decode(piece) }.getOrNull()
                    if (decodedBytes != null) {
                        byteBuffer.write(decodedBytes)
                    } else {
                        byteBuffer.write(piece.toByteArray(Charsets.UTF_8))
                    }
                }
                byteBuffer.toByteArray().toString(Charsets.UTF_8)
            }

            TokenDecodeMode.BYTE_LEVEL_BPE -> {
                val byteBuffer = ByteArrayOutputStream(tokenIds.size * 3)
                for (id in tokenIds) {
                    if (id < 0 || id >= idToToken.size) continue
                    val piece = idToToken[id] ?: continue
                    captureBracketLanguage(piece)
                    if (skipSpecialTokens && (id in specialIds || isBracketSpecial(piece))) continue
                    for (ch in piece) {
                        val b = UNICODE_TO_BYTE[ch]
                        if (b != null) {
                            byteBuffer.write(b)
                        } else {
                            byteBuffer.write(ch.toString().toByteArray(Charsets.UTF_8))
                        }
                    }
                }
                byteBuffer.toByteArray().toString(Charsets.UTF_8)
            }

            TokenDecodeMode.SENTENCEPIECE -> {
                val sb = StringBuilder()
                for (id in tokenIds) {
                    if (id < 0 || id >= idToToken.size) continue
                    val piece = idToToken[id] ?: continue
                    captureBracketLanguage(piece)
                    if (skipSpecialTokens && (id in specialIds || isBracketSpecial(piece))) continue
                    when {
                        piece.startsWith("<0x") && piece.endsWith(">") && piece.length == 6 -> {
                            val hex = piece.substring(3, 5).toIntOrNull(16)
                            if (hex != null) sb.append(hex.toChar())
                        }
                        piece.endsWith("@@") -> sb.append(piece.removeSuffix("@@"))
                        else -> sb.append(piece.replace('\u2581', ' '))
                    }
                }
                sb.toString()
            }
        }
        return extractAsrMetadata(rawDecoded, specialTagLanguage)
    }

    private fun isBracketSpecial(piece: String): Boolean {
        return (piece.startsWith("<|") && piece.endsWith("|>")) ||
            piece == "<s>" || piece == "</s>" || piece == "<unk>" || piece == "<blank>" || piece == "<pad>"
    }

    private fun extractAsrMetadata(raw: String, fallbackLanguage: String?): DecodedAsrOutput {
        var text = raw.replace(SPECIAL_TAG_REGEX, "")
        var headerLanguage: String? = null
        val asrTextMarker = "<asr_text>"
        val markerIdx = text.indexOf(asrTextMarker)
        if (markerIdx >= 0) {
            val header = text.substring(0, markerIdx).trim()
            if (header.startsWith("language ", ignoreCase = true)) {
                headerLanguage = parseAsrLanguageTag(header.substring("language ".length))
            }
            text = text.substring(markerIdx + asrTextMarker.length)
        }
        return DecodedAsrOutput(
            text = text.replace("\uFFFD", "").trim(),
            detectedLanguage = headerLanguage ?: fallbackLanguage,
        )
    }

    companion object {
        private val SPECIAL_TAG_REGEX = Regex("<\\|[^|>]+\\|>")
        private val jsonParser = Json { ignoreUnknownKeys = true }

        private val ENGLISH_LANGUAGE_CODES: Map<String, String> by lazy(LazyThreadSafetyMode.PUBLICATION) {
            val map = HashMap<String, String>(256)
            for (iso in java.util.Locale.getISOLanguages()) {
                val loc = java.util.Locale.forLanguageTag(iso)
                val eng = loc.getDisplayLanguage(java.util.Locale.ENGLISH).lowercase(java.util.Locale.ROOT)
                if (eng.isNotBlank()) map.putIfAbsent(eng, iso)
                map.putIfAbsent(iso.lowercase(java.util.Locale.ROOT), iso.lowercase(java.util.Locale.ROOT))
            }
            map["chinese"] = "zh"
            map["mandarin"] = "zh"
            map["cantonese"] = "yue"
            map["yue"] = "yue"
            map["filipino"] = "fil"
            map["fil"] = "fil"
            map["cebuano"] = "ceb"
            map["ceb"] = "ceb"
            map
        }

        internal fun parseAsrLanguageTag(rawTag: String): String? {
            val normalized = rawTag.trim().lowercase(java.util.Locale.ROOT)
            if (normalized.isEmpty() || normalized in setOf("none", "auto", "unknown", "nospeech", "speech", "withitn", "woitn")) {
                return null
            }
            return ENGLISH_LANGUAGE_CODES[normalized]
        }

        private val UNICODE_TO_BYTE: Map<Char, Int>
        private val BYTE_TO_UNICODE: Map<Int, Char>

        init {
            val bs = mutableListOf<Int>()
            for (b in '!'.code..'~'.code) bs.add(b)
            for (b in '¡'.code..'¬'.code) bs.add(b)
            for (b in '®'.code..'ÿ'.code) bs.add(b)
            val cs = bs.toMutableList()
            var n = 0
            for (b in 0..255) {
                if (b !in bs) {
                    bs.add(b)
                    cs.add(256 + n)
                    n++
                }
            }
            val u2b = HashMap<Char, Int>(256)
            val b2u = HashMap<Int, Char>(256)
            for (i in bs.indices) {
                u2b[cs[i].toChar()] = bs[i]
                b2u[bs[i]] = cs[i].toChar()
            }
            UNICODE_TO_BYTE = u2b
            BYTE_TO_UNICODE = b2u
        }

        fun loadFromDirectory(modelDir: File, explicitVocabFile: String? = null): QwenBpeTokenizer {
            // Prefer vocab.json over 11MB tokenizer.json when both exist to minimize memory footprint
            if (!explicitVocabFile.isNullOrBlank()) {
                val f = File(modelDir, explicitVocabFile)
                if (f.exists()) return loadFromFile(f)
            }
            val relativeCandidates = listOf(
                "vocab.json",
                "tokenizer/vocab.json",
                "Qwen3-0.6B/vocab.json",
                "tokens.txt",
                "tokenizer.json",
                "tokenizer/tokenizer.json",
                "Qwen3-0.6B/tokenizer.json",
            )
            for (rel in relativeCandidates) {
                val f = File(modelDir, rel)
                if (f.exists()) return loadFromFile(f)
            }
            modelDir.walkTopDown().maxDepth(3).firstOrNull {
                it.isFile && (it.name == "vocab.json" || it.name == "tokens.txt" || it.name == "tokenizer.json")
            }?.let { return loadFromFile(it) }

            throw IllegalArgumentException(
                "Vocabulary file not found (tokenizer.json / vocab.json / tokens.txt) in ${modelDir.absolutePath}"
            )
        }

        fun loadFromFile(file: File): QwenBpeTokenizer {
            return when {
                file.name.endsWith("vocab.json", ignoreCase = true) -> parseVocabJsonStreaming(file)
                file.name.endsWith("tokenizer.json", ignoreCase = true) -> parseTokenizerJson(file)
                else -> parseTokensTxtStreaming(file)
            }
        }

        private fun ensureCapacity(arr: Array<String?>, requiredIndex: Int): Array<String?> {
            if (requiredIndex < arr.size) return arr
            var newCap = maxOf(arr.size * 2, requiredIndex + 1024)
            if (newCap < 160_000 && requiredIndex > 32_000) newCap = 160_000
            return arr.copyOf(newCap)
        }

        /**
         * Zero-DOM streaming parser for `vocab.json` (`{"token": id, ...}`) + sibling `tokenizer_config.json`.
         */
        internal fun parseVocabJsonStreaming(vocabFile: File): QwenBpeTokenizer {
            var table = arrayOfNulls<String>(152_128)
            val specialIds = HashSet<Int>()
            val specialStrings = ArrayList<String>()
            var maxId = -1

            BufferedReader(InputStreamReader(FileInputStream(vocabFile), Charsets.UTF_8), 32 * 1024).use { reader ->
                parseFlatJsonStringIntPairs(reader) { token, id ->
                    if (id >= 0) {
                        table = ensureCapacity(table, id)
                        table[id] = token
                        if (id > maxId) maxId = id
                        if (token.startsWith("<|") && token.endsWith("|>")) {
                            specialIds.add(id)
                            specialStrings.add(token)
                        }
                    }
                }
            }

            // Merge small ~12KB tokenizer_config.json for added special tokens (<|im_start|>, <|audio_pad|>, <asr_text>, etc.)
            val configFile = File(vocabFile.parentFile, "tokenizer_config.json")
            if (configFile.exists()) {
                runCatching {
                    val cfg = jsonParser.parseToJsonElement(configFile.readText()).jsonObject
                    val addedDecoder = cfg["added_tokens_decoder"]?.jsonObject
                    if (addedDecoder != null) {
                        for ((idKey, entry) in addedDecoder) {
                            val id = idKey.toIntOrNull() ?: continue
                            val entryObj = entry.jsonObject
                            val content = entryObj["content"]?.jsonPrimitive?.content ?: continue
                            val special = entryObj["special"]?.jsonPrimitive?.booleanOrNull ?: false
                            table = ensureCapacity(table, id)
                            table[id] = content
                            if (id > maxId) maxId = id
                            specialStrings.add(content)
                            if (special) specialIds.add(id)
                        }
                    }
                }
            }

            val trimmed = if (maxId >= 0 && maxId + 1 < table.size) table.copyOf(maxId + 1) else table
            val distinctSpecials = specialStrings.distinct().sortedByDescending { it.length }
            return QwenBpeTokenizer(
                idToToken = trimmed,
                specialIds = specialIds,
                specialTokenStrings = distinctSpecials,
                mode = TokenDecodeMode.BYTE_LEVEL_BPE,
            )
        }

        internal fun parseTokenizerJson(file: File): QwenBpeTokenizer {
            // Check if sibling vocab.json exists first (much smaller than 11MB tokenizer.json)
            val siblingVocab = File(file.parentFile, "vocab.json")
            if (siblingVocab.exists()) {
                return parseVocabJsonStreaming(siblingVocab)
            }

            val root = jsonParser.parseToJsonElement(file.readText()).jsonObject
            var table = arrayOfNulls<String>(152_128)
            val specialIds = HashSet<Int>()
            val specialStrings = ArrayList<String>()
            var maxId = -1

            val modelObj = root["model"]?.jsonObject
            val vocabObj = modelObj?.get("vocab")?.jsonObject
            if (vocabObj != null) {
                for ((token, idElem) in vocabObj) {
                    val id = idElem.jsonPrimitive.intOrNull ?: continue
                    if (id >= 0) {
                        table = ensureCapacity(table, id)
                        table[id] = token
                        if (id > maxId) maxId = id
                    }
                }
            }

            val addedTokens = root["added_tokens"]
            if (addedTokens is JsonArray) {
                for (elem in addedTokens) {
                    val obj = elem.jsonObject
                    val id = obj["id"]?.jsonPrimitive?.intOrNull ?: continue
                    val tok = obj["content"]?.jsonPrimitive?.content ?: continue
                    val special = obj["special"]?.jsonPrimitive?.booleanOrNull ?: false
                    if (id >= 0) {
                        table = ensureCapacity(table, id)
                        table[id] = tok
                        if (id > maxId) maxId = id
                        if (special || (tok.startsWith("<") && tok.endsWith(">"))) {
                            specialStrings.add(tok)
                        }
                        if (special || (tok.startsWith("<|") && tok.endsWith("|>"))) {
                            specialIds.add(id)
                        }
                    }
                }
            }

            val trimmed = if (maxId >= 0 && maxId + 1 < table.size) table.copyOf(maxId + 1) else table
            val isByteLevel = trimmed.any { it != null && (it.contains('\u0120') || it.contains('\u010A')) } ||
                modelObj?.get("type")?.jsonPrimitive?.content.equals("BPE", ignoreCase = true)

            return QwenBpeTokenizer(
                idToToken = trimmed,
                specialIds = specialIds,
                specialTokenStrings = specialStrings.distinct().sortedByDescending { it.length },
                mode = if (isByteLevel) TokenDecodeMode.BYTE_LEVEL_BPE else TokenDecodeMode.SENTENCEPIECE,
            )
        }

        internal fun parseTokensTxtStreaming(file: File): QwenBpeTokenizer {
            var table = arrayOfNulls<String>(65_536)
            val specialIds = HashSet<Int>()
            val specialStrings = ArrayList<String>()
            var maxId = -1

            BufferedReader(InputStreamReader(FileInputStream(file), Charsets.UTF_8), 32 * 1024).use { reader ->
                var lineIdx = 0
                while (true) {
                    val rawLine = reader.readLine() ?: break
                    val line = rawLine.trimEnd('\r', '\n')
                    if (line.isNotEmpty()) {
                        val lastSpace = line.lastIndexOf(' ')
                        val (token, id) = if (lastSpace > 0 && line.substring(lastSpace + 1).toIntOrNull() != null) {
                            line.substring(0, lastSpace) to line.substring(lastSpace + 1).toInt()
                        } else {
                            line to lineIdx
                        }
                        if (id >= 0) {
                            table = ensureCapacity(table, id)
                            table[id] = token
                            if (id > maxId) maxId = id
                            if ((token.startsWith("<|") && token.endsWith("|>")) ||
                                token == "<blank>" || token == "<unk>" || token == "<s>" || token == "</s>"
                            ) {
                                specialIds.add(id)
                                specialStrings.add(token)
                            }
                        }
                    }
                    lineIdx++
                }
            }

            val trimmed = if (maxId >= 0 && maxId + 1 < table.size) table.copyOf(maxId + 1) else table
            val mode = when {
                trimmed.size > 1 && trimmed[0] == "IQ==" && trimmed[1] == "Ig==" -> TokenDecodeMode.BASE64_BYTES
                trimmed.any { it != null && it.contains('\u0120') } -> TokenDecodeMode.BYTE_LEVEL_BPE
                else -> TokenDecodeMode.SENTENCEPIECE
            }
            return QwenBpeTokenizer(
                idToToken = trimmed,
                specialIds = specialIds,
                specialTokenStrings = specialStrings.sortedByDescending { it.length },
                mode = mode,
            )
        }

        /**
         * Minimal, zero-DOM streaming scanner for flat JSON maps `{"strKey": intVal, ...}`.
         */
        internal fun parseFlatJsonStringIntPairs(
            reader: Reader,
            onEntry: (key: String, value: Int) -> Unit,
        ) {
            val sb = StringBuilder(32)
            var ch = reader.read()

            fun skipWhitespace() {
                while (ch != -1 && (ch == ' '.code || ch == '\n'.code || ch == '\r'.code || ch == '\t'.code)) {
                    ch = reader.read()
                }
            }

            fun readJsonString(): String {
                sb.setLength(0)
                // Assume opening '"' already matched; advance past '"'
                ch = reader.read()
                while (ch != -1 && ch != '"'.code) {
                    if (ch == '\\'.code) {
                        val esc = reader.read()
                        if (esc == -1) break
                        when (esc.toChar()) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                var code = 0
                                repeat(4) {
                                    val hexCh = reader.read()
                                    val digit = Character.digit(hexCh, 16)
                                    if (digit >= 0) code = (code shl 4) or digit
                                }
                                sb.append(code.toChar())
                            }
                            else -> sb.append(esc.toChar())
                        }
                    } else {
                        sb.append(ch.toChar())
                    }
                    ch = reader.read()
                }
                // Advance past closing '"'
                ch = reader.read()
                return sb.toString()
            }

            skipWhitespace()
            if (ch == '{'.code) ch = reader.read()

            while (ch != -1) {
                skipWhitespace()
                if (ch == '}'.code || ch == -1) break
                if (ch == ','.code) {
                    ch = reader.read()
                    skipWhitespace()
                }
                if (ch != '"'.code) {
                    ch = reader.read()
                    continue
                }
                val key = readJsonString()
                skipWhitespace()
                if (ch == ':'.code) ch = reader.read()
                skipWhitespace()

                var sign = 1
                if (ch == '-'.code) {
                    sign = -1
                    ch = reader.read()
                }
                var num = 0
                var hasDigits = false
                while (ch in '0'.code..'9'.code) {
                    hasDigits = true
                    num = num * 10 + (ch - '0'.code)
                    ch = reader.read()
                }
                if (hasDigits) {
                    onEntry(key, num * sign)
                }
            }
        }
    }
}
