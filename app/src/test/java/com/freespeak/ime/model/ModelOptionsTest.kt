package com.freespeak.ime.model

import com.freespeak.ime.data.UserSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelOptionsTest {
    private fun builtin(id: String) = BuiltinModelCatalog.models.first { it.id == id }

    @Test
    fun `Canary v2 variants offer all 25 languages and use English as the translation pivot`() {
        for (id in listOf("canary-1b-v2-int8", "canary-1b-v2-q4_k")) {
            val model = builtin(id)
            val settings = UserSettings(modelOptions = mapOf(id to mapOf(ModelOptions.LANGUAGE to "it")))
            val (source, target) = ModelOptions.specsFor(model, settings).map { it as ChoiceModelOption }
            assertEquals(25, source.choices.distinct().size)
            assertTrue("pt" in source.choices)
            assertFalse("zh" in source.choices)
            assertEquals(listOf(CanaryLanguages.SAME_AS_SOURCE, "en"), target.choices)
            assertEquals("it", settings.optionsFor(model).language)
        }
    }

    @Test
    fun `Canary output choices follow the spoken language and reject unsupported pairs`() {
        val model = builtin("canary-180m-flash-int8")
        fun settings(source: String, target: String) = UserSettings(
            modelOptions = mapOf(model.id to mapOf(ModelOptions.LANGUAGE to source, ModelOptions.TARGET_LANGUAGE to target)),
        )
        val english = settings("en", "fr")
        assertEquals(listOf("en", "de", "es", "fr"), (ModelOptions.specsFor(model, english).first() as ChoiceModelOption).choices)
        assertEquals("fr", english.optionsFor(model).targetLanguage)
        val german = settings("de", "fr")
        val target = ModelOptions.specsFor(model, german).last() as ChoiceModelOption
        assertEquals(listOf(CanaryLanguages.SAME_AS_SOURCE, "en"), target.choices)
        assertEquals("de", CanaryLanguages.target(model, "de", german.optionsFor(model).targetLanguage))
        assertEquals("en", CanaryLanguages.target(model, "de", settings("de", "en").optionsFor(model).targetLanguage))
    }

    @Test
    fun `each engine declares only the options it supports`() {
        val whisper = ModelOptions.specsFor(builtin("whisper-small-q5_1")).map { it.key }
        assertEquals(listOf(ModelOptions.LANGUAGE, ModelOptions.TRANSLATE_EN, ModelOptions.WHISPER_PROMPT), whisper)

        val sense = ModelOptions.specsFor(builtin("sense-voice-small-int8")).map { it.key }
        assertEquals(listOf(ModelOptions.LANGUAGE), sense)

        assertTrue(ModelOptions.specsFor(builtin("fun-asr-nano-int8")).isEmpty())

        val en = builtin("whisper-small-q5_1").let {
            it.copy(runtimeConfig = it.runtimeConfig.copy(primaryModelFile = "ggml-small.en-q5_1.bin"))
        }
        val enKeys = ModelOptions.specsFor(en).map { it.key }
        assertFalse(ModelOptions.TRANSLATE_EN in enKeys)
        val prompt = ModelOptions.specsFor(en).first { it.key == ModelOptions.WHISPER_PROMPT } as ToggleModelOption
        assertEquals(null, prompt.disabledWhenOn)
    }

    @Test
    fun `language choices follow the model language list`() {
        val sense = ModelOptions.specsFor(builtin("sense-voice-small-int8")).first() as ChoiceModelOption
        assertEquals(listOf("auto", "en", "zh", "yue", "ja", "ko"), sense.choices)
        assertFalse("fr" in sense.choices)
    }

    @Test
    fun `values are stored per model and validated`() {
        val whisper = builtin("whisper-small-q5_1")
        val sense = builtin("sense-voice-small-int8")
        val settings = UserSettings(
            modelOptions = mapOf(
                whisper.id to mapOf(ModelOptions.LANGUAGE to "fr", ModelOptions.TRANSLATE_EN to "true"),
                sense.id to mapOf(ModelOptions.LANGUAGE to "fr"),
            )
        )
        val w = settings.optionsFor(whisper)
        assertEquals("fr", w.language)
        assertTrue(w.translateToEnglish)
        // French is not supported by SenseVoice -> falls back to auto, and no translation.
        val s = settings.optionsFor(sense)
        assertEquals("auto", s.language)
        assertFalse(s.translateToEnglish)
    }

    @Test
    fun `legacy global values act as defaults`() {
        val whisper = builtin("whisper-small-q5_1")
        val legacy = UserSettings(spokenLanguageHint = "ja", translateToEnglish = true, whisperStylePromptEnabled = false)
        val o = legacy.optionsFor(whisper)
        assertEquals("ja", o.language)
        assertTrue(o.translateToEnglish)
        assertFalse(o.whisperPrompt)
        assertFalse(legacy.optionsFor(builtin("qwen3-asr-0.6b-int8")).translateToEnglish)
        assertEquals("auto", UserSettings().optionsFor(null).language)
    }
}
