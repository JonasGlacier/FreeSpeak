package com.freespeak.ime.itn

import org.junit.Assert.*
import org.junit.Test

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

    @Test fun permutesFieldsButNeverTokens() {
        val variants = NemoTokenParser.variants("tokens { money { integer: \"20\" currency: \"$\" } } tokens { name: \"left\" }").toList()
        assertEquals(2, variants.size)
        assertTrue(variants.any { it.indexOf("currency:") < it.indexOf("integer:") })
        assertTrue(variants.all { it.indexOf("money") < it.indexOf("left") })
    }

    @Test fun honorsPreserveOrderAndPreservesUnicodeAndQuotes() {
        val variants = NemoTokenParser.variants("tokens { date { month: \"十二\" day: \"😀\" preserve_order: true } }").toList()
        assertEquals(1, variants.size)
        assertTrue(variants.single().contains("day: \"😀\""))
        assertTrue(NemoTokenParser.variants("tokens { name: \"\"\" }").single().contains("name: \"\"\""))
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

    private fun catalog(name: String = "classify.far", url: String = "https://example.com/en/classify.far", hash: String = "a".repeat(64)) = """
        {"format":1,"packs":[{"language":"en","revision":"test-1","files":[
        {"name":"$name","url":"$url","bytes":1024,"sha256":"$hash"},
        {"name":"verbalize.far","url":"https://example.com/en/verbalize.far","bytes":12,"sha256":"${"b".repeat(64)}"}]}]}
    """.trimIndent()

    @Test fun validatesCatalogIntegrityAndPaths() {
        assertEquals(1036L, ItnPackCatalog.parse(catalog()).packs.single().diskBytes)
        assertTrue(runCatching { ItnPackCatalog.parse(catalog(name = "../classify.far")) }.isFailure)
        assertTrue(runCatching { ItnPackCatalog.parse(catalog(url = "http://example.com/a")) }.isFailure)
        assertTrue(runCatching { ItnPackCatalog.parse(catalog(hash = "bad")) }.isFailure)
        assertTrue(runCatching { ItnPackCatalog.parse(catalog().replace("\"format\":1", "\"format\":2")) }.isFailure)
    }
}
