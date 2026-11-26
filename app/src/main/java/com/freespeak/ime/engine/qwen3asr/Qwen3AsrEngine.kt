package com.freespeak.ime.engine.qwen3asr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import com.freespeak.engine.dsp.AudioDspNative
import com.freespeak.engine.qwen3asr.Qwen3AsrNative
import com.freespeak.engine.qwen3asr.QwenBpeTokenizer
import com.freespeak.ime.engine.AsrEngine
import com.freespeak.ime.engine.AsrInferenceOptions
import com.freespeak.ime.engine.AsrTranscriptionResult
import com.freespeak.ime.model.EngineType
import com.freespeak.ime.model.ModelManifest
import com.freespeak.ime.model.RumbleFormatting
import com.freespeak.ime.model.hasRumbleFormatting
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.util.Locale

/**
 * Memory-optimized ONNX multimodal inference engine for `Qwen3-ASR-0.6B`
 * (`conv_frontend.onnx` + `encoder[.int8].onnx` + `decoder[.int8].onnx` + `vocab.json`).
 *
 * Key mobile memory optimizations:
 * 1. KV caches live 100% off-heap in DirectByteBuffers (0 bytes on Android's Dalvik/ART heap)
 *    and are wrapped once into zero-copy OnnxTensors per transcription.
 * 2. Dynamic KV cache capacity sized tightly to `promptLen + maxNewTokens` instead of fixed 1024.
 * 3. Prefill over `promptIds[0 until S0 - 1]` requests ONLY `key_delta_*` / `value_delta_*`
 *    (skipping the 151,936-vocab `lm_head` MatMul and avoiding multi-megabyte prefill logits!).
 */
class Qwen3AsrEngine : AsrEngine {

    override val engineType: EngineType = EngineType.QWEN3_ASR

    private var ortEnv: OrtEnvironment? = null
    private var convSession: OrtSession? = null
    private var encoderSession: OrtSession? = null
    private var decoderSession: OrtSession? = null
    private var tokenizer: QwenBpeTokenizer? = null
    private var manifest: ModelManifest? = null

    private var numLayers: Int = 0
    private var modelFixedMaxLen: Int = -1
    private var kvHeads: Int = 0
    private var headDim: Int = 0

    override val isLoaded: Boolean
        get() = convSession != null && encoderSession != null && decoderSession != null && tokenizer != null

    override suspend fun load(
        modelDir: File,
        manifest: ModelManifest,
        numThreads: Int,
    ): Result<Unit> = runCatching {
        release()
        if (!AudioDspNative.ensureLoaded()) {
            error("Failed to load libfreespeak_dsp.so")
        }
        Qwen3AsrNative.ensureLoaded()

        val env = OrtEnvironment.getEnvironment()
        ortEnv = env
        val cfg = manifest.runtimeConfig

        val convFile = resolveFile(modelDir, cfg.convFrontendFile, listOf("conv_frontend.onnx"))
            ?: error("Missing conv_frontend.onnx")
        val encFile = resolveFile(modelDir, cfg.encoderFile, listOf("encoder.int8.onnx", "encoder.onnx"))
            ?: error("Missing encoder.int8.onnx / encoder.onnx")
        val decFile = resolveFile(modelDir, cfg.decoderFile, listOf("decoder.int8.onnx", "decoder.onnx"))
            ?: error("Missing decoder.int8.onnx / decoder.onnx")

        val tok = QwenBpeTokenizer.loadFromDirectory(modelDir, cfg.vocabFile)
        if (manifest.hasRumbleFormatting) {
            RumbleFormatting().promptTokens().plus(RumbleFormatting(false, true, false).promptTokens()).forEach {
                requireNotNull(tok.tokenToId(it)) { "Rumble tokenizer is missing $it" }
            }
            requireNotNull(tok.tokenToId("<|audio_pad|>")) { "Rumble tokenizer is missing the audio token" }
            requireNotNull(tok.tokenToId("<|im_end|>")) { "Rumble tokenizer is missing the end-of-turn token" }
            requireNotNull(tok.tokenToId("<|endoftext|>")) { "Rumble tokenizer is missing the end-of-text token" }
        }

        val dSess = OrtSession.SessionOptions().use { opts ->
            opts.setIntraOpNumThreads(numThreads.coerceIn(1, 8))
            opts.setInterOpNumThreads(1)
            opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            opts.setMemoryPatternOptimization(false)
            opts.setCPUArenaAllocator(false)
            convSession = env.createSession(convFile.absolutePath, opts)
            encoderSession = env.createSession(encFile.absolutePath, opts)
            env.createSession(decFile.absolutePath, opts).also { decoderSession = it }
        }

        val cacheKeys = dSess.inputInfo.keys
            .filter { it.startsWith("cache_key_") }
            .sortedBy { it.substringAfterLast('_').toIntOrNull() ?: 0 }

        if (cacheKeys.isEmpty()) {
            error("Decoder missing cache_key_* inputs")
        }

        val firstCacheShape = (dSess.inputInfo[cacheKeys[0]]?.info as? TensorInfo)?.shape
            ?: error("Invalid decoder KV cache shape")

        numLayers = cacheKeys.size
        modelFixedMaxLen = if (firstCacheShape.size >= 4 && firstCacheShape[1] > 0) {
            firstCacheShape[1].toInt()
        } else {
            -1 // Dynamic axis ("max_total_len")
        }
        kvHeads = firstCacheShape[2].toInt()
        headDim = firstCacheShape[3].toInt()

        tokenizer = tok
        this.manifest = manifest
    }.onFailure { release() }

    override suspend fun transcribe(
        pcm16k: FloatArray,
        options: AsrInferenceOptions,
    ): Result<AsrTranscriptionResult> = runCatching {
        val env = ortEnv ?: error("ORT uninitialized")
        val cSess = convSession ?: error("ConvFrontend not loaded")
        val eSess = encoderSession ?: error("Encoder not loaded")
        val dSess = decoderSession ?: error("Decoder not loaded")
        val tok = tokenizer ?: error("Tokenizer not loaded")

        if (pcm16k.isEmpty()) return@runCatching AsrTranscriptionResult("")
        val startMs = System.currentTimeMillis()

        val numMels = manifest?.runtimeConfig?.numMels ?: 128
        val melChannelFirst = AudioDspNative.extractWhisperMelSpectrogram(
            pcm16k = pcm16k,
            numMels = numMels,
            padOrTrimSamples = 0,
        )
        val numFrames = melChannelFirst.size / numMels
        if (numFrames <= 0) return@runCatching AsrTranscriptionResult("")

        // Transpose [numMels, numFrames] directly into off-heap DirectFloatBuffer [1, numFrames, numMels]
        val melDirect = allocateDirectFloats(numFrames * numMels)
        for (t in 0 until numFrames) {
            for (m in 0 until numMels) {
                melDirect.put(melChannelFirst[m * numFrames + t])
            }
        }
        melDirect.rewind()

        // Step 1: Run conv_frontend.onnx
        val (convDirect, aConv, convDim) = OnnxTensor.createTensor(
            env,
            melDirect,
            longArrayOf(1L, numFrames.toLong(), numMels.toLong())
        ).use { melTensor ->
            cSess.run(mapOf("input_features" to melTensor)).use { res ->
                val out = res[0] as OnnxTensor
                val sh = out.info.shape
                val tConv = sh[1].toInt()
                val dConv = sh[2].toInt()
                val buf = allocateDirectFloats(tConv * dConv)
                buf.put(out.floatBuffer)
                buf.rewind()
                Triple(buf, tConv, dConv)
            }
        }

        // Step 2: Compute exact audio token count A & boolean mask [1, aConv]
        val audioTokensLen = featToAudioTokensLen(numFrames, chunkSize = 100)
        if (audioTokensLen <= 0) return@runCatching AsrTranscriptionResult("")

        val mask2d = Array(1) { BooleanArray(aConv) { pos -> pos < audioTokensLen } }

        // Step 3: Run encoder -> slice first A audio feature tokens into DirectFloatBuffer
        val convTensor = OnnxTensor.createTensor(
            env,
            convDirect,
            longArrayOf(1L, aConv.toLong(), convDim.toLong())
        )
        val maskTensor = OnnxTensor.createTensor(env, mask2d)

        val (audioFeatDirect, actualA, hiddenSize) = try {
            eSess.run(
                mapOf(
                    "input_features" to convTensor,
                    "feature_attention_mask" to maskTensor,
                )
            ).use { res ->
                val af = res[0] as OnnxTensor
                val sh = af.info.shape
                val h = sh[2].toInt()
                val validA = minOf(audioTokensLen, sh[1].toInt())
                val srcFb = af.floatBuffer
                srcFb.limit(validA * h)
                srcFb.position(0)
                val dstDirect = allocateDirectFloats(validA * h)
                dstDirect.put(srcFb)
                dstDirect.rewind()
                Triple(dstDirect, validA, h)
            }
        } finally {
            convTensor.close()
            maskTensor.close()
        }

        val rumble = manifest?.takeIf { it.hasRumbleFormatting }
        val formatting = rumble?.let { options.rumbleFormatting ?: RumbleFormatting() }
        val forceLanguage = mapQwenLanguageName(options.language) ?: options.language
            .takeIf { rumble != null && it in rumble.languages }
            ?.let { Locale.forLanguageTag(it).getDisplayLanguage(Locale.ENGLISH) }
        val promptIds = buildQwen3AsrPromptIds(tok, actualA, forceLanguage, formatting)
        if (promptIds.isEmpty()) return@runCatching AsrTranscriptionResult("")

        val configuredMaxNew = manifest?.runtimeConfig?.maxNewTokens ?: 128
        // Dynamic speech-proportional cap: ~6 tokens per audio token + margin, capped at configuredMaxNew
        val adaptiveMaxNew = (actualA * 4 + 24).coerceIn(32, configuredMaxNew)

        val contextLimit = manifest?.runtimeConfig?.maxDecoderContext
        val runtimeMaxLen = if (modelFixedMaxLen > 0) {
            modelFixedMaxLen
        } else {
            (promptIds.size + adaptiveMaxNew + 8).coerceAtMost(contextLimit ?: 1024)
        }
        if (contextLimit != null) {
            require(promptIds.size < minOf(runtimeMaxLen, contextLimit)) {
                "Recording exceeds the model's audio context. Try a shorter recording."
            }
        }

        val maxNew = adaptiveMaxNew.coerceAtMost((minOf(runtimeMaxLen, contextLimit ?: runtimeMaxLen) - promptIds.size - 1).coerceAtLeast(1))

        // Step 4: Allocate KV caches 100% off-heap in DirectByteBuffers & wrap ONCE into OnnxTensors
        val stridePerPos = kvHeads * headDim
        val singleCacheFloats = runtimeMaxLen * stridePerPos
        val cacheShape = longArrayOf(1L, runtimeMaxLen.toLong(), kvHeads.toLong(), headDim.toLong())

        val keyBuffers = Array(numLayers) { allocateDirectFloats(singleCacheFloats) }
        val valBuffers = Array(numLayers) { allocateDirectFloats(singleCacheFloats) }

        val persistentTensors = ArrayList<OnnxTensor>(numLayers * 2 + 2)
        val baseFeed = HashMap<String, OnnxTensor>(numLayers * 2 + 4)

        val eosId = tok.tokenToId("<|im_end|>") ?: 151645
        val eotId = tok.tokenToId("<|endoftext|>") ?: 151643
        val allDecOutputs = dSess.outputNames
        val kvDeltaOnlyOutputs = allDecOutputs.filterTo(LinkedHashSet()) { it != "logits" }

        val generatedIds = ArrayList<Int>(maxNew)

        try {
            val audioFeatTensor = OnnxTensor.createTensor(
                env,
                audioFeatDirect,
                longArrayOf(1L, actualA.toLong(), hiddenSize.toLong())
            ).also { persistentTensors.add(it) }
            baseFeed["audio_features"] = audioFeatTensor

            // Dummy 1-element audio_features tensor used after audio tokens have already been injected
            val dummyAudioTensor = OnnxTensor.createTensor(
                env,
                allocateDirectFloats(hiddenSize),
                longArrayOf(1L, 1L, hiddenSize.toLong())
            ).also { persistentTensors.add(it) }

            for (layer in 0 until numLayers) {
                val kt = OnnxTensor.createTensor(env, keyBuffers[layer], cacheShape)
                    .also { persistentTensors.add(it) }
                val vt = OnnxTensor.createTensor(env, valBuffers[layer], cacheShape)
                    .also { persistentTensors.add(it) }
                baseFeed["cache_key_$layer"] = kt
                baseFeed["cache_value_$layer"] = vt
            }

            var curLen = 0

            // Step 4A: KV-Only Prefill on promptIds[0 until promptIds.size - 1]
            // Contains ALL <|audio_pad|> slots; skips `lm_head` completely to save 60-200 MB RAM!
            if (promptIds.size > 1 && kvDeltaOnlyOutputs.isNotEmpty()) {
                val prefillLen = promptIds.size - 1
                val prefillIds = promptIds.copyOfRange(0, prefillLen)
                runDecoderStep(
                    env = env,
                    dSess = dSess,
                    baseFeed = baseFeed,
                    audioTensorForStep = audioFeatTensor,
                    stepIds = prefillIds,
                    curLen = 0,
                    stridePerPos = stridePerPos,
                    keyBuffers = keyBuffers,
                    valBuffers = valBuffers,
                    requestedOutputs = kvDeltaOnlyOutputs,
                    reusableLogits = null,
                )
                curLen = prefillLen
            }

            // Step 4B: Single-token autoregressive decode starting from the final prompt token
            var currentTokenId = promptIds.last()
            var reusableLogits: FloatArray? = null

            for (step in 0 until maxNew) {
                if (curLen + 1 > runtimeMaxLen) break

                // If promptIds had length 1 (edge case), use real audioFeatTensor on step 0; otherwise dummy
                val stepAudioTensor = if (curLen == 0) audioFeatTensor else dummyAudioTensor

                val logits = runDecoderStep(
                    env = env,
                    dSess = dSess,
                    baseFeed = baseFeed,
                    audioTensorForStep = stepAudioTensor,
                    stepIds = longArrayOf(currentTokenId),
                    curLen = curLen,
                    stridePerPos = stridePerPos,
                    keyBuffers = keyBuffers,
                    valBuffers = valBuffers,
                    requestedOutputs = allDecOutputs,
                    reusableLogits = reusableLogits,
                ) ?: break

                reusableLogits = logits
                curLen += 1

                val nextToken = Qwen3AsrNative.selectNextTokenGreedy(
                    logitsArr = logits,
                    logitsOffset = 0,
                    vocabSize = logits.size,
                    generatedTokens = generatedIds.toIntArray(),
                    suppressTokens = if (step == 0) intArrayOf(eosId, eotId) else null,
                    repetitionPenalty = if (formatting != null) 1f else 1.05f,
                )

                if (nextToken < 0 || nextToken == eosId || nextToken == eotId) {
                    break
                }
                generatedIds.add(nextToken)
                currentTokenId = nextToken.toLong()
            }
        } finally {
            persistentTensors.forEach { runCatching { it.close() } }
        }

        val decodedText = tok.decode(generatedIds.toIntArray(), skipSpecialTokens = true)
        val elapsed = System.currentTimeMillis() - startMs

        AsrTranscriptionResult(
            text = decodedText,
            detectedLanguage = options.language.takeIf { it != "auto" },
            inferenceTimeMs = elapsed,
        )
    }

    private fun runDecoderStep(
        env: OrtEnvironment,
        dSess: OrtSession,
        baseFeed: HashMap<String, OnnxTensor>,
        audioTensorForStep: OnnxTensor,
        stepIds: LongArray,
        curLen: Int,
        stridePerPos: Int,
        keyBuffers: Array<FloatBuffer>,
        valBuffers: Array<FloatBuffer>,
        requestedOutputs: Set<String>,
        reusableLogits: FloatArray?,
    ): FloatArray? {
        val sLen = stepIds.size
        val attnMask = LongArray(sLen) { 1L }
        val cachePos = LongArray(sLen) { (curLen + it).toLong() }

        val idTensor = OnnxTensor.createTensor(
            env,
            LongBuffer.wrap(stepIds),
            longArrayOf(1L, sLen.toLong())
        )
        val attnTensor = OnnxTensor.createTensor(
            env,
            LongBuffer.wrap(attnMask),
            longArrayOf(1L, sLen.toLong())
        )
        val posTensor = OnnxTensor.createTensor(
            env,
            LongBuffer.wrap(cachePos),
            longArrayOf(sLen.toLong())
        )

        return try {
            baseFeed["input_ids"] = idTensor
            baseFeed["audio_features"] = audioTensorForStep
            baseFeed["attention_mask"] = attnTensor
            baseFeed["cache_position"] = posTensor

            dSess.run(baseFeed, requestedOutputs).use { outs ->
                val dstOffset = curLen * stridePerPos
                for (layer in 0 until numLayers) {
                    val kd = outs.get("key_delta_$layer").orElse(null) as? OnnxTensor
                    val vd = outs.get("value_delta_$layer").orElse(null) as? OnnxTensor
                    if (kd != null) {
                        val src = kd.floatBuffer
                        val dst = keyBuffers[layer].duplicate()
                        dst.position(dstOffset)
                        dst.put(src)
                    }
                    if (vd != null) {
                        val src = vd.floatBuffer
                        val dst = valBuffers[layer].duplicate()
                        dst.position(dstOffset)
                        dst.put(src)
                    }
                }

                if ("logits" !in requestedOutputs) {
                    return null
                }

                val logitsTensor = outs.get("logits").get() as OnnxTensor
                val vocab = logitsTensor.info.shape.last().toInt()
                val outLogits = if (reusableLogits != null && reusableLogits.size == vocab) {
                    reusableLogits
                } else {
                    FloatArray(vocab)
                }
                val fb = logitsTensor.floatBuffer
                fb.position((sLen - 1) * vocab)
                fb.get(outLogits, 0, vocab)
                outLogits
            }
        } finally {
            idTensor.close()
            attnTensor.close()
            posTensor.close()
        }
    }

    override fun release() {
        runCatching { convSession?.close() }
        runCatching { encoderSession?.close() }
        runCatching { decoderSession?.close() }
        convSession = null
        encoderSession = null
        decoderSession = null
        tokenizer = null
        manifest = null
    }

    private fun allocateDirectFloats(count: Int): FloatBuffer {
        return ByteBuffer
            .allocateDirect(maxOf(1, count) * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
    }

    private fun mapQwenLanguageName(code: String): String? {
        return when (code.lowercase()) {
            "zh" -> "Chinese"
            "en" -> "English"
            "yue" -> "Cantonese"
            "ja" -> "Japanese"
            "ko" -> "Korean"
            "fr" -> "French"
            "de" -> "German"
            "es" -> "Spanish"
            "ru" -> "Russian"
            else -> null
        }
    }

    companion object {
        internal fun featToAudioTokensLen(featLen: Int, chunkSize: Int = 100): Int {
            if (featLen <= 0) return 0
            fun convOut3xStride2(n: Int): Int {
                var x = (n + 1) / 2
                x = (x + 1) / 2
                return (x + 1) / 2
            }
            fun afterCnn(rem: Int): Int {
                if (rem <= 0) return 0
                var x = Math.floorDiv(rem - 1, 2) + 1
                x = Math.floorDiv(x - 1, 2) + 1
                return Math.floorDiv(x - 1, 2) + 1
            }
            val full = featLen / chunkSize
            val rem = featLen % chunkSize
            val tn = convOut3xStride2(chunkSize)
            return maxOf(0, full * tn + afterCnn(rem))
        }
    }

    private fun resolveFile(modelDir: File, explicitRel: String?, fallbacks: List<String>): File? {
        if (!explicitRel.isNullOrBlank()) {
            val f = File(modelDir, explicitRel)
            if (f.exists()) return f
        }
        for (name in fallbacks) {
            val f = File(modelDir, name)
            if (f.exists()) return f
        }
        return null
    }
}
