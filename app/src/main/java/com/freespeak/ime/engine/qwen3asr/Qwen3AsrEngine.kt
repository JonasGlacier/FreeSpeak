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
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * Native ONNX multimodal inference engine for `Qwen3-ASR-0.6B`
 * (`conv_frontend.onnx` + `encoder[.int8].onnx` + `decoder[.int8].onnx` + `vocab.json`).
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
    private var maxTotalLen: Int = 1024
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
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(numThreads.coerceIn(1, 8))
            setInterOpNumThreads(1)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            setMemoryPatternOptimization(false)
        }

        val cSess = env.createSession(convFile.absolutePath, opts)
        val eSess = env.createSession(encFile.absolutePath, opts)
        val dSess = env.createSession(decFile.absolutePath, opts)

        // Inspect KV cache shape from decoder inputs: cache_key_0 .. cache_key_{L-1} -> [1, maxLen, kvHeads, headDim]
        val cacheKeys = dSess.inputInfo.keys
            .filter { it.startsWith("cache_key_") }
            .sortedBy { it.substringAfterLast('_').toIntOrNull() ?: 0 }

        if (cacheKeys.isEmpty()) {
            cSess.close(); eSess.close(); dSess.close()
            error("Decoder missing cache_key_* inputs")
        }

        val firstCacheShape = (dSess.inputInfo[cacheKeys[0]]?.info as? TensorInfo)?.shape
            ?: error("Invalid decoder KV cache shape")

        numLayers = cacheKeys.size
        maxTotalLen = if (firstCacheShape.size >= 4 && firstCacheShape[1] > 0) firstCacheShape[1].toInt() else 1024
        kvHeads = firstCacheShape[2].toInt()
        headDim = firstCacheShape[3].toInt()

        convSession = cSess
        encoderSession = eSess
        decoderSession = dSess
        tokenizer = tok
        this.manifest = manifest
    }

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
        // [numMels, numFrames] channel-first from C++ Slaney Mel extractor
        val melChannelFirst = AudioDspNative.extractWhisperMelSpectrogram(
            pcm16k = pcm16k,
            numMels = numMels,
            padOrTrimSamples = 0,
        )
        val numFrames = melChannelFirst.size / numMels
        if (numFrames <= 0) return@runCatching AsrTranscriptionResult("")

        // Transpose [numMels, numFrames] -> [1, numFrames, numMels] for conv_frontend.onnx
        val melTimeFirst = FloatArray(numFrames * numMels)
        for (m in 0 until numMels) {
            val srcBase = m * numFrames
            for (t in 0 until numFrames) {
                melTimeFirst[t * numMels + m] = melChannelFirst[srcBase + t]
            }
        }

        // Step 1: Run conv_frontend.onnx
        val (convOutFlat, aConv, convDim) = OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(melTimeFirst),
            longArrayOf(1L, numFrames.toLong(), numMels.toLong())
        ).use { melTensor ->
            cSess.run(mapOf("input_features" to melTensor)).use { res ->
                val out = res[0] as OnnxTensor
                val sh = out.info.shape
                val tConv = sh[1].toInt()
                val dConv = sh[2].toInt()
                val arr = FloatArray(tConv * dConv)
                out.floatBuffer.apply { rewind(); get(arr) }
                Triple(arr, tConv, dConv)
            }
        }

        // Step 2: Compute exact audio token count A & boolean mask [1, aConv]
        val audioTokensLen = featToAudioTokensLen(numFrames, chunkSize = 100)
        if (audioTokensLen <= 0) return@runCatching AsrTranscriptionResult("")

        val mask2d = Array(1) { BooleanArray(aConv) { pos -> pos < audioTokensLen } }

        // Step 3: Run encoder.int8.onnx -> audio_features [1, aConv, hiddenSize], slice first A tokens
        val convTensor = OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(convOutFlat),
            longArrayOf(1L, aConv.toLong(), convDim.toLong())
        )
        val maskTensor = OnnxTensor.createTensor(env, mask2d)

        val (audioFeaturesFlat, hiddenSize) = try {
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
                val sliced = FloatArray(validA * h)
                val fb = af.floatBuffer
                fb.rewind()
                fb.get(sliced, 0, validA * h)
                sliced to h
            }
        } finally {
            convTensor.close()
            maskTensor.close()
        }

        val actualA = audioFeaturesFlat.size / hiddenSize
        val promptIds = buildQwen3AsrPromptIds(tok, actualA, options.language)
        val maxNew = (manifest?.runtimeConfig?.maxNewTokens ?: 128)
            .coerceAtMost((maxTotalLen - promptIds.size - 1).coerceAtLeast(1))

        // Step 4: Initialize KV caches [numLayers * 2] of shape [1, maxTotalLen, kvHeads, headDim]
        val stridePerPos = kvHeads * headDim
        val singleCacheSize = maxTotalLen * stridePerPos
        val keyCaches = Array(numLayers) { FloatArray(singleCacheSize) }
        val valCaches = Array(numLayers) { FloatArray(singleCacheSize) }

        val eosId = tok.tokenToId("<|im_end|>") ?: 151645
        val eotId = tok.tokenToId("<|endoftext|>") ?: 151643
        val decOutNames = dSess.outputNames

        val audioFeatTensor = OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(audioFeaturesFlat),
            longArrayOf(1L, actualA.toLong(), hiddenSize.toLong())
        )

        val generatedIds = ArrayList<Int>(maxNew)
        try {
            var curLen = 0
            var stepIds = promptIds

            for (step in 0 until maxNew) {
                val sLen = stepIds.size
                if (curLen + sLen > maxTotalLen) break

                val attnMask = LongArray(sLen) { 1L }
                val cachePos = LongArray(sLen) { (curLen + it).toLong() }

                val createdStepTensors = ArrayList<OnnxTensor>(numLayers * 2 + 3)
                val nextToken = try {
                    val feed = HashMap<String, OnnxTensor>(numLayers * 2 + 4)
                    val idTensor = OnnxTensor.createTensor(
                        env,
                        LongBuffer.wrap(stepIds),
                        longArrayOf(1L, sLen.toLong())
                    ).also { createdStepTensors.add(it) }
                    val attnTensor = OnnxTensor.createTensor(
                        env,
                        LongBuffer.wrap(attnMask),
                        longArrayOf(1L, sLen.toLong())
                    ).also { createdStepTensors.add(it) }
                    val posTensor = OnnxTensor.createTensor(
                        env,
                        LongBuffer.wrap(cachePos),
                        longArrayOf(sLen.toLong())
                    ).also { createdStepTensors.add(it) }

                    feed["input_ids"] = idTensor
                    feed["audio_features"] = audioFeatTensor
                    feed["attention_mask"] = attnTensor
                    feed["cache_position"] = posTensor

                    val cacheShape = longArrayOf(1L, maxTotalLen.toLong(), kvHeads.toLong(), headDim.toLong())
                    for (layer in 0 until numLayers) {
                        val kt = OnnxTensor.createTensor(env, FloatBuffer.wrap(keyCaches[layer]), cacheShape)
                            .also { createdStepTensors.add(it) }
                        val vt = OnnxTensor.createTensor(env, FloatBuffer.wrap(valCaches[layer]), cacheShape)
                            .also { createdStepTensors.add(it) }
                        feed["cache_key_$layer"] = kt
                        feed["cache_value_$layer"] = vt
                    }

                    dSess.run(feed, decOutNames).use { outs ->
                        // Update KV cache deltas in-place for positions [curLen .. curLen + sLen)
                        val dstOffset = curLen * stridePerPos
                        val deltaFloats = sLen * stridePerPos
                        for (layer in 0 until numLayers) {
                            val kd = outs.get("key_delta_$layer").orElse(null) as? OnnxTensor
                            val vd = outs.get("value_delta_$layer").orElse(null) as? OnnxTensor
                            kd?.floatBuffer?.apply {
                                rewind()
                                get(keyCaches[layer], dstOffset, deltaFloats)
                            }
                            vd?.floatBuffer?.apply {
                                rewind()
                                get(valCaches[layer], dstOffset, deltaFloats)
                            }
                        }

                        val logitsTensor = outs.get("logits").get() as OnnxTensor
                        val vocab = logitsTensor.info.shape.last().toInt()
                        val lastTokenLogits = FloatArray(vocab)
                        val fb = logitsTensor.floatBuffer
                        fb.position((sLen - 1) * vocab)
                        fb.get(lastTokenLogits, 0, vocab)

                        Qwen3AsrNative.selectNextTokenGreedy(
                            logitsArr = lastTokenLogits,
                            logitsOffset = 0,
                            vocabSize = vocab,
                            generatedTokens = generatedIds.toIntArray(),
                            suppressTokens = if (step == 0) intArrayOf(eosId, eotId) else null,
                            repetitionPenalty = 1.05f,
                        )
                    }
                } finally {
                    createdStepTensors.forEach { runCatching { it.close() } }
                }

                curLen += sLen
                if (nextToken < 0 || nextToken == eosId || nextToken == eotId) {
                    break
                }
                generatedIds.add(nextToken)
                stepIds = longArrayOf(nextToken.toLong())
            }
        } finally {
            audioFeatTensor.close()
        }

        val decodedText = tok.decode(generatedIds.toIntArray(), skipSpecialTokens = true)
        val elapsed = System.currentTimeMillis() - startMs

        AsrTranscriptionResult(
            text = decodedText,
            detectedLanguage = options.language.takeIf { it != "auto" },
            inferenceTimeMs = elapsed,
        )
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

    private fun buildQwen3AsrPromptIds(
        tok: QwenBpeTokenizer,
        audioTokenCount: Int,
        languageCode: String,
    ): LongArray {
        val audioPadId = tok.tokenToId("<|audio_pad|>") ?: 151676
        val prefixIds = tok.encode("<|im_start|>system\n<|im_end|>\n<|im_start|>user\n<|audio_start|>")
        val forceLangName = mapQwenLanguageName(languageCode)
        val suffixPrompt = if (forceLangName != null) {
            "<|audio_end|><|im_end|>\n<|im_start|>assistant\nlanguage $forceLangName<asr_text>"
        } else {
            "<|audio_end|><|im_end|>\n<|im_start|>assistant\n"
        }
        val suffixIds = tok.encode(suffixPrompt)

        val total = prefixIds.size + audioTokenCount + suffixIds.size
        val result = LongArray(total)
        var pos = 0
        for (id in prefixIds) result[pos++] = id.toLong()
        repeat(audioTokenCount) { result[pos++] = audioPadId.toLong() }
        for (id in suffixIds) result[pos++] = id.toLong()
        return result
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
            else -> null // "auto" lets Qwen3-ASR detect language & code-switching freely
        }
    }

    companion object {
        /**
         * Exact port of Qwen3-ASR `_feat_to_audio_tokens_len_np` using Python floor-division semantics.
         */
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
