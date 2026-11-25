package com.freespeak.ime.model.hf

import com.freespeak.ime.data.MirrorSource
import com.freespeak.ime.data.UserSettings
import com.freespeak.ime.model.EngineType
import com.freespeak.ime.model.ModelManifestCodec
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class HuggingFaceModelResolverTest {

    private fun repo(id: String, vararg files: Pair<String, Long>, gated: Boolean = false) = HfModelInfo(
        id = id,
        sha = "0123456789abcdef0123456789abcdef01234567",
        gated = if (gated) JsonPrimitive("manual") else JsonPrimitive(false),
        siblings = files.map { (path, size) ->
            HfSibling(path, size, if (size > 10_000) HfLfsInfo(sha256 = "ab".repeat(32), size = size) else null)
        },
    )

    @Test
    fun `whisper cpp repo splits every ggml bin into variants and skips test and coreml files`() {
        val info = repo(
            "ggerganov/whisper.cpp",
            "README.md" to 3_000,
            "ggml-tiny-q5_1.bin" to 32_152_673,
            "ggml-small.en-q8_0.bin" to 264_000_000,
            "ggml-base.bin" to 147_951_465,
            "for-tests-ggml-tiny.bin" to 75_000,
            "ggml-base-encoder.mlmodelc.zip" to 37_922_638,
        )
        val a = HuggingFaceModelResolver.analyze(info)
        assertEquals(3, a.supported.size)
        assertTrue(a.variants.all { it.engineType == EngineType.WHISPER_CPP })
        val en = a.variants.first { it.label == "small.en-q8_0" }
        assertEquals("Q8_0", en.quantization)
        assertEquals(false, en.supportsTranslateToEnglish)
        assertEquals("F16", a.variants.first { it.label == "base" }.quantization)
        assertTrue(a.issues.isEmpty())
    }

    @Test
    fun `qwen3 asr layout groups conv encoder decoder and nested tokenizer`() {
        val info = repo(
            "csukuangfj2/sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25",
            "conv_frontend.onnx" to 44_148_281,
            "encoder.int8.onnx" to 182_491_662,
            "decoder.int8.onnx" to 755_914_231,
            "test_wavs/zh.wav" to 300_000,
            "tokenizer/merges.txt" to 1_671_853,
            "tokenizer/tokenizer_config.json" to 12_487,
            "tokenizer/vocab.json" to 2_776_833,
        )
        val a = HuggingFaceModelResolver.analyze(info)
        assertEquals(1, a.supported.size)
        val v = a.supported.single()
        assertEquals(EngineType.QWEN3_ASR, v.engineType)
        assertEquals("INT8", v.quantization)
        assertEquals("tokenizer/vocab.json", v.runtimeConfig.vocabFile)
        assertTrue("tokenizer/tokenizer_config.json" in v.files)
        assertFalse(v.files.keys.any { it.endsWith(".wav") })
        assertTrue(a.issues.isEmpty())
    }

    @Test
    fun `sensevoice ctc is supported while unknown model onnx plus tokens is unverified`() {
        val sense = HuggingFaceModelResolver.analyze(
            repo(
                "someone/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17",
                "model.onnx" to 894_000_000,
                "model.int8.onnx" to 239_000_000,
                "tokens.txt" to 316_000,
            )
        )
        assertEquals(2, sense.supported.size)
        assertTrue(sense.supported.all { it.supportsLanguageHint == true && it.runtimeConfig.blankId == 0 })

        val para = HuggingFaceModelResolver.analyze(
            repo("someone/sherpa-onnx-paraformer-zh-2024-03-09", "model.int8.onnx" to 79_000_000, "tokens.txt" to 75_000)
        )
        assertTrue(para.supported.isEmpty())
        assertEquals(1, para.unverified.size)
    }

    @Test
    fun `builtin fun-asr nano repo reuses the curated runtime config`() {
        val a = HuggingFaceModelResolver.analyze(
            repo(
                "csukuangfj/sherpa-onnx-sense-voice-funasr-nano-int8-2025-12-17",
                "model.int8.onnx" to 264_000_000,
                "tokens.txt" to 400_000,
            )
        )
        val v = a.supported.single()
        assertEquals(60514, v.runtimeConfig.blankId)
        assertEquals(false, v.supportsLanguageHint)
    }

    @Test
    fun `unsupported formats are reported with reasons`() {
        val transducer = HuggingFaceModelResolver.analyze(
            repo("x/sherpa-onnx-zipformer-en", "encoder.onnx" to 5_000_000, "decoder.onnx" to 2_000_000, "joiner.onnx" to 1_000_000, "tokens.txt" to 5_000)
        )
        assertTrue(transducer.variants.isEmpty())
        assertTrue(HfIssue.TRANSDUCER in transducer.issues)

        val ct2 = HuggingFaceModelResolver.analyze(
            repo("Systran/faster-whisper-small", "model.bin" to 483_000_000, "config.json" to 2_000, "vocabulary.txt" to 400_000, "tokenizer.json" to 2_000_000)
        )
        assertTrue(ct2.variants.isEmpty())
        assertTrue(HfIssue.CTRANSLATE2 in ct2.issues)

        val gguf = HuggingFaceModelResolver.analyze(repo("x/SenseVoiceSmall-GGUF", "sense-voice-q8_0.gguf" to 250_000_000))
        assertTrue(HfIssue.GGUF in gguf.issues)

        val hf = HuggingFaceModelResolver.analyze(
            repo("openai/whisper-small", "model.safetensors" to 967_000_000, "pytorch_model.bin" to 967_000_000, "config.json" to 2_000)
        )
        assertTrue(hf.variants.isEmpty())
        assertTrue(HfIssue.PYTORCH in hf.issues)

        val whisperOnnx = HuggingFaceModelResolver.analyze(
            repo("csukuangfj/sherpa-onnx-whisper-tiny", "tiny-encoder.onnx" to 30_000_000, "tiny-decoder.onnx" to 80_000_000, "tiny-tokens.txt" to 800_000)
        )
        assertTrue(HfIssue.WHISPER_ONNX in whisperOnnx.issues)

        val empty = HuggingFaceModelResolver.analyze(repo("x/empty", "README.md" to 100))
        assertTrue(HfIssue.NO_WEIGHTS in empty.issues)
    }

    @Test
    fun `gated repos are blocked`() {
        val a = HuggingFaceModelResolver.analyze(repo("x/gated", "ggml-small.bin" to 400_000_000, gated = true))
        assertTrue(a.blocked)
        assertTrue(HfIssue.GATED in a.issues)
    }

    @Test
    fun `generated manifest pins the commit, carries sizes and is detected as complete`() {
        val info = repo("ggerganov/whisper.cpp", "ggml-tiny-q5_1.bin" to 2_048, "ggml-base.bin" to 4_096)
        // Sizes below the 1MB sanity floor are filtered, so use a realistic repo for that check:
        assertTrue(HuggingFaceModelResolver.analyze(info).variants.isEmpty())

        val real = repo("ggerganov/whisper.cpp", "ggml-tiny-q5_1.bin" to 32_152_673, "ggml-base.bin" to 147_951_465)
        val a = HuggingFaceModelResolver.analyze(real)
        val variant = a.variants.first { it.label == "tiny-q5_1" }
        val m = HuggingFaceModelResolver.toManifest(real, variant, a.variants.size)
        assertTrue(m.id.length <= 64)
        assertEquals(m.id, HuggingFaceModelResolver.toManifest(real, variant, a.variants.size).id)
        assertEquals(
            "https://huggingface.co/ggerganov/whisper.cpp/resolve/0123456789abcdef0123456789abcdef01234567/ggml-tiny-q5_1.bin",
            m.files.single().url,
        )
        assertEquals(32_152_673L, m.files.single().sizeBytes)
        assertEquals("ggerganov/whisper.cpp", m.sourceRepo)

        // Mirror rewriting still applies to pinned URLs.
        assertEquals(
            "https://hf-mirror.com/ggerganov/whisper.cpp/resolve/0123456789abcdef0123456789abcdef01234567/ggml-tiny-q5_1.bin",
            UserSettings(mirrorSource = MirrorSource.HF_MIRROR).resolveDownloadUrl(m.files.single().url),
        )

        val dir = Files.createTempDirectory("hf_manifest").toFile()
        try {
            dir.resolve("ggml-tiny-q5_1.bin").writeBytes(ByteArray(1000))
            assertFalse(ModelManifestCodec.isModelDirectoryComplete(dir, m))
            val roundTrip = ModelManifestCodec.parseManifest(ModelManifestCodec.encodeManifest(m))
            assertEquals(m, roundTrip)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `repo references parse ids and links`() {
        assertEquals("ggerganov/whisper.cpp", HuggingFaceClient.parseRepoReference("ggerganov/whisper.cpp"))
        assertEquals(
            "csukuangfj2/sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25",
            HuggingFaceClient.parseRepoReference("https://huggingface.co/csukuangfj2/sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25/tree/main"),
        )
        assertEquals("a/b", HuggingFaceClient.parseRepoReference("hf-mirror.com/a/b"))
        assertNull(HuggingFaceClient.parseRepoReference("whisper"))
        assertNull(HuggingFaceClient.parseRepoReference("https://huggingface.co/datasets/a"))
        assertNull(HuggingFaceClient.parseRepoReference("https://example.com/a/b"))
    }

    @Test
    fun `search helpers handle link headers, rebasing and speech heuristics`() {
        val link = "<https://huggingface.co/api/models?search=x&cursor=abc>; rel=\"next\""
        assertEquals("https://huggingface.co/api/models?search=x&cursor=abc", HuggingFaceClient.parseNextLink(link))
        assertNull(HuggingFaceClient.parseNextLink(null))
        assertEquals(
            "https://hf-mirror.com/api/models?search=x&cursor=abc",
            HuggingFaceClient.rebase("https://huggingface.co/api/models?search=x&cursor=abc", "https://hf-mirror.com/"),
        )
        assertTrue(HuggingFaceClient.looksSpeechRelated(HfModelSummary("k2-fsa/sherpa-onnx-paraformer")))
        assertFalse(HuggingFaceClient.looksSpeechRelated(HfModelSummary("Qwen/Qwen3-8B", tags = listOf("text-generation"))))
        assertTrue(
            HuggingFaceClient.buildSearchUrl("https://huggingface.co", "sense voice", 30, asrOnly = true)
                .contains("pipeline_tag=automatic-speech-recognition")
        )
    }
}
