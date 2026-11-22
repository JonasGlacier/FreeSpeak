package com.freespeak.ime.model

/**
 * Curated open-weights speech recognition models available out-of-the-box.
 * Users can switch mirrors (HF-Mirror / ModelScope / HuggingFace / Custom),
 * subscribe to external JSON catalogs, or import any custom model locally.
 */
object BuiltinModelCatalog {

    val models: List<ModelManifest> = listOf(
        ModelManifest(
            id = "fun-asr-nano-int8",
            name = "Fun-ASR-Nano (INT8 极速版)",
            description = "阿里通义 FunAudioLLM 最新轻量多语言/方言模型，支持中英自由混说、7 大中文方言与 31 种语言，自带标点，单次前向推理极快。",
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
            name = "Qwen3-ASR-0.6B (INT8 旗舰识别)",
            description = "通义千问最新一代多语言语音识别小模型，中英夹杂、专业术语及自然标点断句表现极佳，推荐 8GB+ 内存机型使用。",
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
            name = "Whisper Small (Q5_1 多语言均衡)",
            description = "OpenAI Whisper Small 量化版（whisper.cpp 原生推理），支持 99 种语言识别与标点恢复，体积与准确率均衡。",
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
                initialPrompt = "以下是包含标点符号的句子，支持中文与 English 混合输入。",
            ),
        ),
        ModelManifest(
            id = "sense-voice-small-int8",
            name = "SenseVoice-Small (INT8 轻量高速)",
            description = "支持中文、粤语、英语、日语、韩语识别与逆文本正则化（自动数字与标点），内存占用低、响应极快。",
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
            name = "Whisper Tiny (Q5_1 32MB 超轻体验)",
            description = "仅约 32MB 的超轻多语言模型，下载只需数秒，适合低配旧手机或快速验证离线语音输入流程。",
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
                initialPrompt = "以下是包含标点符号的句子，支持中文与 English 混合输入。",
            ),
        ),
    )
}
