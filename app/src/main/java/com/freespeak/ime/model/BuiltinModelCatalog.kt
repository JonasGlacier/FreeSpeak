package com.freespeak.ime.model

import androidx.annotation.StringRes
import com.freespeak.ime.R

object BuiltinModelCatalog {

    private const val CANARY_REPO = "csukuangfj/sherpa-onnx-nemo-canary-180m-flash-en-es-de-fr-int8"
    private const val CANARY_REVISION = "9077164e0d3dd1d5353743e89ceaa1d3a770838c"

    private fun canaryFile(name: String, bytes: Long, hash: String) = ModelFileEntry(
        relativePath = name,
        url = "https://huggingface.co/$CANARY_REPO/resolve/$CANARY_REVISION/$name",
        sizeBytes = bytes,
        sha256 = hash,
    )

    private const val WHISPER_MEDIUM_SIZE_BYTES = 539_212_467L

    @StringRes
    fun localizedDescriptionRes(modelId: String): Int? = when (modelId) {
        "canary-180m-flash-int8" -> R.string.model_desc_canary
        "fun-asr-nano-int8" -> R.string.model_desc_funasr_nano
        "qwen3-asr-0.6b-int8" -> R.string.model_desc_qwen3_asr
        "whisper-small-q5_1" -> R.string.model_desc_whisper_small
        "whisper-medium-q5_0" -> R.string.model_desc_whisper_medium
        "sense-voice-small-int8" -> R.string.model_desc_sensevoice
        "whisper-tiny-q5_1" -> R.string.model_desc_whisper_tiny
        else -> null
    }

    val canary = ModelManifest(
        id = "canary-180m-flash-int8",
        name = "Canary 180M Flash",
        description = "Offline transcription in English, German, Spanish and French, with bidirectional speech translation between English and the other three languages.",
        engineType = EngineType.CANARY_ONNX,
        version = "180m-flash-int8",
        languages = CanaryLanguages.supported,
        sizeBytes = 207_170_046L,
        quantization = "INT8",
        recommendedRamMb = 512,
        sourceRepo = CANARY_REPO,
        sourceRevision = CANARY_REVISION,
        supportsLanguageHint = true,
        supportsTranslateToEnglish = false, // Canary uses a target-language picker instead.
        files = listOf(
            canaryFile("encoder.int8.onnx", 132_678_643L, "7a75b4e2a5857a6dcc0819503bbe3fad66943db4a3ccf21d3f27c633667d303f"),
            canaryFile("decoder.int8.onnx", 74_437_848L, "e41a2ab9c0c2fe81a1e8ade5a45fb02a74bc4db7d1f91b89a54a25e2cf79cba2"),
            canaryFile("tokens.txt", 53_555L, "2dae6fc7815f9640645e0c765522b278ee0cef49b482d91f6913e334628d3e77"),
        ),
        runtimeConfig = ModelRuntimeConfig(
            encoderFile = "encoder.int8.onnx",
            decoderFile = "decoder.int8.onnx",
            vocabFile = "tokens.txt",
        ),
    )

    val models: List<ModelManifest> = listOf(
        ModelManifest(
            id = "fun-asr-nano-int8",
            supportsLanguageHint = false,
            supportsTranslateToEnglish = false,
            name = "Fun-ASR-Nano",
            description = "Compact multilingual & dialect model by FunAudioLLM with built-in punctuation and code-switching.",
            engineType = EngineType.FUN_ASR_ONNX,
            version = "2512-int8",
            sizeBytes = 264_471_717L,
            languages = listOf("auto", "zh", "en", "yue", "ja", "ko"),
            quantization = "INT8",
            recommendedRamMb = 600,
            files = listOf(
                ModelFileEntry(
                    relativePath = "model.int8.onnx",
                    url = "https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-funasr-nano-int8-2025-12-17/resolve/main/model.int8.onnx",
                ),
                ModelFileEntry(
                    relativePath = "tokens.txt",
                    url = "https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-funasr-nano-int8-2025-12-17/resolve/main/tokens.txt",
                ),
            ),
            runtimeConfig = ModelRuntimeConfig(
                primaryModelFile = "model.int8.onnx",
                vocabFile = "tokens.txt",
                numMels = 80,
                lfrWindowSize = 7,
                lfrWindowShift = 6,
                upscaleSamples = true,
                blankId = 60514,
            ),
        ),
        ModelManifest(
            id = "qwen3-asr-0.6b-int8",
            supportsLanguageHint = true,
            supportsTranslateToEnglish = false,
            name = "Qwen3-ASR-0.6B",
            description = "Flagship 0.6B multimodal speech model with strong mixed-language and natural punctuation accuracy.",
            engineType = EngineType.QWEN3_ASR,
            version = "2026.03-int8",
            sizeBytes = 985_343_494L,
            languages = listOf("auto", "zh", "en", "yue", "ja", "ko", "fr", "de", "es", "ru"),
            quantization = "INT8",
            recommendedRamMb = 1800,
            files = listOf(
                ModelFileEntry(
                    relativePath = "conv_frontend.onnx",
                    url = "https://huggingface.co/csukuangfj2/sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25/resolve/main/conv_frontend.onnx",
                    modelScopeUrl = "https://modelscope.cn/models/zengshuishui/Qwen3-ASR-onnx/resolve/master/conv_frontend.onnx",
                ),
                ModelFileEntry(
                    relativePath = "encoder.int8.onnx",
                    url = "https://huggingface.co/csukuangfj2/sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25/resolve/main/encoder.int8.onnx",
                    modelScopeUrl = "https://modelscope.cn/models/zengshuishui/Qwen3-ASR-onnx/resolve/master/encoder.int8.onnx",
                ),
                ModelFileEntry(
                    relativePath = "decoder.int8.onnx",
                    url = "https://huggingface.co/csukuangfj2/sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25/resolve/main/decoder.int8.onnx",
                    modelScopeUrl = "https://modelscope.cn/models/zengshuishui/Qwen3-ASR-onnx/resolve/master/decoder.int8.onnx",
                ),
                ModelFileEntry(
                    relativePath = "tokenizer/vocab.json",
                    url = "https://huggingface.co/csukuangfj2/sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25/resolve/main/tokenizer/vocab.json",
                ),
                ModelFileEntry(
                    relativePath = "tokenizer/tokenizer_config.json",
                    url = "https://huggingface.co/csukuangfj2/sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25/resolve/main/tokenizer/tokenizer_config.json",
                ),
            ),
            runtimeConfig = ModelRuntimeConfig(
                convFrontendFile = "conv_frontend.onnx",
                encoderFile = "encoder.int8.onnx",
                decoderFile = "decoder.int8.onnx",
                vocabFile = "tokenizer/vocab.json",
                numMels = 128,
                maxNewTokens = 128,
            ),
        ),
        canary,
        ModelManifest(
            id = "whisper-small-q5_1",
            supportsLanguageHint = true,
            supportsTranslateToEnglish = true,
            name = "Whisper Small",
            description = "Balanced 99-language Whisper model running on whisper.cpp.",
            engineType = EngineType.WHISPER_CPP,
            version = "q5_1",
            sizeBytes = 190_085_487L,
            languages = listOf("auto", "zh", "en", "ja", "ko", "fr", "de", "es", "ru"),
            quantization = "Q5_1",
            recommendedRamMb = 500,
            files = listOf(
                ModelFileEntry(
                    relativePath = "ggml-small-q5_1.bin",
                    url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small-q5_1.bin",
                )
            ),
            runtimeConfig = ModelRuntimeConfig(
                primaryModelFile = "ggml-small-q5_1.bin",
            ),
        ),
        ModelManifest(
            id = "whisper-medium-q5_0",
            supportsLanguageHint = true,
            supportsTranslateToEnglish = true,
            name = "Whisper Medium",
            description = "Larger multilingual Whisper model for accuracy testing. Slower and more memory-intensive than Whisper Small.",
            engineType = EngineType.WHISPER_CPP,
            version = "q5_0",
            sizeBytes = WHISPER_MEDIUM_SIZE_BYTES,
            languages = listOf("auto", "zh", "en", "ja", "ko", "fr", "de", "es", "ru"),
            quantization = "Q5_0",
            recommendedRamMb = 1024,
            files = listOf(
                ModelFileEntry(
                    relativePath = "ggml-medium-q5_0.bin",
                    url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/5359861c739e955e79d9a303bcbc70fb988958b1/ggml-medium-q5_0.bin",
                    sha256 = "19fea4b380c3a618ec4723c3eef2eb785ffba0d0538cf43f8f235e7b3b34220f",
                    sizeBytes = WHISPER_MEDIUM_SIZE_BYTES,
                ),
            ),
            runtimeConfig = ModelRuntimeConfig(
                primaryModelFile = "ggml-medium-q5_0.bin",
            ),
        ),
        ModelManifest(
            id = "sense-voice-small-int8",
            supportsLanguageHint = true,
            supportsTranslateToEnglish = false,
            name = "SenseVoice-Small",
            description = "Fast multilingual model (Chinese, Cantonese, English, Japanese, Korean) with punctuation & ITN.",
            engineType = EngineType.FUN_ASR_ONNX,
            version = "2025.09-int8",
            sizeBytes = 237_431_441L,
            languages = listOf("auto", "zh", "en", "yue", "ja", "ko"),
            quantization = "INT8",
            recommendedRamMb = 550,
            files = listOf(
                ModelFileEntry(
                    relativePath = "model.int8.onnx",
                    url = "https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09/resolve/main/model.int8.onnx",
                ),
                ModelFileEntry(
                    relativePath = "tokens.txt",
                    url = "https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09/resolve/main/tokens.txt",
                ),
            ),
            runtimeConfig = ModelRuntimeConfig(
                primaryModelFile = "model.int8.onnx",
                vocabFile = "tokens.txt",
                numMels = 80,
                lfrWindowSize = 7,
                lfrWindowShift = 6,
                upscaleSamples = true,
                blankId = 0,
            ),
        ),
        ModelManifest(
            id = "whisper-tiny-q5_1",
            supportsLanguageHint = true,
            supportsTranslateToEnglish = true,
            name = "Whisper Tiny",
            description = "Ultra-light 32 MB model for low-memory devices or quick testing.",
            engineType = EngineType.WHISPER_CPP,
            version = "q5_1",
            sizeBytes = 32_152_673L,
            languages = listOf("auto", "zh", "en", "ja", "ko", "fr", "de", "es", "ru"),
            quantization = "Q5_1",
            recommendedRamMb = 220,
            files = listOf(
                ModelFileEntry(
                    relativePath = "ggml-tiny-q5_1.bin",
                    url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny-q5_1.bin",
                )
            ),
            runtimeConfig = ModelRuntimeConfig(
                primaryModelFile = "ggml-tiny-q5_1.bin",
            ),
        ),
    )
}
