package com.freespeak.ime.itn

import com.freespeak.engine.itn.NemoItnNative

/**
 * Offline WFST inverse text normalizer supporting both NVIDIA NeMo (`engine = "nemo"`) and
 * WeTextProcessing (`engine = "wetext"`) language packs.
 *
 * Key reliability guarantees:
 * 1. Utterances are split into bounded clauses with delimiters preserved verbatim.
 * 2. Plain words/characters bypass the verbalizer FST completely, preserving spaces, quotes,
 *    and code-switched text.
 * 3. Structured semiotic tokens are verbalized individually so multi-entity sentences never
 *    suffer combinatorial permutation blowup or whole-utterance fallback.
 */
class NemoItnProcessor internal constructor(
    private val withInstalledPack: (String, (ItnPack, java.io.File) -> Boolean) -> Boolean?,
    private val rewriter: ((handle: Long, classify: Boolean, text: String) -> String?)? = null,
) {
    constructor(packs: ItnPackRepository) : this(packs::withInstalled, null)

    private var handle = 0L
    private var loaded: ItnPack? = null

    @Synchronized
    fun normalize(text: String, language: String?): String {
        if (language == null || text.isBlank() || text.length > 4096) return text
        return try {
            var activePack: ItnPack? = null
            val available = withInstalledPack(language) { pack, directory ->
                if (pack != loaded) {
                    releaseAll()
                    handle = if (rewriter != null) {
                        1L
                    } else {
                        NemoItnNative.create(
                            directory.resolve("classify.far").path.toByteArray(Charsets.UTF_8),
                            directory.resolve("verbalize.far").path.toByteArray(Charsets.UTF_8),
                        )
                    }
                    if (handle != 0L) loaded = pack
                }
                activePack = loaded
                handle != 0L
            } ?: false
            val pack = activePack
            if (!available || pack == null) {
                releaseAll()
                return text
            }

            val segments = ItnTextPreprocessor.splitIntoSegments(text, language)
            if (segments.isEmpty()) return text

            val deadline = System.nanoTime() + 2_500_000_000L
            buildString(text.length) {
                for (segment in segments) {
                    if (!segment.shouldNormalize || System.nanoTime() > deadline) {
                        append(segment.text)
                    } else {
                        append(normalizeClause(segment.text, language, pack.engine, deadline))
                    }
                }
            }
        } catch (_: Exception) {
            text
        } catch (_: LinkageError) {
            text
        }
    }

    private fun normalizeClause(
        rawClause: String,
        language: String,
        engine: String,
        deadline: Long,
    ): String {
        val leadingWsLen = rawClause.indexOfFirst { !it.isWhitespace() }.takeIf { it >= 0 } ?: return rawClause
        val lastNonWs = rawClause.indexOfLast { !it.isWhitespace() }
        val leadingWs = rawClause.substring(0, leadingWsLen)
        val trailingWs = rawClause.substring(lastNonWs + 1)
        val core = rawClause.substring(leadingWsLen, lastNonWs + 1)

        val preprocessed = ItnTextPreprocessor.preprocessClause(core, language)
        val normalizedCore = try {
            when (engine) {
                ItnPack.ENGINE_WETEXT -> normalizeWeTextClause(preprocessed, core, deadline)
                else -> normalizeNemoClause(preprocessed, core, language, deadline)
            }
        } catch (_: Exception) {
            core
        }
        return leadingWs + normalizedCore + trailingWs
    }

    private fun normalizeNemoClause(
        preprocessed: String,
        fallbackCore: String,
        language: String,
        deadline: Long,
    ): String {
        val tagged = rewrite(preprocessed, classify = true) ?: return fallbackCore
        val parsed = NemoTokenParser.parseTokens(tagged)
        if (parsed.all { it is NemoTokenParser.ParsedToken.PlainWord }) {
            // No semiotic entities were matched; return original core untouched (preserving any hyphens/spacing).
            return fallbackCore
        }
        val rendered = ArrayList<NemoTokenParser.RenderedToken>(parsed.size)
        for (token in parsed) {
            when (token) {
                is NemoTokenParser.ParsedToken.PlainWord -> {
                    rendered.add(NemoTokenParser.RenderedToken(token.text, isPlainWord = true))
                }
                is NemoTokenParser.ParsedToken.Semiotic -> {
                    var verbalized: String? = null
                    for (variant in token.variants) {
                        if (System.nanoTime() > deadline) break
                        val candidate = rewrite(variant, classify = false)?.trim()
                        if (!candidate.isNullOrEmpty()) {
                            verbalized = candidate
                            break
                        }
                    }
                    // If any semiotic token cannot be verbalized, safely return the original clause.
                    if (verbalized == null) return fallbackCore
                    rendered.add(NemoTokenParser.RenderedToken(verbalized, isPlainWord = false))
                }
            }
        }
        return NemoTokenParser.joinRenderedTokens(rendered, language)
    }

    private fun normalizeWeTextClause(
        preprocessed: String,
        fallbackCore: String,
        deadline: Long,
    ): String {
        val tagged = rewrite(preprocessed, classify = true) ?: return fallbackCore
        val parsed = WeTextTokenParser.parse(tagged)
        if (parsed.all { it is WeTextTokenParser.ParsedToken.PlainChar }) {
            return fallbackCore
        }
        return buildString(preprocessed.length) {
            for (token in parsed) {
                when (token) {
                    is WeTextTokenParser.ParsedToken.PlainChar -> append(token.value)
                    is WeTextTokenParser.ParsedToken.Semiotic -> {
                        val verbalized = if (System.nanoTime() <= deadline) {
                            rewrite(token.canonicalTag, classify = false)
                        } else null
                        append(verbalized ?: token.rawFallback)
                    }
                }
            }
        }
    }

    private fun rewrite(text: String, classify: Boolean): String? {
        rewriter?.let { return it(handle, classify, text) }
        return NemoItnNative.rewrite(handle, classify, text.toByteArray(Charsets.UTF_8))?.toString(Charsets.UTF_8)
    }

    @Synchronized
    fun releaseLanguage(language: String) {
        if (loaded?.language == language) releaseAll()
    }

    @Synchronized
    fun releaseAll() {
        if (handle != 0L && rewriter == null) NemoItnNative.release(handle)
        handle = 0L
        loaded = null
    }
}
