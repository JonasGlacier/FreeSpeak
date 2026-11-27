package com.freespeak.ime.itn

import android.content.Context
import com.freespeak.ime.data.UserSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class ItnPackState(
    val packs: List<ItnPack> = emptyList(),
    val installed: Set<String> = emptySet(),
    val busy: Boolean = false,
    val progress: Float? = null,
    val error: String? = null,
)

/** Downloads are explicitly initiated by the settings screen, never by the IME. */
class ItnPackRepository(context: Context) {
    private val root = File(context.filesDir, "itn").apply { mkdirs() }
    private val diskLock = Any()
    private val operations = Mutex()
    private val client = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS).callTimeout(10, TimeUnit.MINUTES).build()
    private val mutableState = MutableStateFlow(ItnPackState())
    val state = mutableState.asStateFlow()

    init {
        // Recover a replacement interrupted between the two directory renames.
        ItnPackCatalog.languages.forEach { language ->
            val destination = File(root, language)
            val backup = File(root, "$language.old")
            if (!destination.exists() && backup.exists()) backup.renameTo(destination)
        }
        val catalog = runCatching { ItnPackCatalog.parse(File(root, "catalog.json").readText()) }.getOrNull()
        mutableState.value = ItnPackState(packs = catalog?.packs.orEmpty(), installed = installedLanguages())
    }

    private fun installedLanguages() = ItnPackCatalog.languages.filter { language ->
        withInstalled(language) { _, _ -> true } == true
    }.toSet()

    fun <T> withInstalled(language: String, block: (ItnPack, File) -> T): T? = synchronized(diskLock) {
        if (language !in ItnPackCatalog.languages) return@synchronized null
        val directory = File(root, language)
        val pack = runCatching { ItnPackCatalog.parse(File(directory, "pack.json").readText()).packs.single() }.getOrNull()
            ?: return@synchronized null
        if (pack.language != language || pack.files.any { File(directory, it.name).length() != it.bytes }) return@synchronized null
        block(pack, directory)
    }

    suspend fun refresh(url: String, settings: UserSettings) = operation {
        ItnPackCatalog.requireHttps(url)
        val text = fetchText(settings.resolveDownloadUrl(url))
        val catalog = ItnPackCatalog.parse(text)
        val temp = File(root, "catalog.tmp")
        temp.writeText(text)
        java.nio.file.Files.move(temp.toPath(), File(root, "catalog.json").toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        mutableState.value = mutableState.value.copy(packs = catalog.packs)
    }

    suspend fun install(pack: ItnPack, settings: UserSettings) = operation {
        ItnPackCatalog.parse(ItnPackCatalog.json.encodeToString(ItnPackCatalog(packs = listOf(pack))))
        val stage = File(root, "${pack.language}.partial")
        stage.deleteRecursively(); check(stage.mkdirs())
        try {
            var done = 0L
            for (file in pack.files) {
                val url = settings.resolveDownloadUrl(file.url)
                ItnPackCatalog.requireHttps(url)
                withResponse(url) { response ->
                    check(response.isSuccessful) { "ITN download failed: HTTP ${response.code}" }
                    check(response.request.url.isHttps) { "ITN download must use HTTPS" }
                    val digest = MessageDigest.getInstance("SHA-256")
                    var bytes = 0L
                    checkNotNull(response.body).byteStream().use { input ->
                        File(stage, file.name).outputStream().use { output ->
                            val buffer = ByteArray(65536)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                bytes += count
                                check(bytes <= file.bytes) { "ITN file exceeds declared size" }
                                output.write(buffer, 0, count); digest.update(buffer, 0, count)
                                mutableState.value = mutableState.value.copy(progress = (done + bytes).toFloat() / pack.diskBytes)
                            }
                        }
                    }
                    check(bytes == file.bytes && digest.digest().joinToString("") { "%02x".format(it) } == file.sha256) { "ITN file integrity check failed" }
                    done += bytes
                }
            }
            currentCoroutineContext().ensureActive()
            File(stage, "pack.json").writeText(ItnPackCatalog.json.encodeToString(ItnPackCatalog(packs = listOf(pack))))
            synchronized(diskLock) {
                val destination = File(root, pack.language)
                // Only publish a fully verified pair; keep an installed pack if replacement fails.
                val backup = File(root, "${pack.language}.old")
                backup.deleteRecursively()
                if (destination.exists()) check(destination.renameTo(backup))
                if (!stage.renameTo(destination)) {
                    backup.renameTo(destination)
                    error("Could not install ITN pack")
                }
                backup.deleteRecursively()
            }
        } finally { stage.deleteRecursively() }
    }

    suspend fun delete(language: String) = operation {
        require(language in ItnPackCatalog.languages)
        synchronized(diskLock) { check(File(root, language).deleteRecursively()) }
    }

    private suspend fun operation(block: suspend () -> Unit) = withContext(Dispatchers.IO) {
        operations.withLock {
            mutableState.value = mutableState.value.copy(busy = true, error = null, progress = null)
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                mutableState.value = mutableState.value.copy(error = e.message ?: "ITN operation failed")
            }
            finally { mutableState.value = mutableState.value.copy(busy = false, progress = null, installed = installedLanguages()) }
        }
    }

    private suspend fun fetchText(url: String): String {
        ItnPackCatalog.requireHttps(url)
        return withResponse(url) { response ->
            check(response.isSuccessful) { "ITN catalog failed: HTTP ${response.code}" }
            check(response.request.url.isHttps)
            val bytes = checkNotNull(response.body).byteStream().use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= 1_048_576) { "ITN catalog is too large" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            bytes.toString(Charsets.UTF_8)
        }
    }

    private suspend fun <T> withResponse(url: String, block: suspend (Response) -> T): T = coroutineScope {
        val call = client.newCall(Request.Builder().url(url).build())
        // Cancels even a blocked socket read when the user leaves/cancels the operation.
        val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { call.cancel() }
        }
        try { call.execute().use { block(it) } }
        finally { cancellation.cancel() }
    }
}
