package com.freespeak.ime.model

import com.freespeak.ime.R

object BuiltinModelCatalog {
    internal const val FUN_ASR_MLT_ID = "fun-asr-mlt-nano-int8"

    private const val CANARY_REPO = "csukuangfj/sherpa-onnx-nemo-canary-180m-flash-en-es-de-fr-int8"
    private const val CANARY_REVISION = "9077164e0d3dd1d5353743e89ceaa1d3a770838c"

    private fun canaryFile(name: String, bytes: Long, hash: String, repo: String = CANARY_REPO, revision: String = CANARY_REVISION) = ModelFileEntry(
        relativePath = name,
        url = "https://huggingface.co/$repo/resolve/$revision/$name",
        sizeBytes = bytes,
        sha256 = hash,
    )

    fun featureTagResIds(modelId: String): List<Int> = when (modelId) {
        "fun-asr-nano-int8" -> listOf(R.string.tag_fast, R.string.tag_dialects, R.string.tag_no_punctuation)
        FUN_ASR_MLT_ID -> listOf(R.string.tag_dialects, R.string.tag_high_accuracy)
        "sense-voice-small-int8" -> listOf(R.string.tag_fast, R.string.tag_no_punctuation)
        "qwen3-asr-0.6b-int8" -> listOf(R.string.tag_high_accuracy, R.string.tag_code_switching)
        "canary-180m-flash-int8" -> listOf(R.string.tag_speech_translation, R.string.tag_compact)
        "canary-1b-v2-q4_k" -> listOf(R.string.tag_speech_translation, R.string.tag_low_ram)
        "canary-1b-v2-int8" -> listOf(R.string.tag_speech_translation, R.string.tag_high_accuracy)
        "rumble-2-flash-int8" -> listOf(R.string.tag_digits, R.string.tag_filler_removal)
        "fluid-speech-pro-int8" -> listOf(R.string.tag_clean_mode, R.string.tag_filler_removal)
        else -> emptyList()
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
        optional = true,
        tags = listOf("Translation", "Compact"),
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

    private val canaryV2Onnx = canary.copy(
        id = "canary-1b-v2-int8",
        name = "Canary 1B v2 (ONNX INT8)",
        description = "25 European languages. Transcription and bidirectional speech translation with English.",
        version = "1b-v2-int8",
        sizeBytes = 1_416_509_464L,
        languages = CanaryLanguages.v2,
        recommendedRamMb = 2304,
        sourceRepo = "Sarphix/canary-1b-v2-sherpa-onnx-int8",
        sourceRevision = "7d513cd4f39f6c7dc75c13a284b5e345b37f6198",
        optional = true,
        tags = listOf("Translation", "High accuracy"),
    ).let { model ->
        fun file(name: String, bytes: Long, hash: String) = canaryFile(name, bytes, hash, model.sourceRepo!!, model.sourceRevision!!)
        model.copy(files = listOf(
            file("encoder.int8.onnx", 857_602_351L, "0d8f349aaa015d47c81d88c4ba3a5461c19bc09ebb72bf4c61698eec9f09e968"),
            file("decoder.int8.onnx", 558_682_707L, "e68d839a8939a0c1d55d03fd5c6e6d3f113cfc97038027ffe566b8e3a4be2cd8"),
            file("tokens.txt", 224_406L, "399db68418b655f50275779cfe425c55a5ef6dda12cf38375d709566927ef513"),
        ))
    }

    private const val CANARY_GGUF_REPO = "cstr/canary-1b-v2-GGUF"
    private const val CANARY_GGUF_REVISION = "f4a12db73daa964aa56a188826682f6b11fdc960"
    private const val CANARY_GGUF_FILE = "canary-1b-v2-q4_k.gguf"
    private val canaryV2Gguf = canaryV2Onnx.copy(
        id = "canary-1b-v2-q4_k",
        name = "Canary 1B v2 (GGUF Q4_K)",
        engineType = EngineType.CANARY_GGUF,
        version = "1b-v2-q4_k",
        sizeBytes = 610_746_016L,
        quantization = "Q4_K",
        recommendedRamMb = 1536,
        sourceRepo = CANARY_GGUF_REPO,
        sourceRevision = CANARY_GGUF_REVISION,
        optional = false,
        tags = listOf("Translation", "Low RAM"),
        files = listOf(canaryFile(CANARY_GGUF_FILE, 610_746_016L,
            "5027f74ba0eb9bb707b14a11c7a68510a704748bb392e5f3af45115329ee501d", CANARY_GGUF_REPO, CANARY_GGUF_REVISION)),
        runtimeConfig = ModelRuntimeConfig(primaryModelFile = CANARY_GGUF_FILE, numMels = 128),
    )

    private const val RUMBLE_REPO = "TypeWhisper/Rumble-2-Flash-sherpa-onnx-int8"
    private const val RUMBLE_REVISION = "a8128305e8e8c4aa5bf6fcd0a8454347ff80d6d1"
    private fun rumbleFile(path: String, bytes: Long, hash: String) = ModelFileEntry(
        relativePath = path,
        url = "https://huggingface.co/$RUMBLE_REPO/resolve/$RUMBLE_REVISION/$path",
        sizeBytes = bytes,
        sha256 = hash,
    )

    private val rumble = ModelManifest(
        id = "rumble-2-flash-int8",
        name = "Rumble-2-Flash",
        description = "Multilingual transcription with controls for digits, filler words and repeated words.",
        engineType = EngineType.QWEN3_ASR,
        version = "2-flash-int8",
        sizeBytes = 656_199_213L,
        languages = listOf("af", "ar", "bn", "bs", "ca", "ceb", "cs", "da", "de", "el", "en", "es", "eu",
            "fa", "fi", "fil", "fr", "hi", "hr", "hu", "id", "is", "it", "ja", "ko", "lt", "lv", "mk",
            "ms", "mt", "nb", "nl", "pl", "pt", "ro", "ru", "sq", "sr", "sv", "th", "tr", "uk", "vi", "yue", "zh"),
        quantization = "INT8",
        recommendedRamMb = 2304,
        supportsLanguageHint = true,
        optional = true,
        sourceRepo = RUMBLE_REPO,
        sourceRevision = RUMBLE_REVISION,
        tags = listOf("Number digits", "Filler removal"),
        files = listOf(
            rumbleFile("conv_frontend.onnx", 44_148_280L, "00c48c47db2937884636a3d99f23166390088e8c939ff7e9e803e69b8af4c0b5"),
            rumbleFile("encoder.int8.onnx", 182_491_662L, "e6b4847992553dfa57bffda161b8c62d0af79c952c73269e6a2f6dc887f3e5dd"),
            rumbleFile("decoder.int8.onnx", 426_507_858L, "e9be29023d0d52518e75cc4b77abbfc569564c7453ad1f546e50174c9a8b426c"),
            rumbleFile("tokenizer/vocab.json", 1_961_918L, "c5e356c0638515539c196bca5da724fcf35a91fceaf1a2204c2a7193adc21059"),
            rumbleFile("tokenizer/tokenizer_config.json", 13_585L, "c812aa17410d17b1b8c12f499d5359d6d9bd7395729bc7a7e1742aaa21f2b7df"),
            rumbleFile("tokenizer/merges.txt", 1_075_910L, "ca57770275b525f8766ed68bf4b2d1d66b85adc41b06d8b5686cc3ca2b808aa9"),
        ),
        runtimeConfig = ModelRuntimeConfig(
            convFrontendFile = "conv_frontend.onnx",
            encoderFile = "encoder.int8.onnx",
            decoderFile = "decoder.int8.onnx",
            vocabFile = "tokenizer/vocab.json",
            numMels = 128,
            rumbleFormatting = true,
            maxDecoderContext = 512,
        ),
    )

    private const val FLUID_REPO = "FluidVoice-Labs/fluid-speech-pro-onnx"
    private const val FLUID_REVISION = "fa588e9f87f1b9ad0ca2e2d8f7eaf84dc3803581"
    private fun fluidFile(name: String, bytes: Long, hash: String) =
        ModelFileEntry(name, "https://huggingface.co/$FLUID_REPO/resolve/$FLUID_REVISION/$name", sha256 = hash, sizeBytes = bytes)

    private val fluidFiles = listOf(
        fluidFile("encoder-model.int8.onnx", 955_592, "07ad77459bd3bb165c2c53e978ed1dfd64c6e129b18af2ec5ee38343be1d4451"),
        fluidFile("encoder-model.clean.int8.onnx", 1_293_960, "cc9ffaba4d01f3ba4ca276aa7d8b38719266522b426ee093d7f60513823f5b2b"),
        fluidFile("encoder-model.int8.onnx.data", 745_971_712, "d2f8bfbb1fa399f207cde85aa3fabee0d67f6730cb31cbf31832c10de69f9c3c"),
        fluidFile("decoder.int8.onnx", 4_797, "0bd3e225124a8a90373e500f62516270fd85fdf42237aa569489b232d21cac08"),
        fluidFile("decoder.clean.int8.onnx", 4_797, "38b69847defd184b0b4924d161b70cf9b454a2ba2cdf776226f74acc519a7d84"),
        fluidFile("decoder.int8.onnx.data", 47_251_456, "632c3cbf59d3e52d2f7f4dda3bf8e28c045f7a3bb3dbef0f44529ba63926efaa"),
        fluidFile("joiner.int8.onnx", 2_754, "c0e995b659ed5e15fc0c38408ee56cd9a03e6d3329f304c32b9d5017c8cdd381"),
        fluidFile("joiner.clean.int8.onnx", 2_760, "397e03c1de42926dc09a82ad0db3c65de92fb3600141bf5f0ab430a4161962f6"),
        fluidFile("joiner.int8.onnx.data", 6_564_352, "7473427ab99417fae8cf7025ba11507cb5dcc223194bae2f79064dde0936d0af"),
        fluidFile("vocab.txt", 93_939, "d58544679ea4bc6ac563d1f545eb7d474bd6cfa467f0a6e2c1dc1c7d37e3c35d"),
    )

    private val fluidSpeech = ModelManifest(
        id = "fluid-speech-pro-int8",
        name = "Fluid Speech Pro",
        description = "Fluid Speech Pro by FluidVoice (CC BY 4.0). Automatic transcription in 25 European languages with optional clean mode.",
        engineType = EngineType.FLUID_TDT,
        version = "1.0-int8",
        sizeBytes = fluidFiles.sumOf { requireNotNull(it.sizeBytes) },
        languages = CanaryLanguages.v2,
        quantization = "INT8",
        recommendedRamMb = 2304,
        files = fluidFiles,
        sourceRepo = FLUID_REPO,
        sourceRevision = FLUID_REVISION,
        optional = true,
        tags = listOf("Clean mode", "Filler removal"),
    )

    private const val FUN_ASR_MLT_REPO = "luigi000/fun-asr-mlt-nano-onnx-int8-1024"
    private const val FUN_ASR_MLT_REVISION = "39334e8a32d91f46d47f80d96d749409c86a56b1"

    private fun mltFile(path: String, bytes: Long, hash: String) =
        ModelFileEntry(path, "https://huggingface.co/$FUN_ASR_MLT_REPO/resolve/$FUN_ASR_MLT_REVISION/$path", sha256 = hash, sizeBytes = bytes)

    private val mltFiles = listOf(
        mltFile("encoder_adaptor.int8.onnx", 238_277_215, "a10c52d65ec0afbbf29070032bb69aafa2379684e5e95e24818bf29dbcc747d4"),
        mltFile("embedding.int8.onnx", 155_583_235, "168a8d20117666a804db9595b01feff5643c149af0cd324745031d261526f69d"),
        mltFile("llm.int8.onnx", 600_025_528, "1dff1090bd510c7a800771580d10e690260cfeda805c8d4a2464c521df296897"),
        mltFile("tokenizer/vocab.json", 2_776_833, "ca10d7e9fb3ed18575dd1e277a2579c16d108e32f27439684afa0e10b1440910"),
        mltFile("tokenizer/tokenizer_config.json", 9_732, "d5d09f07b48c3086c508b30d1c9114bd1189145b74e982a265350c923acd8101"),
    )

    private val funAsrMlt = ModelManifest(
        id = FUN_ASR_MLT_ID,
        name = "Fun-ASR-MLT-Nano",
        description = "31-language FunAudioLLM model, converted to sherpa-onnx INT8 by luigi000.",
        engineType = EngineType.FUN_ASR_ONNX,
        version = "2512-int8-1024",
        sizeBytes = mltFiles.sumOf { requireNotNull(it.sizeBytes) },
        languages = FunAsrMltLanguages.supported,
        recommendedRamMb = 3072,
        sourceRepo = FUN_ASR_MLT_REPO,
        sourceRevision = FUN_ASR_MLT_REVISION,
        supportsLanguageHint = true,
        files = mltFiles,
        tags = listOf("Chinese dialects", "High accuracy"),
        runtimeConfig = ModelRuntimeConfig(
            encoderFile = "encoder_adaptor.int8.onnx",
            embeddingFile = "embedding.int8.onnx",
            decoderFile = "llm.int8.onnx",
            vocabFile = "tokenizer/vocab.json",
            maxDecoderContext = 1024,
            maxNewTokens = 256,
        ),
    )

    val models: List<ModelManifest> = listOf(
        ModelManifest(
            id = "qwen3-asr-0.6b-int8",
            supportsLanguageHint = true,
            name = "Qwen3-ASR-0.6B",
            description = "Flagship 0.6B multimodal speech model with strong mixed-language and natural punctuation accuracy.",
            engineType = EngineType.QWEN3_ASR,
            version = "2026.03-int8",
            sizeBytes = 985_343_494L,
            languages = listOf("auto", "zh", "en", "yue", "ja", "ko", "fr", "de", "es", "ru"),
            quantization = "INT8",
            recommendedRamMb = 1800,
            tags = listOf("High accuracy", "Code-switching"),
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
        canaryV2Gguf,
        funAsrMlt,
        ModelManifest(
            id = "fun-asr-nano-int8",
            supportsLanguageHint = false,
            optional = true,
            name = "Fun-ASR-Nano",
            description = "Compact multilingual & dialect model by FunAudioLLM without punctuation.",
            engineType = EngineType.FUN_ASR_ONNX,
            version = "2512-int8",
            sizeBytes = 264_471_717L,
            languages = listOf("auto", "zh", "en", "yue", "ja", "ko"),
            quantization = "INT8",
            recommendedRamMb = 600,
            tags = listOf("Fast", "Chinese dialects", "No punctuation"),
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
            id = "sense-voice-small-int8",
            supportsLanguageHint = true,
            optional = true,
            name = "SenseVoice-Small",
            description = "Fast multilingual model (Chinese, Cantonese, English, Japanese, Korean) without punctuation.",
            engineType = EngineType.FUN_ASR_ONNX,
            version = "2025.09-int8",
            sizeBytes = 237_431_441L,
            languages = listOf("auto", "zh", "en", "yue", "ja", "ko"),
            quantization = "INT8",
            recommendedRamMb = 550,
            tags = listOf("Fast", "No punctuation"),
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
        canary,
        canaryV2Onnx,
        rumble,
        fluidSpeech,
    )
}
