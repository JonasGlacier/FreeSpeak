package com.freespeak.ime.itn

import com.freespeak.engine.itn.NemoItnNative

/** No network access. Only one language's graphs are resident; all JNI access is serialized. */
class NemoItnProcessor(private val packs: ItnPackRepository) {
    private var handle = 0L
    private var loaded: ItnPack? = null

    @Synchronized
    fun normalize(text: String, language: String?): String {
        if (language == null || text.isBlank() || text.length > 2048) return text
        return try {
            val available = packs.withInstalled(language) { pack, directory ->
                if (pack != loaded) {
                    releaseAll()
                    handle = NemoItnNative.create(
                        directory.resolve("classify.far").path.toByteArray(Charsets.UTF_8),
                        directory.resolve("verbalize.far").path.toByteArray(Charsets.UTF_8),
                    )
                    if (handle != 0L) loaded = pack
                }
                handle != 0L
            } ?: false
            if (!available) { releaseAll(); return text }
            val tagged = rewrite(text, true) ?: return text
            // The original field ordering usually works and preserves grammar-specific spacing.
            rewrite(tagged, false)?.takeIf { it.isNotBlank() }?.let { return it }
            val deadline = System.nanoTime() + 2_000_000_000L
            for (variant in NemoTokenParser.variants(tagged)) {
                if (System.nanoTime() > deadline) break
                rewrite(variant, false)?.takeIf { it.isNotBlank() }?.let { return it }
            }
            text
        } catch (_: Exception) {
            text
        } catch (_: LinkageError) {
            text
        }
    }

    private fun rewrite(text: String, classify: Boolean): String? =
        NemoItnNative.rewrite(handle, classify, text.toByteArray(Charsets.UTF_8))?.toString(Charsets.UTF_8)

    @Synchronized
    fun releaseLanguage(language: String) {
        if (loaded?.language == language) releaseAll()
    }

    @Synchronized
    fun releaseAll() {
        if (handle != 0L) NemoItnNative.release(handle)
        handle = 0L
        loaded = null
    }
}
