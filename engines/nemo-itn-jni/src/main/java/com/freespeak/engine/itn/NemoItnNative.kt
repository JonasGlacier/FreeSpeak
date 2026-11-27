package com.freespeak.engine.itn

/** Standard UTF-8 byte arrays avoid JNI modified-UTF-8 corruption of emoji and supplementary Han. */
object NemoItnNative {
    init { System.loadLibrary("freespeak_itn") }
    external fun create(classify: ByteArray, verbalize: ByteArray): Long
    external fun rewrite(handle: Long, classify: Boolean, text: ByteArray): ByteArray?
    external fun release(handle: Long)
}
