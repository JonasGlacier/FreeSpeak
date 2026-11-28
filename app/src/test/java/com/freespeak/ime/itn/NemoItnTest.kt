package com.freespeak.ime.itn

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class NemoItnTest {
    @Test fun resolvesOutputLanguageWithoutGuessingSharedScripts() {
        assertEquals("de", resolveItnLanguage("auto", "de", "en", "en"))
        assertEquals("fr", resolveItnLanguage("auto", "same_as_source", "fr-FR", "en"))
        assertEquals("pt", resolveItnLanguage("pt", "de", "en", "en"))
        assertNull(resolveItnLanguage("auto", null, null, "auto"))
        assertNull(resolveItnLanguage("auto", null, "uk", "ru"))
        assertNull(resolveItnLanguage("auto", null, "yue", "zh"))
        assertEquals("zh", resolveItnLanguage("auto", null, "zh-TW", "auto"))
    }

    @Test fun preventsPureEnglishFromEnteringCjkGrammarsAndRoutesViaPreferredLanguage() {
        val english = "There are thirty-five students in the classroom."
        // Even if the ASR language hint was "zh", pure Latin text must NOT enter "zh" ITN;
        // if "en" is the user's sole Latin preferred language, it routes to "en".
        assertEquals(
            "en",
            resolveItnLanguage("auto", null, "zh", "zh", text = english, preferredLanguages = setOf("zh", "en")),
        )
        // If Dutch ("nl") is the user's Latin language, never misroute to English.
        assertNull(
            resolveItnLanguage("auto", null, "zh", "zh", text = english, preferredLanguages = setOf("zh", "nl")),
        )
        // Forced "zh" on pure English skips ITN cleanly instead of corrupting text.
        assertNull(
            resolveItnLanguage("zh", null, "en", "en", text = english, preferredLanguages = setOf("zh", "en")),
        )
        // Unhinted ("auto") Chinese text routes to "zh" when "zh" is the user's Han language.
        assertEquals(
            "zh",
            resolveItnLanguage("auto", null, null, "auto", text = "百分之六十八", preferredLanguages = setOf("zh", "en")),
        )
        // Unhinted Kana text unambiguously routes to "ja".
        assertEquals(
            "ja",
            resolveItnLanguage("auto", null, null, "auto", text = "百二十三パーセント", preferredLanguages = setOf("zh", "en")),
        )
    }

    @Test fun expandsEnglishHyphenatedNumeralsWithoutBreakingCompoundWords() {
        assertEquals(
            "There are thirty five students in the classroom.",
            ItnTextPreprocessor.preprocessClause("There are thirty-five students in the classroom.", "en"),
        )
        assertEquals(
            "She finished in twenty first place out of two hundred fifty.",
            ItnTextPreprocessor.preprocessClause("She finished in twenty-first place out of two-hundred-fifty.", "en"),
        )
        // Must NOT break compound adjectives, ordinary words, ranges, or French hyphens.
        assertEquals(
            "A well-known twenty-five-year-old played one-on-one.",
            ItnTextPreprocessor.preprocessClause("A well-known twenty-five-year-old played one-on-one.", "en"),
        )
        assertEquals(
            "About twenty-thirty people came.",
            ItnTextPreprocessor.preprocessClause("About twenty-thirty people came.", "en"),
        )
        assertEquals(
            "Il y a vingt-trois élèves.",
            ItnTextPreprocessor.preprocessClause("Il y a vingt-trois élèves.", "fr"),
        )
    }

    @Test fun nemoProcessorHandlesHyphensPerTokenVerbalizationAndTrailingPunctuation() {
        val enPack = ItnPack("en", "rev-1", emptyList(), ItnPack.ENGINE_NEMO)
        val processor = NemoItnProcessor(
            withInstalledPack = { lang, block -> if (lang == "en") block(enPack, File(".")) else null },
            rewriter = { _, classify, input ->
                if (classify) {
                    when (input) {
                        "There are thirty five students in the classroom." ->
                            """tokens { name: "There" } tokens { name: "are" } tokens { cardinal { integer: "35" } } tokens { name: "students" } tokens { name: "in" } tokens { name: "the" } tokens { name: "classroom." }"""
                        "I bought twenty five." ->
                            """tokens { name: "I" } tokens { name: "bought" } tokens { cardinal { integer: "25" } } tokens { name: "." }"""
                        else -> null
                    }
                } else {
                    when (input) {
                        """tokens { cardinal { integer: "35" } }""" -> "35"
                        """tokens { cardinal { integer: "25" } }""" -> "25"
                        else -> null
                    }
                }
            },
        )

        assertEquals(
            "There are 35 students in the classroom. I bought 25.",
            processor.normalize("There are thirty-five students in the classroom. I bought twenty-five.", "en"),
        )
    }

    @Test fun wetextProcessorHandlesPercentagesIdiomsAndCodeSwitchedSpaces() {
        val zhPack = ItnPack("zh", "rev-wt", emptyList(), ItnPack.ENGINE_WETEXT)
        val processor = NemoItnProcessor(
            withInstalledPack = { lang, block -> if (lang == "zh") block(zhPack, File(".")) else null },
            rewriter = { _, classify, input ->
                if (classify) {
                    when (input) {
                        "准确率百分之六十八" ->
                            """char { value: "准" } char { value: "确" } char { value: "率" } measure { value: "百分之六十八" }"""
                        "大家一心一意" ->
                            """char { value: "大" } char { value: "家" } char { value: "一" } char { value: "心" } char { value: "一" } char { value: "意" }"""
                        "一共有三十五个 Apple Watch" ->
                            """whitelist { value: "一共" } char { value: "有" } cardinal { value: "三十五" } char { value: "个" } char { value: " " } char { value: "A" } char { value: "p" } char { value: "p" } char { value: "l" } char { value: "e" } char { value: " " } char { value: "W" } char { value: "a" } char { value: "t" } char { value: "c" } char { value: "h" }"""
                        else -> error("Unexpected classify input: $input")
                    }
                } else {
                    when (input) {
                        """measure { value: "百分之六十八" }""" -> "68%"
                        """whitelist { value: "一共" }""" -> "一共"
                        """cardinal { value: "三十五" }""" -> "35"
                        else -> null
                    }
                }
            },
        )

        val raw = "准确率百分之六十八，大家一心一意，一共有三十五个 Apple Watch，There are thirty-five students。"
        val expected = "准确率68%，大家一心一意，一共有35个 Apple Watch，There are thirty-five students。"
        assertEquals(expected, processor.normalize(raw, "zh"))
    }

    @Test fun wetextParserReordersCanonicalFieldsAndUnescapesQuotes() {
        val parsed = WeTextTokenParser.parse(
            """money { decimal: "五角" value: "三十" currency: "元" } char { value: "\"" } char { value: "\\" }"""
        )
        assertEquals(3, parsed.size)
        val money = parsed[0] as WeTextTokenParser.ParsedToken.Semiotic
        assertEquals("""money { currency: "元" value: "三十" decimal: "五角" }""", money.canonicalTag)
        assertEquals(WeTextTokenParser.ParsedToken.PlainChar("\""), parsed[1])
        assertEquals(WeTextTokenParser.ParsedToken.PlainChar("\\"), parsed[2])
    }

    @Test fun permutesFieldsButNeverTokens() {
        val variants = NemoTokenParser.variants("tokens { money { integer: \"20\" currency: \"$\" } } tokens { name: \"left\" }").toList()
        assertEquals(2, variants.size)
        assertTrue(variants.any { it.indexOf("currency:") < it.indexOf("integer:") })
        assertTrue(variants.all { it.indexOf("money") < it.indexOf("left") })
    }

    @Test fun honorsPreserveOrderAndPreservesUnicodeQuotesAndNonBreakingSpace() {
        val variants = NemoTokenParser.variants("tokens { date { month: \"十二\" day: \"😀\" preserve_order: true } }").toList()
        assertEquals(1, variants.size)
        assertTrue(variants.single().contains("day: \"😀\""))
        assertTrue(NemoTokenParser.variants("tokens { name: \"\"\" }").single().contains("name: \"\"\""))

        // Non-breaking space inside multi-word literal must not terminate quote parsing prematurely
        val nbspTokens = NemoTokenParser.parseTokens("tokens { name: \"hello\u00A0world\" }")
        assertEquals(listOf(NemoTokenParser.ParsedToken.PlainWord("hello world")), nbspTokens)
    }

    @Test fun boundsPermutationWork() {
        val text = "tokens { date { a: \"1\" b: \"2\" c: \"3\" d: \"4\" e: \"5\" f: \"6\" g: \"7\" } }"
        assertEquals(729, NemoTokenParser.variants(text).count())
    }

    @Test fun rejectsMalformedTags() {
        listOf("tokens { name: \"broken", "tokens { x: false }", "tokens { name: \"x\" } trailing", "tokens { x: \"x\" } }").forEach {
            assertTrue(runCatching { NemoTokenParser.variants(it).toList() }.isFailure)
        }
    }

    private fun catalog(
        name: String = "classify.far",
        url: String = "https://example.com/en/classify.far",
        hash: String = "a".repeat(64),
        engine: String = "nemo",
    ) = """
        {"format":1,"packs":[{"language":"en","revision":"test-1","engine":"$engine","files":[
        {"name":"$name","url":"$url","bytes":1024,"sha256":"$hash"},
        {"name":"verbalize.far","url":"https://example.com/en/verbalize.far","bytes":12,"sha256":"${"b".repeat(64)}"}]}]}
    """.trimIndent()

    @Test fun validatesCatalogIntegrityAndPaths() {
        assertEquals(1036L, ItnPackCatalog.parse(catalog()).packs.single().diskBytes)
        assertEquals("wetext", ItnPackCatalog.parse(catalog(engine = "wetext")).packs.single().engine)
        assertTrue(runCatching { ItnPackCatalog.parse(catalog(engine = "unknown")) }.isFailure)
        assertTrue(runCatching { ItnPackCatalog.parse(catalog(name = "../classify.far")) }.isFailure)
        assertTrue(runCatching { ItnPackCatalog.parse(catalog(url = "http://example.com/a")) }.isFailure)
        assertTrue(runCatching { ItnPackCatalog.parse(catalog(hash = "bad")) }.isFailure)
        assertTrue(runCatching { ItnPackCatalog.parse(catalog().replace("\"format\":1", "\"format\":2")) }.isFailure)
    }
}
