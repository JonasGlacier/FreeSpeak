package com.freespeak.ime.engine.fluid

import android.os.SystemClock
import com.freespeak.ime.engine.AsrEngine
import com.freespeak.ime.engine.AsrInferenceOptions
import com.freespeak.ime.engine.AsrTranscriptionResult
import com.freespeak.ime.model.EngineType
import com.freespeak.ime.model.FluidSpeechFiles
import com.freespeak.ime.model.ModelManifest
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import java.io.File

internal class FluidSpeechEngine : AsrEngine {
    override val engineType = EngineType.FLUID_TDT
    private var modelDir: File? = null
    private var threads = 1
    private var recognizer: OfflineRecognizer? = null
    private var loadedClean = false
    override val isLoaded get() = recognizer != null

    override suspend fun load(modelDir: File, manifest: ModelManifest, numThreads: Int): Result<Unit> = runCatching {
        release()
        val root = modelDir.canonicalFile
        for (name in FluidSpeechFiles.required) {
            val file = File(root, name)
            val declared = manifest.files.firstOrNull { it.relativePath == name }?.sizeBytes
            require(file.canonicalFile.parentFile == root && file.isFile && file.length() > 0 &&
                (declared == null || file.length() == declared)) { "Missing or incomplete Fluid Speech Pro file: $name" }
        }
        threads = numThreads.coerceIn(1, 8)
        recognizer = createRecognizer(root, false)
        this.modelDir = root
    }

    private fun createRecognizer(root: File, clean: Boolean): OfflineRecognizer {
        fun graph(component: String) = File(root, FluidSpeechFiles.graph(component, clean)).path
        return OfflineRecognizer(config = OfflineRecognizerConfig(
            modelConfig = OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(encoder = graph("encoder-model"), decoder = graph("decoder"), joiner = graph("joiner")),
                tokens = File(root, "vocab.txt").path,
                numThreads = threads,
                provider = "cpu",
                modelType = "nemo_transducer",
            ),
            decodingMethod = "greedy_search",
        ))
    }

    override suspend fun transcribe(pcm16k: FloatArray, options: AsrInferenceOptions): Result<AsrTranscriptionResult> = runCatching {
        val root = checkNotNull(modelDir) { "Fluid Speech Pro is not loaded" }
        if (pcm16k.isEmpty()) return@runCatching AsrTranscriptionResult("")
        val start = SystemClock.elapsedRealtime()
        // Keep only one set of sessions in memory. Switch graphs when the setting changes.
        if (recognizer == null || loadedClean != options.fluidCleanMode) {
            recognizer?.release()
            recognizer = null
            recognizer = createRecognizer(root, options.fluidCleanMode)
            loadedClean = options.fluidCleanMode
        }
        val engine = checkNotNull(recognizer)
        val stream = engine.createStream()
        try {
            stream.acceptWaveform(pcm16k, sampleRate = 16_000)
            engine.decode(stream)
            AsrTranscriptionResult(engine.getResult(stream).text.trim(), inferenceTimeMs = SystemClock.elapsedRealtime() - start)
        } finally {
            stream.release()
        }
    }

    override fun release() {
        recognizer?.release()
        recognizer = null
        modelDir = null
        loadedClean = false
    }
}
