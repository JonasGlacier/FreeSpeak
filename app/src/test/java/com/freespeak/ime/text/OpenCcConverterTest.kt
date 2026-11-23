package com.freespeak.ime.text

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.util.Locale

class OpenCcConverterTest {
    private fun converter() = OpenCcConverter { name ->
        File("src/main/assets/opencc", name).reader(Charsets.UTF_8)
    }

    @Test fun `phrase mappings disambiguate hair and departure and preserve mixed text`() {
        assertEquals("頭髮、出發、皇后，review Qwen3 0.6B！",
            converter().convert("头发、出发、皇后，review Qwen3 0.6B！", ChineseOutputMode.TRADITIONAL))
    }

    @Test fun `Taiwan vocabulary differs from generic traditional and Hong Kong glyphs`() {
        val converter = converter()
        assertEquals("軟體、記憶體、滑鼠", converter.convert("软件、内存、鼠标", ChineseOutputMode.TAIWAN))
        assertEquals("軟件、內存、鼠標", converter.convert("软件、内存、鼠标", ChineseOutputMode.TRADITIONAL))
        assertEquals("裏面的軟件", converter.convert("里面的软件", ChineseOutputMode.HONG_KONG))
    }

    @Test fun `simplification removes traditional glyphs without changing numbers or punctuation`() {
        assertEquals("头发、出发，English 123！",
            converter().convert("頭髮、出發，English 123！", ChineseOutputMode.SIMPLIFIED))
    }

    @Test fun `original English translation and Japanese do not even load dictionaries`() {
        val converter = OpenCcConverter { error("Dictionary must not be loaded") }
        assertEquals("頭髮 软件", converter.convert("頭髮 软件", ChineseOutputMode.ORIGINAL))
        assertEquals("This is English.", converter.convert("This is English.", ChineseOutputMode.TAIWAN))
        assertEquals("日本語の漢字", converter.convert("日本語の漢字", ChineseOutputMode.SIMPLIFIED))
        assertEquals("図書館", converter.convert("図書館", ChineseOutputMode.SIMPLIFIED, language = "ja"))
        assertEquals("Chinese 软件", converter.convert("Chinese 软件", ChineseOutputMode.TAIWAN, translatedToEnglish = true))
    }

    @Test fun `system follows country and script and explicit selection ignores system`() {
        assertEquals(ChineseOutputMode.TAIWAN, ChineseOutputMode.SYSTEM.resolved(Locale.TAIWAN))
        assertEquals(ChineseOutputMode.HONG_KONG, ChineseOutputMode.SYSTEM.resolved(Locale.forLanguageTag("zh-HK")))
        assertEquals(ChineseOutputMode.HONG_KONG, ChineseOutputMode.SYSTEM.resolved(Locale.forLanguageTag("zh-MO")))
        assertEquals(ChineseOutputMode.TRADITIONAL, ChineseOutputMode.SYSTEM.resolved(Locale.forLanguageTag("zh-Hant")))
        assertEquals(ChineseOutputMode.SIMPLIFIED, ChineseOutputMode.SYSTEM.resolved(Locale.US))
        assertEquals(ChineseOutputMode.ORIGINAL, ChineseOutputMode.ORIGINAL.resolved(Locale.TAIWAN))
    }
}
