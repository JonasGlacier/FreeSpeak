package com.freespeak.engine.canary

internal object CanaryGgufNative {
    init { System.loadLibrary("freespeak_canary_gguf") }

    external fun open(path: String, threads: Int): Long
    external fun infer(handle: Long, audio: FloatArray, source: String, target: String): ByteArray
    external fun close(handle: Long)
}
