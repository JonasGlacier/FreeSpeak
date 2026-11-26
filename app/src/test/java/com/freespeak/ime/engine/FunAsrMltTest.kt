package com.freespeak.ime.engine

import com.freespeak.ime.data.UserSettings
import com.freespeak.ime.engine.funasr.FunAsrKvDecoder
import com.freespeak.ime.model.BuiltinModelCatalog
import com.freespeak.ime.model.ChoiceModelOption
import com.freespeak.ime.model.FunAsrMltLanguages
import com.freespeak.ime.model.ModelOptions
import com.freespeak.ime.model.optionsFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class FunAsrMltTest {
    @Test
    fun `all documented languages are selectable and use a native Fun-ASR prompt`() {
        val model = BuiltinModelCatalog.models.single { it.id == "fun-asr-mlt-nano-int8" }
        val original = BuiltinModelCatalog.models.single { it.id == "fun-asr-nano-int8" }
        val languages = (ModelOptions.specsFor(model).single { it.key == ModelOptions.LANGUAGE } as ChoiceModelOption).choices
        assertEquals(32, languages.distinct().size) // 31 speech languages + auto
        for (code in languages) {
            val settings = UserSettings(modelOptions = mapOf(model.id to mapOf(ModelOptions.LANGUAGE to code)))
            assertEquals(code, settings.optionsFor(model).language)
        }
        assertEquals("语音转写成越南语：", FunAsrMltLanguages.transcriptionPrompt("vi"))
        assertEquals("语音转写：", FunAsrMltLanguages.transcriptionPrompt("auto"))
        assertEquals("语音转写：", FunAsrMltLanguages.transcriptionPrompt("de"))
        assertEquals(emptyList<Any>(), ModelOptions.specsFor(original))
    }

    @Test
    fun `generation reserves output space without trimming the audio prompt`() {
        assertEquals(256, FunAsrKvDecoder.generationBudget(500, 1024, 256))
        assertEquals(100, FunAsrKvDecoder.generationBudget(924, 1024, 256))
        assertEquals(1, FunAsrKvDecoder.generationBudget(1023, 1024, 256))
        assertThrows(AsrInputTooLongException::class.java) { FunAsrKvDecoder.generationBudget(1024, 1024, 256) }
    }
}
