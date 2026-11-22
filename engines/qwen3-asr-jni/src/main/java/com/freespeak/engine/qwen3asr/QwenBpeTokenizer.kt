package com.freespeak.engine.qwen3asr

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64

enum class TokenDecodeMode {
    BYTE_LEVEL_BPE,
    BASE64_BYTES,
    SENTENCEPIECE,
}

/**
 * Universal offline tokenizer supporting:
 * 1. HuggingFace `tokenizer.json` / `vocab.json` + `tokenizer_config.json` (Qwen2 / Qwen3 Byte-Level BPE)
 * 2. Base64-encoded UTF-8 byte `tokens.txt` (used by Fun-ASR-Nano CTC ONNX)
 * 3. SentencePiece / char `tokens.txt` (used by SenseVoice-Small / Paraformer)
 */
class QwenBpeTokenizer private constructor(
    private val idToToken: Map<Int, String>,
    private val tokenToId: Map<String, Int>,
    private val specialIds: Set<Int>,
    private val specialTokenStrings: List<String>,
    val mode: TokenDecodeMode,
) {
    val vocabSize: Int get() = idToToken.size

    fun tokenToId(token: String): Int? = tokenToId[token]

    fun isSpecialToken(id: Int): Boolean = id in specialIds

    /**
     * Encodes prompt strings (including special tokens like `<|im_start|>`, `<|audio_pad|>`, `<asr_text>`)
     * into token IDs using special-token splitting + greedy longest-prefix Byte-Level BPE matching.
     */
    fun encode(text: String): IntArray {
        if (text.isEmpty()) return IntArray(0)
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
                tokenToId[matchedSpecial]?.let { out.add(it) }
                idx += matchedSpecial.length
                continue
            }

            // Find next special token boundary
            var nextSpecialPos = text.length
            for (sp in specialTokenStrings) {
                if (sp.isEmpty()) continue
                val pos = text.indexOf(sp, idx)
                if (pos in idx until nextSpecialPos) {
                    nextSpecialPos = pos
                }
            }

            val chunk = text.substring(idx, nextSpecialPos)
            encodePlainChunk(chunk, out)
            idx = nextSpecialPos
        }
        return out.toIntArray()
    }

    private fun encodePlainChunk(chunk: String, out: MutableList<Int>) {
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
                    val tid = tokenToId[cand]
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
                    val tid = tokenToId[cand]
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

    fun decode(tokenIds: IntArray, skipSpecialTokens: Boolean = true): String {
        if (tokenIds.isEmpty()) return ""

        return when (mode) {
            TokenDecodeMode.BASE64_BYTES -> {
                val byteBuffer = ByteArrayOutputStream(tokenIds.size * 3)
                val b64Decoder = Base64.getDecoder()
                for (id in tokenIds) {
                    if (skipSpecialTokens && id in specialIds) continue
                    val piece = idToToken[id] ?: continue
                    if (skipSpecialTokens && isBracketSpecial(piece)) continue
                    val decodedBytes = runCatching { b64Decoder.decode(piece) }.getOrNull()
                    if (decodedBytes != null) {
                        byteBuffer.write(decodedBytes)
                    } else {
                        byteBuffer.write(piece.toByteArray(Charsets.UTF_8))
                    }
                }
                cleanAsrArtifacts(byteBuffer.toByteArray().toString(Charsets.UTF_8))
            }

            TokenDecodeMode.BYTE_LEVEL_BPE -> {
                val byteBuffer = ByteArrayOutputStream(tokenIds.size * 3)
                for (id in tokenIds) {
                    if (skipSpecialTokens && id in specialIds) continue
                    val piece = idToToken[id] ?: continue
                    if (skipSpecialTokens && isBracketSpecial(piece)) continue
                    for (ch in piece) {
                        val b = UNICODE_TO_BYTE[ch]
                        if (b != null) {
                            byteBuffer.write(b)
                        } else {
                            byteBuffer.write(ch.toString().toByteArray(Charsets.UTF_8))
                        }
                    }
                }
                cleanAsrArtifacts(byteBuffer.toByteArray().toString(Charsets.UTF_8))
            }

            TokenDecodeMode.SENTENCEPIECE -> {
                val sb = StringBuilder()
                for (id in tokenIds) {
                    if (skipSpecialTokens && id in specialIds) continue
                    val piece = idToToken[id] ?: continue
                    if (skipSpecialTokens && isBracketSpecial(piece)) continue
                    when {
                        piece.startsWith("<0x") && piece.endsWith(">") && piece.length == 6 -> {
                            val hex = piece.substring(3, 5).toIntOrNull(16)
                            if (hex != null) sb.append(hex.toChar())
                        }
                        piece.endsWith("@@") -> sb.append(piece.removeSuffix("@@"))
                        else -> sb.append(piece.replace('\u2581', ' '))
                    }
                }
                cleanAsrArtifacts(sb.toString())
            }
        }
    }

    private fun isBracketSpecial(piece: String): Boolean {
        return (piece.startsWith("<|") && piece.endsWith("|>")) ||
            piece == "<s>" || piece == "</s>" || piece == "<unk>" || piece == "<blank>" || piece == "<pad>"
    }

    private fun cleanAsrArtifacts(raw: String): String {
        var text = raw.replace(SPECIAL_TAG_REGEX, "")
        val asrTextMarker = "<asr_text>"
        val markerIdx = text.indexOf(asrTextMarker)
        if (markerIdx >= 0) {
            text = text.substring(markerIdx + asrTextMarker.length)
        }
        return text.replace("\uFFFD", "").trim()
    }

    companion object {
        private val SPECIAL_TAG_REGEX = Regex("<\\|[^|>]+\\|>")
        private val jsonParser = Json { ignoreUnknownKeys = true }

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
            if (!explicitVocabFile.isNullOrBlank()) {
                val f = File(modelDir, explicitVocabFile)
                if (f.exists()) return loadFromFile(f)
            }
            val relativeCandidates = listOf(
                "tokenizer.json",
                "vocab.json",
                "tokens.txt",
                "tokenizer/tokenizer.json",
                "tokenizer/vocab.json",
                "Qwen3-0.6B/tokenizer.json",
                "Qwen3-0.6B/vocab.json",
            )
            for (rel in relativeCandidates) {
                val f = File(modelDir, rel)
                if (f.exists()) return loadFromFile(f)
            }
            // Fallback shallow search in subdirectories
            modelDir.walkTopDown().maxDepth(3).firstOrNull {
                it.isFile && (it.name == "tokenizer.json" || it.name == "vocab.json" || it.name == "tokens.txt")
            }?.let { return loadFromFile(it) }

            throw IllegalArgumentException("Vocabulary file not found (tokenizer.json / vocab.json / tokens.txt) in ${modelDir.absolutePath}")
        }

        fun loadFromFile(file: File): QwenBpeTokenizer {
            return when {
                file.name.endsWith("tokenizer.json", ignoreCase = true) -> parseTokenizerJson(file.readText())
                file.name.endsWith("vocab.json", ignoreCase = true) -> parseVocabJson(file)
                else -> parseTokensTxt(file.readLines())
            }
        }

        internal fun parseTokenizerJson(content: String): QwenBpeTokenizer {
            val root = jsonParser.parseToJsonElement(content).jsonObject
            val idToToken = HashMap<Int, String>(160_000)
            val tokenToId = HashMap<String, Int>(160_000)
            val specialIds = HashSet<Int>()
            val specialStrings = ArrayList<String>()

            val modelObj = root["model"]?.jsonObject
            val vocabObj = modelObj?.get("vocab")?.jsonObject
            if (vocabObj != null) {
                for ((token, idElem) in vocabObj) {
                    val id = idElem.jsonPrimitive.intOrNull ?: continue
                    idToToken[id] = token
                    tokenToId[token] = id
                }
            }

            val addedTokens = root["added_tokens"]
            if (addedTokens is JsonArray) {
                for (elem in addedTokens) {
                    val obj = elem.jsonObject
                    val id = obj["id"]?.jsonPrimitive?.intOrNull ?: continue
                    val tok = obj["content"]?.jsonPrimitive?.content ?: continue
                    val special = obj["special"]?.jsonPrimitive?.booleanOrNull ?: false
                    idToToken[id] = tok
                    tokenToId[tok] = id
                    if (special || (tok.startsWith("<") && tok.endsWith(">"))) {
                        specialStrings.add(tok)
                    }
                    if (special || (tok.startsWith("<|") && tok.endsWith("|>"))) {
                        specialIds.add(id)
                    }
                }
            }

            specialStrings.sortByDescending { it.length }
            val isByteLevel = idToToken.values.any { it.contains('\u0120') || it.contains('\u010A') } ||
                modelObj?.get("type")?.jsonPrimitive?.content.equals("BPE", ignoreCase = true)

            return QwenBpeTokenizer(
                idToToken = idToToken,
                tokenToId = tokenToId,
                specialIds = specialIds,
                specialTokenStrings = specialStrings,
                mode = if (isByteLevel) TokenDecodeMode.BYTE_LEVEL_BPE else TokenDecodeMode.SENTENCEPIECE,
            )
        }

        internal fun parseVocabJson(vocabFile: File): QwenBpeTokenizer {
            val root: JsonObject = jsonParser.parseToJsonElement(vocabFile.readText()).jsonObject
            val idToToken = HashMap<Int, String>(root.size + 256)
            val tokenToId = HashMap<String, Int>(root.size + 256)
            val specialIds = HashSet<Int>()
            val specialStrings = ArrayList<String>()

            for ((token, idElem) in root) {
                val id = idElem.jsonPrimitive.intOrNull ?: continue
                idToToken[id] = token
                tokenToId[token] = id
                if (token.startsWith("<|") && token.endsWith("|>")) {
                    specialIds.add(id)
                    specialStrings.add(token)
                }
            }

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
                            idToToken[id] = content
                            tokenToId[content] = id
                            specialStrings.add(content)
                            if (special) specialIds.add(id)
                        }
                    }
                }
            }

            val distinctSpecials = specialStrings.distinct().sortedByDescending { it.length }
            return QwenBpeTokenizer(
                idToToken = idToToken,
                tokenToId = tokenToId,
                specialIds = specialIds,
                specialTokenStrings = distinctSpecials,
                mode = TokenDecodeMode.BYTE_LEVEL_BPE,
            )
        }

        internal fun parseTokensTxt(lines: List<String>): QwenBpeTokenizer {
            val idToToken = HashMap<Int, String>(lines.size)
            val tokenToId = HashMap<String, Int>(lines.size)
            val specialIds = HashSet<Int>()
            val specialStrings = ArrayList<String>()

            lines.forEachIndexed { lineIdx, rawLine ->
                val line = rawLine.trimEnd('\r', '\n')
                if (line.isEmpty()) return@forEachIndexed
                val lastSpace = line.lastIndexOf(' ')
                val (token, id) = if (lastSpace > 0 && line.substring(lastSpace + 1).toIntOrNull() != null) {
                    line.substring(0, lastSpace) to line.substring(lastSpace + 1).toInt()
                } else {
                    line to lineIdx
                }
                idToToken[id] = token
                tokenToId[token] = id
                if ((token.startsWith("<|") && token.endsWith("|>")) ||
                    token == "<blank>" || token == "<unk>" || token == "<s>" || token == "</s>"
                ) {
                    specialIds.add(id)
                    specialStrings.add(token)
                }
            }

            val mode = when {
                idToToken[0] == "IQ==" && idToToken[1] == "Ig==" -> TokenDecodeMode.BASE64_BYTES
                idToToken.values.any { it.contains('\u0120') } -> TokenDecodeMode.BYTE_LEVEL_BPE
                else -> TokenDecodeMode.SENTENCEPIECE
            }
            return QwenBpeTokenizer(
                idToToken = idToToken,
                tokenToId = tokenToId,
                specialIds = specialIds,
                specialTokenStrings = specialStrings.sortedByDescending { it.length },
                mode = mode,
            )
        }
    }
}
