package com.freespeak.ime.engine.canary

import android.os.SystemClock
import com.freespeak.ime.engine.AsrEngine
import com.freespeak.ime.engine.AsrInferenceOptions
import com.freespeak.ime.engine.AsrTranscriptionResult
import com.freespeak.ime.model.CanaryLanguages
import com.freespeak.ime.model.EngineType
import com.freespeak.ime.model.ModelManifest
import com.k2fsa.sherpa.onnx.OfflineCanaryModelConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import java.io.File

class CanaryEngine : AsrEngine {
    override val engineType = EngineType.CANARY_ONNX
    private var recognizer: OfflineRecognizer? = null
    private var manifest: ModelManifest? = null
    override val isLoaded get() = recognizer != null

    override suspend fun load(modelDir: File, manifest: ModelManifest, numThreads: Int): Result<Unit> = runCatching {
        release()
        val root = modelDir.canonicalFile
        fun requiredFile(path: String?, label: String): String {
            val file = path?.let { File(root, it).canonicalFile }
            require(file != null && file.toPath().startsWith(root.toPath()) && file.isFile && file.length() > 0L) {
                "Missing Canary $label file"
            }
            return file.path
        }
        val cfg = manifest.runtimeConfig
        recognizer = OfflineRecognizer(
            config = OfflineRecognizerConfig(
                modelConfig = OfflineModelConfig(
                    canary = OfflineCanaryModelConfig(
                        encoder = requiredFile(cfg.encoderFile, "encoder"),
                        decoder = requiredFile(cfg.decoderFile, "decoder"),
                    ),
                    tokens = requiredFile(cfg.vocabFile, "tokens"),
                    numThreads = numThreads.coerceIn(1, 8),
                    provider = "cpu",
                ),
                decodingMethod = "greedy_search",
            ),
        )
        this.manifest = manifest
    }

    override suspend fun transcribe(pcm16k: FloatArray, options: AsrInferenceOptions): Result<AsrTranscriptionResult> = runCatching {
        val engine = checkNotNull(recognizer) { "Canary model is not loaded" }
        val model = checkNotNull(manifest)
        if (pcm16k.isEmpty()) return@runCatching AsrTranscriptionResult("")
        val start = SystemClock.elapsedRealtime()
        val source = CanaryLanguages.source(model, options.language)
        val target = CanaryLanguages.target(model, source, options.targetLanguage)
        val stream = engine.createStream()
        try {
            stream.setOption("src_lang", source)
            stream.setOption("tgt_lang", target)
            stream.acceptWaveform(pcm16k, sampleRate = 16_000)
            engine.decode(stream)
            AsrTranscriptionResult(
                text = engine.getResult(stream).text.trim(),
                inferenceTimeMs = SystemClock.elapsedRealtime() - start,
            )
        } finally {
            stream.release()
        }
    }

    override fun release() {
        recognizer?.release()
        recognizer = null
        manifest = null
    }
}
