package com.freespeak.ime.model

import com.freespeak.ime.data.MirrorSource
import com.freespeak.ime.data.UserSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class ModelManifestAndMirrorTest {

    @Test
    fun `builtin catalog contains curated models with files and tags`() {
        val ids = BuiltinModelCatalog.models.map { it.id }.toSet()
        assertTrue("fun-asr-nano-int8" in ids)
        assertTrue("sense-voice-small-int8" in ids)
        assertTrue("qwen3-asr-0.6b-int8" in ids)
        assertTrue("canary-180m-flash-int8" in ids)
        assertTrue(BuiltinModelCatalog.models.all { it.files.isNotEmpty() })
        assertTrue(BuiltinModelCatalog.models.all { it.tags.isNotEmpty() && BuiltinModelCatalog.tagResIds(it.id).isNotEmpty() })
    }

    @Test
    fun `mirror URL resolver supports HF-Mirror, ModelScope, and Custom mirror`() {
        val hfUrl = "https://huggingface.co/csukuangfj2/sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25/resolve/main/encoder.int8.onnx"
        val msUrl = "https://modelscope.cn/models/example/model.onnx"

        val hfMirrorSettings = UserSettings(mirrorSource = MirrorSource.HF_MIRROR)
        assertEquals(
            "https://hf-mirror.com/csukuangfj2/sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25/resolve/main/encoder.int8.onnx",
            hfMirrorSettings.resolveDownloadUrl(hfUrl, msUrl)
        )

        val modelScopeSettings = UserSettings(mirrorSource = MirrorSource.MODELSCOPE)
        assertEquals(msUrl, modelScopeSettings.resolveDownloadUrl(hfUrl, msUrl))

        val customSettings = UserSettings(
            mirrorSource = MirrorSource.CUSTOM,
            customMirrorUrl = "https://my-mirror.example.org/"
        )
        assertEquals(
            "https://my-mirror.example.org/csukuangfj2/sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25/resolve/main/encoder.int8.onnx",
            customSettings.resolveDownloadUrl(hfUrl, null)
        )
    }

    @Test
    fun `inferOrLoadManifest auto-detects Qwen3-ASR, Fun-ASR ONNX, and Canary GGUF directories`() {
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

        val ggufDir = Files.createTempDirectory("test_canary_gguf").toFile()
        try {
            ggufDir.resolve("canary-1b-v2-q4_k.gguf").writeBytes(ByteArray(2048))
            val ggufManifest = ModelManifestCodec.inferOrLoadManifest(ggufDir, "id-c", "Custom Canary")
            assertEquals(EngineType.CANARY_GGUF, ggufManifest.engineType)
            assertEquals("canary-1b-v2-q4_k.gguf", ggufManifest.runtimeConfig.primaryModelFile)
        } finally {
            ggufDir.deleteRecursively()
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
