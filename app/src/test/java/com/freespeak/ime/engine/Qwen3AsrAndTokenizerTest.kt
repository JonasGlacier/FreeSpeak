package com.freespeak.ime.engine

import com.freespeak.engine.qwen3asr.QwenBpeTokenizer
import com.freespeak.engine.qwen3asr.TokenDecodeMode
import com.freespeak.ime.engine.qwen3asr.Qwen3AsrEngine
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files
import java.util.Base64

class Qwen3AsrAndTokenizerTest {

    @Test
    fun `featToAudioTokensLen matches exact Qwen3-ASR Python floor division`() {
        assertEquals(0, Qwen3AsrEngine.featToAudioTokensLen(0))
        // Exact multiple of chunk_size=100 must yield rem=0 -> 13 * (n / 100) + 0
        assertEquals(13, Qwen3AsrEngine.featToAudioTokensLen(100))
        assertEquals(26, Qwen3AsrEngine.featToAudioTokensLen(200))
        assertEquals(1, Qwen3AsrEngine.featToAudioTokensLen(1))
        assertEquals(20, Qwen3AsrEngine.featToAudioTokensLen(150))
    }

    @Test
    fun `Fun-ASR-Nano base64 tokens_txt decodes mixed Chinese-English and punctuation`() {
        val b64 = Base64.getEncoder()
        // Split 3-byte UTF-8 Chinese character '你' (E4 BD A0) across two tokens to test byte-stream reassembly!
        val niBytes = "你".toByteArray(Charsets.UTF_8)
        val part1 = b64.encodeToString(niBytes.copyOfRange(0, 2))
        val part2 = b64.encodeToString(niBytes.copyOfRange(2, 3))
        val hao = b64.encodeToString("好，".toByteArray(Charsets.UTF_8))
        val eng = b64.encodeToString("FreeSpeak！".toByteArray(Charsets.UTF_8))

        val lines = listOf(
            "IQ== 0",
            "Ig== 1",
            "$part1 2",
            "$part2 3",
            "$hao 4",
            "$eng 5",
        )
        val tmpFile = Files.createTempFile("tokens", ".txt").toFile()
        try {
            tmpFile.writeText(lines.joinToString("\n"))
            val tok = QwenBpeTokenizer.loadFromFile(tmpFile)
            assertEquals(TokenDecodeMode.BASE64_BYTES, tok.mode)
            val decoded = tok.decode(intArrayOf(2, 3, 4, 5))
            assertEquals("你好，FreeSpeak！", decoded)
        } finally {
            tmpFile.delete()
        }
    }

    @Test
    fun `Qwen3-ASR tokenizer strips language prefix before asr_text tag and encodes special tokens`() {
        val dir = Files.createTempDirectory("qwen_tok").toFile()
        try {
            dir.resolve("vocab.json").writeText(
                """
                {
                  "l": 10,
                  "a": 11,
                  "nguage": 12,
                  "ĠChinese": 13,
                  "OK": 14,
                  "。": 99999
                }
                """.trimIndent()
            )
            dir.resolve("tokenizer_config.json").writeText(
                """
                {
                  "added_tokens_decoder": {
                    "151644": {"content": "<|im_start|>", "special": true},
                    "151645": {"content": "<|im_end|>", "special": true},
                    "151676": {"content": "<|audio_pad|>", "special": true},
                    "151704": {"content": "<asr_text>", "special": false}
                  }
                }
                """.trimIndent()
            )

            val tok = QwenBpeTokenizer.loadFromDirectory(dir)
            assertEquals(151644, tok.tokenToId("<|im_start|>"))
            assertEquals(151676, tok.tokenToId("<|audio_pad|>"))

            val encoded = tok.encode("<|im_start|>OK<|im_end|>")
            assertEquals(listOf(151644, 14, 151645), encoded.toList())

            // Simulate Qwen3-ASR output: "language Chinese<asr_text>OK"
            val outText = tok.decode(intArrayOf(10, 11, 12, 13, 151704, 14, 151645))
            assertEquals("OK", outText)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `streaming vocab_json parser handles JSON escapes and high token IDs without DOM`() {
        val dir = Files.createTempDirectory("qwen_esc").toFile()
        try {
            dir.resolve("vocab.json").writeText(
                """{"\"": 1, "\\": 2, "\u0120world": 151935}"""
            )
            val tok = QwenBpeTokenizer.loadFromDirectory(dir)
            assertEquals(1, tok.tokenToId("\""))
            assertEquals(2, tok.tokenToId("\\"))
            assertEquals("\"\\ world", tok.decode(intArrayOf(1, 2, 151935)))
        } finally {
            dir.deleteRecursively()
        }
    }
}
