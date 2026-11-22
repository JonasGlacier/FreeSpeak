package com.freespeak.ime.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
enum class EngineType(val displayName: String) {
    WHISPER_CPP("whisper.cpp (GGML/GGUF)"),
    FUN_ASR_ONNX("Fun-ASR / SenseVoice (ONNX)"),
    QWEN3_ASR("Qwen3-ASR (ONNX Multimodal)"),
}

@Serializable
data class ModelFileEntry(
    val relativePath: String,
    val url: String,
    val modelScopeUrl: String? = null,
    val sha256: String? = null,
    val extractArchive: Boolean = false,
)

@Serializable
data class ModelRuntimeConfig(
    val primaryModelFile: String? = null,
    val convFrontendFile: String? = null,
    val encoderFile: String? = null,
    val decoderFile: String? = null,
    val embeddingFile: String? = null,
    val vocabFile: String? = null,
    val numMels: Int = 80,
    val lfrWindowSize: Int = 7,
    val lfrWindowShift: Int = 6,
    val upscaleSamples: Boolean = true,
    val blankId: Int? = null,
    val initialPrompt: String? = null,
    val maxNewTokens: Int = 128,
)

/**
 * Declarative `model.json` manifest allowing users and community catalogs to plug in new
 * speech recognition models without waiting for app updates.
 */
@Serializable
data class ModelManifest(
    val id: String,
    val name: String,
    val description: String,
    val engineType: EngineType,
    val version: String = "1.0",
    val sizeBytes: Long = 0L,
    val languages: List<String> = listOf("zh", "en", "ja", "ko", "yue"),
    val quantization: String = "INT8",
    val recommendedRamMb: Int = 1024,
    val files: List<ModelFileEntry> = emptyList(),
    val runtimeConfig: ModelRuntimeConfig = ModelRuntimeConfig(),
    val sourceTag: String = "builtin",
)

@Serializable
data class ExternalModelCatalog(
    val catalogName: String = "Community Catalog",
    val updatedAt: String = "",
    val models: List<ModelManifest> = emptyList(),
)

object ModelManifestCodec {
    val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    const val MANIFEST_FILE_NAME = "model.json"

    fun parseManifest(rawJson: String): ModelManifest =
        json.decodeFromString(ModelManifest.serializer(), rawJson)

    fun encodeManifest(manifest: ModelManifest): String =
        json.encodeToString(ModelManifest.serializer(), manifest)

    fun parseCatalog(rawJson: String): ExternalModelCatalog =
        json.decodeFromString(ExternalModelCatalog.serializer(), rawJson)

    /**
     * Inspects an extracted or imported directory and automatically synthesizes a [ModelManifest]
     * if `model.json` was not bundled inside the archive.
     */
    fun inferOrLoadManifest(
        modelDir: File,
        fallbackId: String,
        fallbackName: String,
        preferredEngine: EngineType? = null,
    ): ModelManifest {
        val manifestFile = File(modelDir, MANIFEST_FILE_NAME)
        if (manifestFile.exists()) {
            runCatching { return parseManifest(manifestFile.readText()) }
        }
        // Also check 1 nested directory in case archive extracted a single top-level folder
        val subDirs = modelDir.listFiles()?.filter { it.isDirectory }.orEmpty()
        if (subDirs.size == 1) {
            val nestedManifest = File(subDirs[0], MANIFEST_FILE_NAME)
            if (nestedManifest.exists()) {
                runCatching { return parseManifest(nestedManifest.readText()) }
            }
        }

        val allFiles = modelDir.walkTopDown()
            .maxDepth(3)
            .filter { it.isFile && it.name != MANIFEST_FILE_NAME }
            .toList()

        val relPaths = allFiles.map { it.relativeTo(modelDir).invariantSeparatorsPath }
        val totalBytes = allFiles.sumOf { it.length() }

        // 1. Check for Qwen3-ASR ONNX layout (conv_frontend + encoder + decoder)
        val convFrontend = relPaths.find { it.endsWith("conv_frontend.onnx", ignoreCase = true) }
        val encoderOnnx = relPaths.find {
            it.contains("encoder", ignoreCase = true) && it.endsWith(".onnx", ignoreCase = true)
        }
        val decoderOnnx = relPaths.find {
            (it.contains("decoder", ignoreCase = true) || it.contains("llm", ignoreCase = true)) &&
                it.endsWith(".onnx", ignoreCase = true)
        }
        val embeddingOnnx = relPaths.find {
            it.contains("embedding", ignoreCase = true) && it.endsWith(".onnx", ignoreCase = true)
        }
        val vocabRel = relPaths.find {
            it.endsWith("tokenizer.json", ignoreCase = true) ||
                it.endsWith("vocab.json", ignoreCase = true) ||
                it.endsWith("tokens.txt", ignoreCase = true)
        }

        if (preferredEngine == EngineType.QWEN3_ASR || (convFrontend != null && encoderOnnx != null && decoderOnnx != null)) {
            return ModelManifest(
                id = fallbackId,
                name = fallbackName,
                description = "本地导入的 Qwen3-ASR 多语言语音识别模型",
                engineType = EngineType.QWEN3_ASR,
                sizeBytes = totalBytes,
                quantization = if (relPaths.any { it.contains("int8") }) "INT8" else "FP32/FP16",
                runtimeConfig = ModelRuntimeConfig(
                    convFrontendFile = convFrontend,
                    encoderFile = encoderOnnx,
                    decoderFile = decoderOnnx,
                    vocabFile = vocabRel,
                    numMels = 128,
                ),
                sourceTag = "local",
            )
        }

        // 2. Check for Fun-ASR-Nano LLM 3-part ONNX or single CTC ONNX
        if (preferredEngine == EngineType.FUN_ASR_ONNX ||
            (encoderOnnx != null && decoderOnnx != null && embeddingOnnx != null) ||
            relPaths.any { it.endsWith(".onnx", ignoreCase = true) }
        ) {
            val primaryOnnx = relPaths.find { it.endsWith("model.int8.onnx") }
                ?: relPaths.find { it.endsWith("model.onnx") }
                ?: relPaths.firstOrNull { it.endsWith(".onnx") }

            return ModelManifest(
                id = fallbackId,
                name = fallbackName,
                description = "本地导入的 ONNX 语音识别模型",
                engineType = EngineType.FUN_ASR_ONNX,
                sizeBytes = totalBytes,
                quantization = if (relPaths.any { it.contains("int8") }) "INT8" else "FP32",
                runtimeConfig = ModelRuntimeConfig(
                    primaryModelFile = primaryOnnx,
                    encoderFile = encoderOnnx,
                    decoderFile = decoderOnnx,
                    embeddingFile = embeddingOnnx,
                    vocabFile = vocabRel,
                    numMels = 80,
                ),
                sourceTag = "local",
            )
        }

        // 3. Default: Whisper GGML / GGUF (.bin / .gguf)
        val ggmlBin = relPaths.firstOrNull {
            it.endsWith(".bin", ignoreCase = true) || it.endsWith(".gguf", ignoreCase = true)
        } ?: throw IllegalArgumentException("未在导入目录中识别到受支持的模型权重 (.bin / .gguf / .onnx)")

        return ModelManifest(
            id = fallbackId,
            name = fallbackName,
            description = "本地导入的 Whisper GGML/GGUF 模型",
            engineType = EngineType.WHISPER_CPP,
            sizeBytes = totalBytes,
            quantization = ggmlBin.substringAfterLast('-', "GGML").substringBefore('.').uppercase(),
            runtimeConfig = ModelRuntimeConfig(
                primaryModelFile = ggmlBin,
            ),
            sourceTag = "local",
        )
    }

    /**
     * Checks whether all required weight files for [manifest] exist in [modelDir].
     */
    fun isModelDirectoryComplete(modelDir: File, manifest: ModelManifest): Boolean {
        if (!modelDir.exists() || !modelDir.isDirectory) return false
        if (manifest.files.isNotEmpty() && manifest.files.none { it.extractArchive }) {
            return manifest.files.all { entry ->
                val f = File(modelDir, entry.relativePath)
                f.exists() && f.length() > 0L
            }
        }
        val cfg = manifest.runtimeConfig
        return when (manifest.engineType) {
            EngineType.WHISPER_CPP -> {
                val p = cfg.primaryModelFile
                if (p != null) {
                    File(modelDir, p).let { it.exists() && it.length() > 1024L }
                } else {
                    modelDir.walkTopDown().maxDepth(2).any {
                        it.isFile && (it.name.endsWith(".bin") || it.name.endsWith(".gguf"))
                    }
                }
            }
            EngineType.FUN_ASR_ONNX -> {
                val hasTriple = cfg.encoderFile != null && cfg.decoderFile != null && cfg.embeddingFile != null &&
                    File(modelDir, cfg.encoderFile).exists() &&
                    File(modelDir, cfg.decoderFile).exists() &&
                    File(modelDir, cfg.embeddingFile).exists()
                val hasSingle = cfg.primaryModelFile != null && File(modelDir, cfg.primaryModelFile).exists()
                hasTriple || hasSingle
            }
            EngineType.QWEN3_ASR -> {
                val enc = cfg.encoderFile
                val dec = cfg.decoderFile
                enc != null && dec != null && File(modelDir, enc).exists() && File(modelDir, dec).exists()
            }
        }
    }
}
