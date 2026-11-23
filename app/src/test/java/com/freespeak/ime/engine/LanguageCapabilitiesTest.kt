package com.freespeak.ime.engine

import com.freespeak.ime.data.UserSettings
import com.freespeak.ime.model.*
import org.junit.Assert.*
import org.junit.Test

class LanguageCapabilitiesTest {
    @Test fun `automatic detection is the default regardless of old preferred languages`() {
        assertEquals("auto", UserSettings().spokenLanguageHint)
        assertFalse(UserSettings().translateToEnglish)
    }

    @Test fun `translation is only forwarded to multilingual Whisper`() {
        val requested = AsrInferenceOptions(language = "zh", translateToEnglish = true)
        for (model in BuiltinModelCatalog.models) {
            val effective = requested.forModel(model)
            assertEquals(model.engineType == EngineType.WHISPER_CPP, effective.translateToEnglish)
            assertEquals(if (model.canHintLanguage) "zh" else "auto", effective.language)
        }
    }

    @Test fun `English only Whisper and disabled capability cannot enable translation`() {
        val model = BuiltinModelCatalog.models.first { it.engineType == EngineType.WHISPER_CPP }
        val english = model.copy(runtimeConfig = model.runtimeConfig.copy(primaryModelFile = "ggml-small.en-q5_1.bin"))
        assertFalse(english.canTranslateToEnglish)
        assertFalse(AsrInferenceOptions(translateToEnglish = true).forModel(english).translateToEnglish)
        assertFalse(model.copy(supportsTranslateToEnglish = false).canTranslateToEnglish)
        val onnx = model.copy(engineType = EngineType.QWEN3_ASR, supportsTranslateToEnglish = true)
        assertFalse(onnx.canTranslateToEnglish)
    }

    @Test fun `old downloaded manifests retain compatible capability defaults`() {
        val model = ModelManifestCodec.parseManifest("""{
            "id":"sense-voice-small-int8", "name":"SenseVoice", "description":"",
            "engineType":"FUN_ASR_ONNX"
        }""")
        assertTrue(model.canHintLanguage)
        assertFalse(model.canTranslateToEnglish)
        assertFalse(model.copy(id = "fun-asr-nano-int8").canHintLanguage)
        val custom = model.copy(id = "custom-model", supportsLanguageHint = true)
        assertTrue(ModelManifestCodec.parseManifest(ModelManifestCodec.encodeManifest(custom)).canHintLanguage)
    }
}
