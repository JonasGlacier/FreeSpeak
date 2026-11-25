package com.freespeak.ime.engine.canary

import android.os.SystemClock
import com.freespeak.engine.canary.CanaryGgufNative
import com.freespeak.ime.engine.AsrEngine
import com.freespeak.ime.engine.AsrInferenceOptions
import com.freespeak.ime.engine.AsrTranscriptionResult
import com.freespeak.ime.model.CanaryLanguages
import com.freespeak.ime.model.EngineType
import com.freespeak.ime.model.ModelManifest
import java.io.File

internal class CanaryGgufEngine : AsrEngine {
    override val engineType = EngineType.CANARY_GGUF
    private var handle = 0L
    private var manifest: ModelManifest? = null
    override val isLoaded get() = handle != 0L

    override suspend fun load(modelDir: File, manifest: ModelManifest, numThreads: Int): Result<Unit> = runCatching {
        release()
        val root = modelDir.canonicalFile
        val weights = manifest.runtimeConfig.primaryModelFile?.let { File(root, it).canonicalFile }
        require(weights != null && weights.toPath().startsWith(root.toPath()) && weights.isFile) { "Missing Canary GGUF weights" }
        handle = CanaryGgufNative.open(weights.path, numThreads)
        check(isLoaded) { "Could not load Canary GGUF model" }
        this.manifest = manifest
    }

    override suspend fun transcribe(pcm16k: FloatArray, options: AsrInferenceOptions): Result<AsrTranscriptionResult> = runCatching {
        val model = checkNotNull(manifest) { "Canary GGUF model is not loaded" }
        if (pcm16k.isEmpty()) return@runCatching AsrTranscriptionResult("")
        val start = SystemClock.elapsedRealtime()
        val source = CanaryLanguages.source(model, options.language)
        val target = CanaryLanguages.target(model, source, options.targetLanguage)
        AsrTranscriptionResult(
            text = CanaryGgufNative.infer(handle, pcm16k, source, target).decodeToString().trim(),
            inferenceTimeMs = SystemClock.elapsedRealtime() - start,
        )
    }

    override fun release() {
        if (isLoaded) CanaryGgufNative.close(handle)
        handle = 0L
        manifest = null
    }
}
