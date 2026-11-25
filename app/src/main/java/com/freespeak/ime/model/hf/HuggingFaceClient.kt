package com.freespeak.ime.model.hf

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

@Serializable
data class HfModelSummary(
    val id: String,
    val likes: Int = 0,
    val downloads: Long = 0L,
    val tags: List<String> = emptyList(),
    @SerialName("pipeline_tag") val pipelineTag: String? = null,
    @SerialName("library_name") val libraryName: String? = null,
    val createdAt: String? = null,
    val lastModified: String? = null,
    val private: Boolean = false,
) {
    val owner: String get() = id.substringBefore('/', "")
    val name: String get() = id.substringAfter('/')
}

@Serializable
data class HfLfsInfo(
    val sha256: String? = null,
    val size: Long? = null,
)

@Serializable
data class HfSibling(
    val rfilename: String,
    val size: Long? = null,
    val lfs: HfLfsInfo? = null,
)

@Serializable
data class HfModelInfo(
    val id: String,
    val author: String? = null,
    val sha: String? = null,
    val lastModified: String? = null,
    val createdAt: String? = null,
    val private: Boolean = false,
    val disabled: Boolean = false,
    val gated: JsonElement? = null,
    val downloads: Long = 0L,
    val likes: Int = 0,
    val tags: List<String> = emptyList(),
    @SerialName("pipeline_tag") val pipelineTag: String? = null,
    @SerialName("library_name") val libraryName: String? = null,
    val siblings: List<HfSibling> = emptyList(),
) {
    /** `gated` is `false` for open repos, or a string ("auto" / "manual") when gated. */
    val isGated: Boolean
        get() {
            val prim = gated as? JsonPrimitive ?: return false
            prim.booleanOrNull?.let { return it }
            return prim.content.isNotBlank() && prim.content != "false"
        }
}

data class HfSearchPage(
    val items: List<HfModelSummary>,
    /** Opaque cursors for the next page; null when exhausted. */
    val nextCursor: HfSearchCursor?,
)

data class HfSearchCursor(
    val asrNextUrl: String?,
    val generalNextUrl: String?,
) {
    val hasMore: Boolean get() = asrNextUrl != null || generalNextUrl != null
}

class HuggingFaceException(message: String, val httpCode: Int? = null) : IOException(message)

/**
 * Minimal Hugging Face Hub REST client (public repos only, no token).
 */
class HuggingFaceClient(
    private val http: OkHttpClient,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    /**
     * Searches the Hub. Many community speech repos (e.g. sherpa-onnx exports) carry no
     * `pipeline_tag`, so two queries are merged: ASR-tagged results first, then general results
     * that look speech-related.
     */
    suspend fun search(
        apiBase: String,
        query: String,
        cursor: HfSearchCursor? = null,
        pageSize: Int = 30,
    ): HfSearchPage = withContext(Dispatchers.IO) {
        val base = normalizeBase(apiBase)
        val asrUrl: String?
        val generalUrl: String?
        if (cursor == null) {
            asrUrl = buildSearchUrl(base, query, pageSize, asrOnly = true)
            generalUrl = if (query.isNotBlank()) buildSearchUrl(base, query, pageSize, asrOnly = false) else null
        } else {
            asrUrl = cursor.asrNextUrl?.let { rebase(it, base) }
            generalUrl = cursor.generalNextUrl?.let { rebase(it, base) }
        }

        coroutineScope {
            val asrDeferred = asrUrl?.let { url -> async { fetchList(url) } }
            val generalDeferred = generalUrl?.let { url -> async { fetchList(url) } }
            val asr = asrDeferred?.await()
            val general = generalDeferred?.await()

            val merged = LinkedHashMap<String, HfModelSummary>()
            asr?.first?.forEach { merged[it.id] = it }
            general?.first?.filter { looksSpeechRelated(it) }?.forEach { merged.putIfAbsent(it.id, it) }

            HfSearchPage(
                items = merged.values.filterNot { it.private },
                nextCursor = HfSearchCursor(asr?.second, general?.second).takeIf { it.hasMore },
            )
        }
    }

    suspend fun modelInfo(apiBase: String, repoId: String): HfModelInfo = withContext(Dispatchers.IO) {
        val base = normalizeBase(apiBase)
        val url = "$base/api/models/${encodeRepoId(repoId)}?blobs=true"
        val body = execute(url).first
        json.decodeFromString(HfModelInfo.serializer(), body)
    }

    private fun fetchList(url: String): Pair<List<HfModelSummary>, String?> {
        val (body, link) = execute(url)
        val list = json.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(HfModelSummary.serializer()),
            body,
        )
        return list to parseNextLink(link)
    }

    private fun execute(url: String): Pair<String, String?> {
        val req = Request.Builder().url(url).header("Accept", "application/json").get().build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw HuggingFaceException("HTTP ${resp.code}", resp.code)
            }
            val ct = resp.header("Content-Type").orEmpty()
            val body = resp.body?.string().orEmpty()
            if (!ct.contains("json", ignoreCase = true) && !body.trimStart().let { it.startsWith("[") || it.startsWith("{") }) {
                throw HuggingFaceException("Hub API unavailable on this mirror")
            }
            return body to resp.header("Link")
        }
    }

    companion object {
        private val REPO_ID = Regex("^[A-Za-z0-9][A-Za-z0-9._-]*/[A-Za-z0-9][A-Za-z0-9._-]*$")
        private val SPEECH_HINTS = listOf(
            "asr", "whisper", "speech", "sherpa", "sense-voice", "sensevoice", "funasr", "fun-asr",
            "paraformer", "ggml", "stt", "transcri", "voice", "audio", "qwen3-asr", "qwen3asr",
        )

        fun normalizeBase(apiBase: String): String =
            apiBase.trim().trimEnd('/').ifEmpty { "https://huggingface.co" }

        fun buildSearchUrl(base: String, query: String, limit: Int, asrOnly: Boolean): String {
            val b = (normalizeBase(base) + "/api/models").toHttpUrlOrNull()?.newBuilder()
                ?: throw HuggingFaceException("Invalid mirror URL")
            if (query.isNotBlank()) b.addQueryParameter("search", query.trim())
            if (asrOnly) b.addQueryParameter("pipeline_tag", "automatic-speech-recognition")
            b.addQueryParameter("sort", "downloads")
            b.addQueryParameter("direction", "-1")
            b.addQueryParameter("limit", limit.toString())
            return b.build().toString()
        }

        fun parseNextLink(link: String?): String? {
            if (link.isNullOrBlank()) return null
            return link.split(',').map { it.trim() }.firstOrNull { it.contains("rel=\"next\"") }
                ?.substringAfter('<', "")?.substringBefore('>', "")
                ?.takeIf { it.isNotBlank() }
        }

        /** Keeps the cursor query, but routes through the user's selected API base. */
        fun rebase(url: String, base: String): String {
            val src: HttpUrl = url.toHttpUrlOrNull() ?: return url
            val target = normalizeBase(base).toHttpUrlOrNull() ?: return url
            return src.newBuilder().scheme(target.scheme).host(target.host).port(target.port).build().toString()
        }

        fun looksSpeechRelated(m: HfModelSummary): Boolean {
            if (m.pipelineTag == "automatic-speech-recognition") return true
            val hay = (m.id + " " + m.tags.joinToString(" ")).lowercase()
            return SPEECH_HINTS.any { it in hay }
        }

        fun encodeRepoId(repoId: String): String =
            repoId.split('/').joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8") }

        /**
         * Extracts `owner/name` from raw ids or pasted huggingface.co / hf-mirror.com links.
         */
        fun parseRepoReference(input: String): String? {
            val raw = input.trim().removeSuffix("/")
            if (raw.isEmpty()) return null
            if (REPO_ID.matches(raw) && !raw.contains("..")) return raw
            val url = (if (raw.startsWith("http")) raw else "https://$raw").toHttpUrlOrNull() ?: return null
            if (!url.host.contains("huggingface.co") && !url.host.contains("hf-mirror.com")) return null
            val segs = url.pathSegments.filter { it.isNotBlank() }
            val start = if (segs.firstOrNull() == "models") 1 else 0
            if (segs.size < start + 2) return null
            if (segs[start] in setOf("datasets", "spaces", "api", "docs")) return null
            val id = segs[start] + "/" + segs[start + 1]
            return id.takeIf { REPO_ID.matches(it) }
        }
    }
}
