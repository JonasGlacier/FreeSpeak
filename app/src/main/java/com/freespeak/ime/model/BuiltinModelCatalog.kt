package com.freespeak.ime.model

import androidx.annotation.StringRes
import com.freespeak.ime.R

object BuiltinModelCatalog {

    @StringRes
    fun localizedDescriptionRes(modelId: String): Int? = when (modelId) {
        "fun-asr-nano-int8" -> R.string.model_desc_funasr_nano
        "qwen3-asr-0.6b-int8" -> R.string.model_desc_qwen3_asr
        "whisper-small-q5_1" -> R.string.model_desc_whisper_small
        "sense-voice-small-int8" -> R.string.model_desc_sensevoice
        "whisper-tiny-q5_1" -> R.string.model_desc_whisper_tiny
        else -> null
    }

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
