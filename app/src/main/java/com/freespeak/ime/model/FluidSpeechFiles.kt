package com.freespeak.ime.model

internal object FluidSpeechFiles {
    val components = listOf("encoder-model", "decoder", "joiner")
    fun graph(component: String, clean: Boolean) = "$component${if (clean) ".clean" else ""}.int8.onnx"
    val required = components.flatMap { listOf(graph(it, false), graph(it, true), graph(it, false) + ".data") } + "vocab.txt"
}
