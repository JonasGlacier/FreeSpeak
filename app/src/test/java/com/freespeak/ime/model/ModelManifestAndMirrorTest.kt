package com.freespeak.ime.model

import com.freespeak.ime.data.MirrorSource
import com.freespeak.ime.data.UserSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class ModelManifestAndMirrorTest {

    @Test
    fun `builtin catalog contains Fun-ASR-Nano, Qwen3-ASR-0_6B, and Whisper Small`() {
        val ids = BuiltinModelCatalog.models.map { it.id }.toSet()
        assertTrue("fun-asr-nano-int8" in ids)
        assertTrue("qwen3-asr-0.6b-int8" in ids)
        assertTrue("whisper-small-q5_1" in ids)
        assertTrue(BuiltinModelCatalog.models.all { it.files.isNotEmpty() })
    }

    @Test
    fun `mirror URL resolver supports HF-Mirror, ModelScope, and Custom mirror`() {
        val hfUrl = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small-q5_1.bin"
        val msUrl = "https://modelscope.cn/models/example/model.onnx"

        val hfMirrorSettings = UserSettings(mirrorSource = MirrorSource.HF_MIRROR)
        assertEquals(
            "https://hf-mirror.com/ggerganov/whisper.cpp/resolve/main/ggml-small-q5_1.bin",
            hfMirrorSettings.resolveDownloadUrl(hfUrl, msUrl)
        )

        val modelScopeSettings = UserSettings(mirrorSource = MirrorSource.MODELSCOPE)
        assertEquals(msUrl, modelScopeSettings.resolveDownloadUrl(hfUrl, msUrl))

        val customSettings = UserSettings(
            mirrorSource = MirrorSource.CUSTOM,
            customMirrorUrl = "https://my-mirror.example.org/"
        )
        assertEquals(
            "https://my-mirror.example.org/ggerganov/whisper.cpp/resolve/main/ggml-small-q5_1.bin",
            customSettings.resolveDownloadUrl(hfUrl, null)
        )
    }

    @Test
    fun `inferOrLoadManifest auto-detects Qwen3-ASR, Fun-ASR ONNX, and Whisper directories`() {
        val qwenDir = Files.createTempDirectory("test_qwen3").toFile()
        try {
            qwenDir.resolve("conv_frontend.onnx").writeBytes(ByteArray(16))
            qwenDir.resolve("encoder.int8.onnx").writeBytes(ByteArray(16))
            qwenDir.resolve("decoder.int8.onnx").writeBytes(ByteArray(16))
            qwenDir.resolve("vocab.json").writeText("{}")

            val qwenManifest = ModelManifestCodec.inferOrLoadManifest(qwenDir, "id-qwen", "My Qwen3")
            assertEquals(EngineType.QWEN3_ASR, qwenManifest.engineType)
            assertEquals(128, qwenManifest.runtimeConfig.numMels)
        } finally {
            qwenDir.deleteRecursively()
        }

        val whisperDir = Files.createTempDirectory("test_whisper").toFile()
        try {
            whisperDir.resolve("ggml-custom-q8_0.gguf").writeBytes(ByteArray(2048))
            val whisperManifest = ModelManifestCodec.inferOrLoadManifest(whisperDir, "id-w", "Custom Whisper")
            assertEquals(EngineType.WHISPER_CPP, whisperManifest.engineType)
            assertEquals("ggml-custom-q8_0.gguf", whisperManifest.runtimeConfig.primaryModelFile)
        } finally {
            whisperDir.deleteRecursively()
        }
    }

    @Test
    fun `preferred languages summary and effective ASR language resolve cleanly`() {
        val bilingual = UserSettings(
            preferredLanguages = setOf("zh", "en"),
            selectedLanguage = "auto",
        )
        assertEquals("English, 中文", bilingual.formatPreferredLanguagesSummary())
        assertEquals("auto", bilingual.effectiveAsrLanguage)

        val single = UserSettings(
            preferredLanguages = setOf("ja"),
            selectedLanguage = "auto",
        )
        assertEquals("日本語", single.formatPreferredLanguagesSummary())
        assertEquals("ja", single.effectiveAsrLanguage)
    }
}
