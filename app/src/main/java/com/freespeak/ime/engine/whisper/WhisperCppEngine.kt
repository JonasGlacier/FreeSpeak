package com.freespeak.ime.engine.whisper

import com.freespeak.engine.whisper.WhisperNative
import com.freespeak.ime.engine.AsrEngine
import com.freespeak.ime.engine.AsrInferenceOptions
import com.freespeak.ime.engine.AsrTranscriptionResult
import com.freespeak.ime.model.EngineType
import com.freespeak.ime.model.ModelManifest
import java.io.File

class WhisperCppEngine : AsrEngine {

    override val engineType: EngineType = EngineType.WHISPER_CPP

    private var contextPtr: Long = 0L
    private var loadedManifest: ModelManifest? = null

    override val isLoaded: Boolean
        get() = contextPtr != 0L

    override suspend fun load(
        modelDir: File,
        manifest: ModelManifest,
        numThreads: Int,
    ): Result<Unit> = runCatching {
        release()
        if (!WhisperNative.ensureLoaded()) {
            error("无法加载 libfreespeak_whisper.so 原生推理库")
        }

        val weightFile = resolveWeightFile(modelDir, manifest)
            ?: error("模型目录中未找到 .bin / .gguf 权重文件: ${modelDir.absolutePath}")

        val ptr = WhisperNative.initContext(weightFile.absolutePath)
        if (ptr == 0L) {
            error("whisper.cpp 加载模型失败: ${weightFile.name}")
        }
        contextPtr = ptr
        loadedManifest = manifest
    }

    override suspend fun transcribe(
        pcm16k: FloatArray,
        options: AsrInferenceOptions,
    ): Result<AsrTranscriptionResult> = runCatching {
        val ptr = contextPtr
        if (ptr == 0L) error("Whisper 模型尚未加载")
        if (pcm16k.isEmpty()) return@runCatching AsrTranscriptionResult("")

        val startMs = System.currentTimeMillis()
        val prompt = if (options.enableStylePrompt) {
            resolvePrompt(options.language, loadedManifest)
        } else {
            null
        }

        val text = WhisperNative.transcribe(
            contextPtr = ptr,
            pcm16k = pcm16k,
            numThreads = options.numThreads.coerceIn(1, 8),
            language = options.language.ifBlank { "auto" },
            initialPrompt = prompt,
            translateToEnglish = false,
        )
        val detectedLang = WhisperNative.getDetectedLanguage(ptr).ifBlank { null }
        val elapsed = System.currentTimeMillis() - startMs

        AsrTranscriptionResult(
            text = text,
            detectedLanguage = detectedLang,
            inferenceTimeMs = elapsed,
        )
    }

    override fun release() {
        val ptr = contextPtr
        contextPtr = 0L
        loadedManifest = null
        if (ptr != 0L) {
            runCatching { WhisperNative.freeContext(ptr) }
        }
    }

    private fun resolvePrompt(language: String, manifest: ModelManifest?): String? {
        val custom = manifest?.runtimeConfig?.initialPrompt?.takeIf { it.isNotBlank() }
        return when (language.lowercase()) {
            "auto", "zh", "yue" -> custom ?: "以下是普通话与 English 混合的句子，包含标点符号。"
            else -> null
        }
    }

    private fun resolveWeightFile(modelDir: File, manifest: ModelManifest): File? {
        manifest.runtimeConfig.primaryModelFile?.let { rel ->
            val f = File(modelDir, rel)
            if (f.exists() && f.isFile) return f
        }
        return modelDir.walkTopDown().maxDepth(2).firstOrNull {
            it.isFile && (it.name.endsWith(".bin", ignoreCase = true) || it.name.endsWith(".gguf", ignoreCase = true))
        }
    }
}
