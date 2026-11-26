package com.freespeak.ime.engine

import com.freespeak.ime.data.UserSettings
import com.freespeak.ime.model.*
import org.junit.Assert.*
import org.junit.Test

class LanguageCapabilitiesTest {
    @Test fun `automatic detection is the default regardless of old preferred languages`() {
        assertEquals("auto", UserSettings().spokenLanguageHint)
    }

    @Test fun `language hint and Canary target language are scoped to capable models`() {
        val requested = AsrInferenceOptions(language = "zh", targetLanguage = "en")
        for (model in BuiltinModelCatalog.models) {
            val effective = requested.forModel(model)
            assertEquals(if (model.canHintLanguage) "zh" else "auto", effective.language)
            assertEquals(if (model.engineType.isCanary) "en" else null, effective.targetLanguage)
        }
    }

    @Test fun `old downloaded manifests retain compatible capability defaults`() {
        val model = ModelManifestCodec.parseManifest("""{
            "id":"sense-voice-small-int8", "name":"SenseVoice", "description":"",
            "engineType":"FUN_ASR_ONNX"
        }""")
        assertTrue(model.canHintLanguage)
        assertFalse(model.copy(id = "fun-asr-nano-int8").canHintLanguage)
        val custom = model.copy(id = "custom-model", supportsLanguageHint = true)
        assertTrue(ModelManifestCodec.parseManifest(ModelManifestCodec.encodeManifest(custom)).canHintLanguage)
    }
}
