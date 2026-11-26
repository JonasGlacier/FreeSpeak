package com.freespeak.ime.engine.qwen3asr

import com.freespeak.engine.qwen3asr.QwenBpeTokenizer
import com.freespeak.ime.model.RumbleFormatting

internal fun buildQwen3AsrPromptIds(
    tokenizer: QwenBpeTokenizer,
    audioTokenCount: Int,
    languageName: String?,
    formatting: RumbleFormatting? = null,
): LongArray {
    val audioPadId = tokenizer.tokenToId("<|audio_pad|>") ?: 151676
    val system = tokenizer.encode("<|im_start|>system\n")
    // These are model tokens, even though they do not use the Qwen <|...|> spelling.
    val controls = formatting?.promptTokens().orEmpty().map { token ->
        requireNotNull(tokenizer.tokenToId(token)) { "Rumble tokenizer is missing $token" }
    }
    val user = tokenizer.encode("<|im_end|>\n<|im_start|>user\n<|audio_start|>")
    val assistant = "<|audio_end|><|im_end|>\n<|im_start|>assistant\n" +
        (languageName?.let { "language $it<asr_text>" } ?: "")
    val suffix = tokenizer.encode(assistant)

    val result = LongArray(system.size + controls.size + user.size + audioTokenCount + suffix.size)
    var pos = 0
    for (id in system) result[pos++] = id.toLong()
    for (id in controls) result[pos++] = id.toLong()
    for (id in user) result[pos++] = id.toLong()
    repeat(audioTokenCount) { result[pos++] = audioPadId.toLong() }
    for (id in suffix) result[pos++] = id.toLong()
    return result
}
