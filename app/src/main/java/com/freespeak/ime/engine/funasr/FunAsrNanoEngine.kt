package com.freespeak.ime.engine.funasr

import ai.onnxruntime.OnnxJavaType
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
import java.nio.IntBuffer
import java.nio.LongBuffer

/**
 * Supports both:
 * 1. Single-graph CTC / SenseVoice-CTC ONNX (`Fun-ASR-Nano` INT8 CTC & `SenseVoice-Small` INT8)
 * 2. 3-graph Encoder+Adaptor + Embedding + Qwen3-0.6B LLM ONNX (`Fun-ASR-Nano` LLM variant)
 */
class FunAsrNanoEngine : AsrEngine {

    override val engineType: EngineType = EngineType.FUN_ASR_ONNX

    private var ortEnv: OrtEnvironment? = null
    private var primarySession: OrtSession? = null
    private var encoderSession: OrtSession? = null
    private var embeddingSession: OrtSession? = null
    private var llmSession: OrtSession? = null
    private var tokenizer: QwenBpeTokenizer? = null
    private var manifest: ModelManifest? = null

    private var lfrM: Int = 7
    private var lfrN: Int = 6
    private var upscaleSamples: Boolean = true
    private var blankId: Int = 0
    private var cmvnNegMean: FloatArray? = null
    private var cmvnInvStd: FloatArray? = null
    private var isLlmTripleGraph: Boolean = false

    override val isLoaded: Boolean
        get() = (primarySession != null || (encoderSession != null && llmSession != null)) && tokenizer != null

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
        val tok = QwenBpeTokenizer.loadFromDirectory(modelDir, cfg.vocabFile)
        tokenizer = tok
        this.manifest = manifest

        val encFile = cfg.encoderFile?.let { File(modelDir, it) }
            ?: File(modelDir, "encoder_adaptor.int8.onnx").takeIf { it.exists() }
        val embFile = cfg.embeddingFile?.let { File(modelDir, it) }
            ?: File(modelDir, "embedding.int8.onnx").takeIf { it.exists() }
        val decFile = cfg.decoderFile?.let { File(modelDir, it) }
            ?: File(modelDir, "llm.int8.onnx").takeIf { it.exists() }

        if (encFile != null && encFile.exists() && embFile != null && embFile.exists() && decFile != null && decFile.exists()) {
            isLlmTripleGraph = true
            val opts = createSessionOptions(numThreads)
            val encSess = env.createSession(encFile.absolutePath, opts)
            val embSess = env.createSession(embFile.absolutePath, opts)
            val llmSess = env.createSession(decFile.absolutePath, opts)

            val meta = encSess.metadata.customMetadata
            lfrM = meta["lfr_window_size"]?.toIntOrNull() ?: cfg.lfrWindowSize
            lfrN = meta["lfr_window_shift"]?.toIntOrNull() ?: cfg.lfrWindowShift
            upscaleSamples = true

            encoderSession = encSess
            embeddingSession = embSess
            llmSession = llmSess
        } else {
            isLlmTripleGraph = false
            val modelFile = resolvePrimaryOnnx(modelDir, manifest)
                ?: error("ONNX model file not found in ${modelDir.absolutePath}")
            val opts = createSessionOptions(numThreads)
            val sess = env.createSession(modelFile.absolutePath, opts)

            val meta = sess.metadata.customMetadata
            lfrM = meta["lfr_window_size"]?.toIntOrNull() ?: cfg.lfrWindowSize
            lfrN = meta["lfr_window_shift"]?.toIntOrNull() ?: cfg.lfrWindowShift
            val normSamplesFlag = meta["normalize_samples"]?.toIntOrNull()
            upscaleSamples = if (normSamplesFlag != null) (normSamplesFlag == 0) else cfg.upscaleSamples
            blankId = meta["blank_id"]?.toIntOrNull() ?: cfg.blankId ?: 0
            cmvnNegMean = parseCsvFloats(meta["neg_mean"])
            cmvnInvStd = parseCsvFloats(meta["inv_stddev"])

            primarySession = sess
        }
    }

    override suspend fun transcribe(
        pcm16k: FloatArray,
        options: AsrInferenceOptions,
    ): Result<AsrTranscriptionResult> = runCatching {
        if (!isLoaded) error("Fun-ASR model is not loaded")
        if (pcm16k.isEmpty()) return@runCatching AsrTranscriptionResult("")

        val startMs = System.currentTimeMillis()
        val text = if (isLlmTripleGraph) {
            transcribeTripleLlm(pcm16k)
        } else {
            transcribeSingleCtc(pcm16k, options.language)
        }
        val elapsed = System.currentTimeMillis() - startMs

        AsrTranscriptionResult(
            text = text,
            detectedLanguage = options.language.takeIf { it != "auto" },
            inferenceTimeMs = elapsed,
        )
    }

    private fun transcribeSingleCtc(pcm16k: FloatArray, language: String): String {
        val env = ortEnv ?: error("ORT environment uninitialized")
        val sess = primarySession ?: error("ONNX session uninitialized")
        val tok = tokenizer ?: error("Tokenizer uninitialized")

        val numMels = manifest?.runtimeConfig?.numMels ?: 80
        val featDim = numMels * maxOf(1, lfrM)
        val features = AudioDspNative.extractKaldiFbankLfrCmvn(
            pcm16k = pcm16k,
            numMels = numMels,
            lfrM = lfrM,
            lfrN = lfrN,
            upscaleSamples = upscaleSamples,
            cmvnNegMean = cmvnNegMean,
            cmvnInvStd = cmvnInvStd,
        )
        if (features.isEmpty()) return ""
        val numFrames = features.size / featDim
        if (numFrames <= 0) return ""

        val createdTensors = ArrayList<OnnxTensor>(4)
        try {
            val inputs = LinkedHashMap<String, OnnxTensor>()
            val inputInfoMap = sess.inputInfo

            for ((name, nodeInfo) in inputInfoMap) {
                val tensorInfo = nodeInfo.info as? TensorInfo ?: continue
                val lower = name.lowercase()
                val tensor = when {
                    lower == "x" || lower == "speech" || lower.contains("feat") -> {
                        OnnxTensor.createTensor(
                            env,
                            FloatBuffer.wrap(features),
                            longArrayOf(1L, numFrames.toLong(), featDim.toLong())
                        )
                    }
                    lower.contains("len") -> {
                        createScalarOr1DIntTensor(env, tensorInfo, numFrames)
                    }
                    lower.contains("lang") -> {
                        val langId = mapSenseVoiceLanguageId(language)
                        createScalarOr1DIntTensor(env, tensorInfo, langId)
                    }
                    lower.contains("norm") -> {
                        // SenseVoice: 14 = <|withitn|> (enables punctuation & inverse text normalization!)
                        createScalarOr1DIntTensor(env, tensorInfo, 14)
                    }
                    else -> null
                }
                if (tensor != null) {
                    createdTensors.add(tensor)
                    inputs[name] = tensor
                }
            }

            sess.run(inputs).use { results ->
                val logitsTensor = results[0] as OnnxTensor
                val shape = logitsTensor.info.shape
                val timeSteps = if (shape.size == 3) shape[1].toInt() else shape[0].toInt()
                val vocabSize = shape.last().toInt()
                val flatLogits = FloatArray(timeSteps * vocabSize)
                val fb = logitsTensor.floatBuffer
                fb.rewind()
                fb.get(flatLogits)

                val tokenIds = if (Qwen3AsrNative.ensureLoaded()) {
                    Qwen3AsrNative.ctcGreedyDecodeIds(flatLogits, timeSteps, vocabSize, blankId)
                } else {
                    fallbackCtcGreedy(flatLogits, timeSteps, vocabSize, blankId)
                }
                return tok.decode(tokenIds, skipSpecialTokens = true)
            }
        } finally {
            createdTensors.forEach { runCatching { it.close() } }
        }
    }

    private fun transcribeTripleLlm(pcm16k: FloatArray): String {
        val env = ortEnv ?: error("ORT environment uninitialized")
        val encSess = encoderSession ?: error("Encoder session uninitialized")
        val embSess = embeddingSession ?: error("Embedding session uninitialized")
        val decSess = llmSession ?: error("LLM session uninitialized")
        val tok = tokenizer ?: error("Tokenizer uninitialized")

        val featDim = 80 * maxOf(1, lfrM)
        val features = AudioDspNative.extractKaldiFbankLfrCmvn(
            pcm16k = pcm16k,
            numMels = 80,
            lfrM = lfrM,
            lfrN = lfrN,
            upscaleSamples = true,
            cmvnNegMean = null,
            cmvnInvStd = null,
        )
        val numFrames = features.size / featDim
        if (numFrames <= 0) return ""

        // 1. Run encoder_adaptor -> [1, T_audio, hiddenDim]
        val encInputName = encSess.inputNames.first()
        val (audioEmbedsFlat, audioTokenLen, hiddenDim) = OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(features),
            longArrayOf(1L, numFrames.toLong(), featDim.toLong())
        ).use { featTensor ->
            encSess.run(mapOf(encInputName to featTensor)).use { res ->
                val out = res[0] as OnnxTensor
                val sh = out.info.shape
                val aLen = sh[1].toInt()
                val hDim = sh[2].toInt()
                val arr = FloatArray(aLen * hDim)
                out.floatBuffer.apply { rewind(); get(arr) }
                Triple(arr, aLen, hDim)
            }
        }

        // 2. Embed prefix & suffix prompt
        val prefixIds = tok.encode("<|im_start|>system\nYou are a helpful assistant.<|im_end|>\n<|im_start|>user\n语音转写：")
        val suffixIds = tok.encode("<|im_end|>\n<|im_start|>assistant\n")
        val prefixEmbeds = lookupEmbeddings(env, embSess, prefixIds, hiddenDim)
        val suffixEmbeds = lookupEmbeddings(env, embSess, suffixIds, hiddenDim)

        var currentEmbeds = Qwen3AsrNative.spliceMultimodalEmbeddings(prefixEmbeds, audioEmbedsFlat, suffixEmbeds)
        var seqLen = currentEmbeds.size / hiddenDim

        val imEndId = tok.tokenToId("<|im_end|>") ?: 151645
        val eosId = tok.tokenToId("<|endoftext|>") ?: 151643
        val maxNewTokens = manifest?.runtimeConfig?.maxNewTokens ?: 100
        val generated = ArrayList<Int>(maxNewTokens)
        val decInputName = decSess.inputNames.first()

        for (step in 0 until maxNewTokens) {
            val nextToken = OnnxTensor.createTensor(
                env,
                FloatBuffer.wrap(currentEmbeds),
                longArrayOf(1L, seqLen.toLong(), hiddenDim.toLong())
            ).use { embTensor ->
                decSess.run(mapOf(decInputName to embTensor)).use { res ->
                    val logitsTensor = res[0] as OnnxTensor
                    val sh = logitsTensor.info.shape
                    val vocab = sh[2].toInt()
                    val lastOffset = (seqLen - 1) * vocab
                    val lastLogits = FloatArray(vocab)
                    val fb = logitsTensor.floatBuffer
                    fb.position(lastOffset)
                    fb.get(lastLogits, 0, vocab)

                    val suppress = if (step == 0) intArrayOf(imEndId, eosId) else null
                    Qwen3AsrNative.selectNextTokenGreedy(
                        logitsArr = lastLogits,
                        logitsOffset = 0,
                        vocabSize = vocab,
                        generatedTokens = generated.toIntArray(),
                        suppressTokens = suppress,
                        repetitionPenalty = 1.06f,
                    )
                }
            }

            if (nextToken < 0 || (step > 0 && (nextToken == imEndId || nextToken == eosId))) {
                break
            }
            generated.add(nextToken)

            val nextEmb = lookupEmbeddings(env, embSess, intArrayOf(nextToken), hiddenDim)
            val grown = FloatArray(currentEmbeds.size + hiddenDim)
            System.arraycopy(currentEmbeds, 0, grown, 0, currentEmbeds.size)
            System.arraycopy(nextEmb, 0, grown, currentEmbeds.size, hiddenDim)
            currentEmbeds = grown
            seqLen++
        }

        return tok.decode(generated.toIntArray(), skipSpecialTokens = true)
    }

    private fun lookupEmbeddings(
        env: OrtEnvironment,
        embSess: OrtSession,
        ids: IntArray,
        hiddenDim: Int,
    ): FloatArray {
        if (ids.isEmpty()) return FloatArray(0)
        val longIds = LongArray(ids.size) { ids[it].toLong() }
        val inName = embSess.inputNames.first()
        return OnnxTensor.createTensor(
            env,
            LongBuffer.wrap(longIds),
            longArrayOf(1L, longIds.size.toLong())
        ).use { idTensor ->
            embSess.run(mapOf(inName to idTensor)).use { res ->
                val out = res[0] as OnnxTensor
                val arr = FloatArray(ids.size * hiddenDim)
                out.floatBuffer.apply { rewind(); get(arr) }
                arr
            }
        }
    }

    override fun release() {
        runCatching { primarySession?.close() }
        runCatching { encoderSession?.close() }
        runCatching { embeddingSession?.close() }
        runCatching { llmSession?.close() }
        primarySession = null
        encoderSession = null
        embeddingSession = null
        llmSession = null
        tokenizer = null
        manifest = null
    }

    private fun createSessionOptions(numThreads: Int): OrtSession.SessionOptions {
        return OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(numThreads.coerceIn(1, 8))
            setInterOpNumThreads(1)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
    }

    private fun createScalarOr1DIntTensor(
        env: OrtEnvironment,
        info: TensorInfo,
        value: Int,
    ): OnnxTensor {
        val shape = if (info.shape.isEmpty()) longArrayOf() else longArrayOf(1L)
        return if (info.type == OnnxJavaType.INT64) {
            OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(value.toLong())), shape)
        } else {
            OnnxTensor.createTensor(env, IntBuffer.wrap(intArrayOf(value)), shape)
        }
    }

    private fun mapSenseVoiceLanguageId(lang: String): Int {
        return when (lang.lowercase()) {
            "zh" -> 3
            "en" -> 4
            "yue" -> 7
            "ja" -> 11
            "ko" -> 12
            else -> 0 // auto
        }
    }

    private fun resolvePrimaryOnnx(modelDir: File, manifest: ModelManifest): File? {
        manifest.runtimeConfig.primaryModelFile?.let { rel ->
            val f = File(modelDir, rel)
            if (f.exists()) return f
        }
        return listOf("model.int8.onnx", "model.onnx").map { File(modelDir, it) }.firstOrNull { it.exists() }
            ?: modelDir.walkTopDown().maxDepth(2).firstOrNull { it.isFile && it.name.endsWith(".onnx", true) }
    }

    private fun parseCsvFloats(csv: String?): FloatArray? {
        if (csv.isNullOrBlank()) return null
        val parts = csv.split(',')
        if (parts.size < 10) return null
        return FloatArray(parts.size) { parts[it].trim().toFloatOrNull() ?: 0f }
    }

    private fun fallbackCtcGreedy(logits: FloatArray, timeSteps: Int, vocabSize: Int, blank: Int): IntArray {
        val out = ArrayList<Int>(timeSteps / 2)
        var prev = -1
        for (t in 0 until timeSteps) {
            val base = t * vocabSize
            var bestId = 0
            var bestVal = logits[base]
            for (v in 1 until vocabSize) {
                val cur = logits[base + v]
                if (cur > bestVal) {
                    bestVal = cur
                    bestId = v
                }
            }
            if (bestId != blank && bestId != prev) {
                out.add(bestId)
            }
            prev = bestId
        }
        return out.toIntArray()
    }
}
