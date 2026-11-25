package com.freespeak.ime.model

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.freespeak.ime.data.UserSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.ArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

sealed interface ModelInstallState {
    data object NotInstalled : ModelInstallState
    data class Downloading(
        val progress: Float,
        val downloadedBytes: Long,
        val totalBytes: Long,
        val speedBytesPerSec: Long,
        val currentFile: String,
    ) : ModelInstallState
    data object Extracting : ModelInstallState
    data class Installed(val diskBytes: Long) : ModelInstallState
    /** Download was interrupted (cancelled or app restarted); files on disk can be resumed. */
    data class Partial(val downloadedBytes: Long, val totalBytes: Long) : ModelInstallState
    data class Error(val message: String) : ModelInstallState
}

data class ManagedModelItem(
    val manifest: ModelManifest,
    val state: ModelInstallState,
    val modelDir: File,
)

/**
 * Manages built-in models, external JSON community catalogs, direct-URL custom models,
 * resumable downloads, archive extraction (.zip / .tar.gz / .tar.bz2), and SAF local imports.
 */
class ModelRepository(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val modelsRootDir = File(context.filesDir, "models").apply { mkdirs() }
    private val customRegistryDir = File(context.filesDir, "custom_manifests").apply { mkdirs() }

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val activeJobs = ConcurrentHashMap<String, Job>()

    val huggingFace = com.freespeak.ime.model.hf.HuggingFaceClient(okHttpClient)

    private val _externalModels = MutableStateFlow<List<ModelManifest>>(emptyList())
    private val _downloadStates = MutableStateFlow<Map<String, ModelInstallState>>(emptyMap())
    private val _models = MutableStateFlow<List<ManagedModelItem>>(emptyList())
    val models: StateFlow<List<ManagedModelItem>> = _models.asStateFlow()

    init {
        refreshLocalInventory()
    }

    fun getModelDirectory(modelId: String): File = File(modelsRootDir, sanitizeId(modelId))

    fun findInstalledManifest(modelId: String): ModelManifest? {
        if (modelId.isBlank()) return null
        val dir = getModelDirectory(modelId)
        val localManifestFile = File(dir, ModelManifestCodec.MANIFEST_FILE_NAME)
        val manifest = if (localManifestFile.exists()) {
            runCatching { ModelManifestCodec.parseManifest(localManifestFile.readText()) }.getOrNull()
        } else {
            allKnownManifests().find { it.id == modelId }
        } ?: return null

        return if (ModelManifestCodec.isModelDirectoryComplete(dir, manifest)) manifest else null
    }

    fun listInstalledManifests(): List<ModelManifest> {
        refreshLocalInventory()
        return _models.value
            .filter { it.state is ModelInstallState.Installed }
            .map { it.manifest }
    }

    fun refreshLocalInventory() {
        val allManifests = allKnownManifests()
        val activeMap = _downloadStates.value
        val items = allManifests.map { manifest ->
            val dir = getModelDirectory(manifest.id)
            val transientState = activeMap[manifest.id]
            val finalState = when {
                transientState is ModelInstallState.Downloading ||
                    transientState is ModelInstallState.Extracting -> transientState
                ModelManifestCodec.isModelDirectoryComplete(dir, manifest) -> {
                    val diskSize = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                    ModelInstallState.Installed(diskSize)
                }
                transientState is ModelInstallState.Error -> transientState
                else -> {
                    val onDisk = if (dir.isDirectory) {
                        dir.walkTopDown().filter { it.isFile && it.name != ModelManifestCodec.MANIFEST_FILE_NAME }
                            .sumOf { it.length() }
                    } else 0L
                    if (onDisk > 0L && manifest.files.isNotEmpty()) {
                        ModelInstallState.Partial(onDisk, manifest.sizeBytes)
                    } else {
                        ModelInstallState.NotInstalled
                    }
                }
            }
            ManagedModelItem(
                manifest = manifest,
                state = finalState,
                modelDir = dir,
            )
        }
        _models.value = items
    }

    private fun allKnownManifests(): List<ModelManifest> {
        val byId = LinkedHashMap<String, ModelManifest>()
        for (m in BuiltinModelCatalog.models) {
            byId[m.id] = m
        }
        for (m in _externalModels.value) {
            byId[m.id] = m
        }
        // Load persisted custom/local manifests from customRegistryDir & modelsRootDir
        customRegistryDir.listFiles()?.forEach { file ->
            if (file.extension == "json") {
                runCatching {
                    val m = ModelManifestCodec.parseManifest(file.readText())
                    byId[m.id] = m
                }
            }
        }
        modelsRootDir.listFiles()?.forEach { dir ->
            if (dir.isDirectory) {
                val mf = File(dir, ModelManifestCodec.MANIFEST_FILE_NAME)
                if (mf.exists()) {
                    runCatching {
                        val m = ModelManifestCodec.parseManifest(mf.readText())
                        if (m.id !in byId) {
                            byId[m.id] = m
                        }
                    }
                }
            }
        }
        return byId.values.toList()
    }

    /**
     * Refreshes subscribed external JSON catalogs (`ExternalModelCatalog` or single `ModelManifest`).
     */
    suspend fun syncExternalCatalogs(urls: Set<String>): Result<Int> = withContext(Dispatchers.IO) {
        if (urls.isEmpty()) {
            _externalModels.value = emptyList()
            refreshLocalInventory()
            return@withContext Result.success(0)
        }

        val loaded = ArrayList<ModelManifest>()
        var lastError: Throwable? = null
        for (url in urls) {
            runCatching {
                val req = Request.Builder().url(url).get().build()
                okHttpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) error("HTTP ${resp.code}: $url")
                    val body = resp.body?.string().orEmpty()
                    val trimmed = body.trim()
                    if (trimmed.contains("\"models\"")) {
                        val cat = ModelManifestCodec.parseCatalog(trimmed)
                        loaded.addAll(cat.models.map { it.copy(sourceTag = "catalog") })
                    } else {
                        val single = ModelManifestCodec.parseManifest(trimmed)
                        loaded.add(single.copy(sourceTag = "catalog"))
                    }
                }
            }.onFailure { lastError = it }
        }

        _externalModels.value = loaded
        refreshLocalInventory()
        if (loaded.isEmpty() && lastError != null) {
            Result.failure(lastError!!)
        } else {
            Result.success(loaded.size)
        }
    }

    /**
     * Registers a user-defined direct-URL model into the local catalog and optionally starts downloading it.
     */
    fun registerDirectUrlModel(
        name: String,
        url: String,
        engineType: EngineType,
    ): ModelManifest {
        val cleanUrl = url.trim()
        val fileName = cleanUrl.substringBefore('?').substringAfterLast('/').ifBlank { "model.bin" }
        val isArchive = isArchiveFileName(fileName)
        val id = "custom-" + sanitizeId(name.lowercase()) + "-" + (cleanUrl.hashCode().toUInt().toString(16).take(6))

        val runtimeCfg = when (engineType) {
            EngineType.WHISPER_CPP -> ModelRuntimeConfig(primaryModelFile = if (isArchive) null else fileName)
            EngineType.FUN_ASR_ONNX -> ModelRuntimeConfig(primaryModelFile = if (isArchive) null else fileName, numMels = 80)
            EngineType.QWEN3_ASR -> ModelRuntimeConfig(numMels = 128)
            EngineType.CANARY_ONNX -> {
                require(isArchive) { "Canary direct downloads must be a model archive containing both ONNX files and tokens.txt" }
                ModelRuntimeConfig()
            }
            EngineType.CANARY_GGUF -> {
                require(isArchive || fileName.endsWith(".gguf", ignoreCase = true)) { "Canary GGUF requires a .gguf file" }
                ModelRuntimeConfig(primaryModelFile = fileName.takeUnless { isArchive })
            }
        }

        val manifest = ModelManifest(
            id = id,
            name = name.trim(),
            description = "Custom URL ($fileName)",
            engineType = engineType,
            languages = if (engineType == EngineType.CANARY_GGUF) CanaryLanguages.v2 else defaultModelLanguages,
            files = listOf(
                ModelFileEntry(
                    relativePath = fileName,
                    url = cleanUrl,
                    extractArchive = isArchive,
                )
            ),
            runtimeConfig = runtimeCfg,
            sourceTag = "custom_url",
        )
        File(customRegistryDir, "${manifest.id}.json").writeText(ModelManifestCodec.encodeManifest(manifest))
        refreshLocalInventory()
        return manifest
    }

    /**
     * Persists a manifest (e.g. generated from a Hugging Face repo) so it survives restarts and
     * interrupted downloads can be resumed from "My models".
     */
    fun registerManifest(manifest: ModelManifest) {
        File(customRegistryDir, "${sanitizeId(manifest.id)}.json")
            .writeText(ModelManifestCodec.encodeManifest(manifest))
        refreshLocalInventory()
    }

    fun startDownload(
        manifest: ModelManifest,
        settings: UserSettings,
        onInstalled: ((ModelManifest) -> Unit)? = null,
    ) {
        if (activeJobs.containsKey(manifest.id)) return

        val job = scope.launch {
            val modelDir = getModelDirectory(manifest.id).apply { mkdirs() }
            try {
                var completedBytesAcrossFiles = 0L
                val declaredTotal = manifest.sizeBytes.coerceAtLeast(0L)

                for (entry in manifest.files) {
                    val targetFile = File(modelDir, entry.relativePath)
                    targetFile.parentFile?.mkdirs()

                    val resolvedUrl = settings.resolveDownloadUrl(entry.url, entry.modelScopeUrl)
                    downloadFileResumable(
                        url = resolvedUrl,
                        destFile = targetFile,
                        expectedSha256 = entry.sha256,
                        expectedSize = entry.sizeBytes,
                        onProgress = { fileDone, fileTotal, speed ->
                            val grandDone = completedBytesAcrossFiles + fileDone
                            val grandTotal = if (declaredTotal > 0L) {
                                maxOf(declaredTotal, grandDone)
                            } else {
                                completedBytesAcrossFiles + fileTotal
                            }
                            val pct = if (grandTotal > 0L) {
                                (grandDone.toFloat() / grandTotal.toFloat()).coerceIn(0f, 0.99f)
                            } else 0f

                            updateTransientState(
                                manifest.id,
                                ModelInstallState.Downloading(
                                    progress = pct,
                                    downloadedBytes = grandDone,
                                    totalBytes = grandTotal,
                                    speedBytesPerSec = speed,
                                    currentFile = entry.relativePath.substringAfterLast('/'),
                                )
                            )
                        }
                    )

                    completedBytesAcrossFiles += targetFile.length()

                    if (entry.extractArchive || isArchiveFileName(targetFile.name)) {
                        updateTransientState(manifest.id, ModelInstallState.Extracting)
                        FileInputStream(targetFile).use { fis ->
                            extractArchiveStream(fis, targetFile.name, modelDir)
                        }
                        targetFile.delete()
                        flattenSingleNestedDirectoryIfNeeded(modelDir)
                    }
                }

                val finalizedManifest = if (manifest.files.any { it.extractArchive || isArchiveFileName(it.relativePath) }) {
                    ModelManifestCodec.inferOrLoadManifest(
                        modelDir = modelDir,
                        fallbackId = manifest.id,
                        fallbackName = manifest.name,
                        preferredEngine = manifest.engineType,
                    )
                } else {
                    manifest
                }

                File(modelDir, ModelManifestCodec.MANIFEST_FILE_NAME)
                    .writeText(ModelManifestCodec.encodeManifest(finalizedManifest))

                clearTransientState(manifest.id)
                refreshLocalInventory()
                onInstalled?.invoke(finalizedManifest)
            } catch (ce: CancellationException) {
                clearTransientState(manifest.id)
                refreshLocalInventory()
                throw ce
            } catch (e: Exception) {
                updateTransientState(
                    manifest.id,
                    ModelInstallState.Error(e.message ?: "Download failed")
                )
            } finally {
                activeJobs.remove(manifest.id)
            }
        }
        activeJobs[manifest.id] = job
    }

    fun cancelDownload(modelId: String) {
        activeJobs.remove(modelId)?.cancel()
        clearTransientState(modelId)
        refreshLocalInventory()
    }

    suspend fun deleteModel(modelId: String) = withContext(Dispatchers.IO) {
        cancelDownload(modelId)
        val dir = getModelDirectory(modelId)
        if (dir.exists()) {
            dir.deleteRecursively()
        }
        val customReg = File(customRegistryDir, "${sanitizeId(modelId)}.json")
        if (customReg.exists() && BuiltinModelCatalog.models.none { it.id == modelId }) {
            customReg.delete()
        }
        clearTransientState(modelId)
        refreshLocalInventory()
    }

    /**
     * Imports a local `.bin`, `.gguf`, `.onnx`, `.zip`, `.tar.gz`, `.tgz`, or `.tar.bz2` file
     * selected via Android SAF (`ACTION_OPEN_DOCUMENT`) completely offline.
     */
    suspend fun importLocalModelUri(
        uri: Uri,
        customDisplayName: String?,
        preferredEngine: EngineType?,
    ): Result<ModelManifest> = withContext(Dispatchers.IO) {
        runCatching {
            val fileName = resolveDisplayName(uri) ?: "imported_model.bin"
            val baseTitle = customDisplayName?.trim()?.takeIf { it.isNotEmpty() }
                ?: fileName.substringBeforeLast('.')
            val modelId = "local-" + sanitizeId(baseTitle.lowercase()) + "-" + (System.currentTimeMillis() % 100000)
            val destDir = getModelDirectory(modelId).apply {
                if (exists()) deleteRecursively()
                mkdirs()
            }

            updateTransientState(modelId, ModelInstallState.Extracting)

            try {
                val inputStream = context.contentResolver.openInputStream(uri)
                    ?: error("Cannot open selected file")
                inputStream.use { stream ->
                    if (isArchiveFileName(fileName)) {
                        extractArchiveStream(stream, fileName, destDir)
                        flattenSingleNestedDirectoryIfNeeded(destDir)
                    } else {
                        val outFile = File(destDir, sanitizeFileName(fileName))
                        FileOutputStream(outFile).use { out ->
                            stream.copyTo(out, bufferSize = 64 * 1024)
                        }
                    }
                }

                val manifest = ModelManifestCodec.inferOrLoadManifest(
                    modelDir = destDir,
                    fallbackId = modelId,
                    fallbackName = baseTitle,
                    preferredEngine = preferredEngine,
                )
                val encoded = ModelManifestCodec.encodeManifest(manifest)
                File(destDir, ModelManifestCodec.MANIFEST_FILE_NAME).writeText(encoded)
                File(customRegistryDir, "${manifest.id}.json").writeText(encoded)
                clearTransientState(modelId)
                refreshLocalInventory()
                manifest
            } catch (e: Exception) {
                destDir.deleteRecursively()
                clearTransientState(modelId)
                refreshLocalInventory()
                throw e
            }
        }
    }

    private suspend fun downloadFileResumable(
        url: String,
        destFile: File,
        expectedSha256: String?,
        expectedSize: Long?,
        onProgress: (downloaded: Long, total: Long, speedBytesPerSec: Long) -> Unit,
    ) = withContext(Dispatchers.IO) {
        if (destFile.exists() && destFile.length() > 0L) {
            val ok = when {
                // Exact size match: the file was hash-verified when it was first completed.
                expectedSize != null -> destFile.length() == expectedSize
                expectedSha256 == null -> true
                else -> verifySha256(destFile, expectedSha256)
            }
            if (ok) {
                onProgress(destFile.length(), destFile.length(), 0L)
                return@withContext
            } else {
                destFile.delete()
            }
        }

        val partFile = File(destFile.parentFile, "${destFile.name}.part")
        var existingBytes = if (partFile.exists()) partFile.length() else 0L
        if (expectedSize != null && existingBytes > expectedSize) {
            partFile.delete()
            existingBytes = 0L
        }

        if (expectedSize == null || existingBytes < expectedSize) {
            val reqBuilder = Request.Builder().url(url)
            if (existingBytes > 0L) {
                reqBuilder.header("Range", "bytes=$existingBytes-")
            }

            okHttpClient.newCall(reqBuilder.build()).execute().use { response ->
                if (response.code == 416 && existingBytes > 0L) {
                    // Range already satisfied; verified below.
                    return@use
                }
                if (!response.isSuccessful) {
                    throw IllegalStateException("HTTP ${response.code} (${url.substringBefore('?')})")
                }

                val isResume = response.code == 206 && existingBytes > 0L
                if (!isResume) {
                    existingBytes = 0L
                    if (partFile.exists()) partFile.delete()
                }

                val body = response.body ?: throw IllegalStateException("Empty server response")
                val contentLen = body.contentLength()
                val totalFileBytes = expectedSize ?: if (contentLen > 0L) existingBytes + contentLen else 0L

                RandomAccessFile(partFile, "rw").use { raf ->
                    if (isResume) raf.seek(existingBytes) else raf.setLength(0L)

                    val buf = ByteArray(64 * 1024)
                    var downloaded = existingBytes
                    var windowBytes = 0L
                    var lastTickMs = System.currentTimeMillis()
                    var currentSpeed = 0L

                    body.byteStream().use { input ->
                        while (true) {
                            // Throws on cancellation so a partial file is never promoted to complete.
                            ensureActive()
                            val read = input.read(buf)
                            if (read == -1) break
                            raf.write(buf, 0, read)
                            downloaded += read
                            windowBytes += read

                            val now = System.currentTimeMillis()
                            val elapsed = now - lastTickMs
                            if (elapsed >= 250L) {
                                currentSpeed = (windowBytes * 1000L) / elapsed
                                windowBytes = 0L
                                lastTickMs = now
                                onProgress(downloaded, totalFileBytes, currentSpeed)
                            }
                        }
                    }
                    onProgress(downloaded, totalFileBytes, currentSpeed)
                }
            }
        }

        if (expectedSize != null && partFile.length() != expectedSize) {
            val got = partFile.length()
            if (got > expectedSize) partFile.delete()
            throw IllegalStateException("Incomplete download: ${destFile.name} ($got / $expectedSize bytes)")
        }
        if (!expectedSha256.isNullOrBlank() && !verifySha256(partFile, expectedSha256)) {
            partFile.delete()
            throw IllegalStateException("SHA-256 checksum mismatch: ${destFile.name}")
        }

        if (destFile.exists()) destFile.delete()
        if (!partFile.renameTo(destFile)) {
            partFile.copyTo(destFile, overwrite = true)
            partFile.delete()
        }
    }

    private fun extractArchiveStream(rawStream: InputStream, fileName: String, targetDir: File) {
        val lower = fileName.lowercase()
        val buffered = BufferedInputStream(rawStream, 64 * 1024)
        val archiveIn: ArchiveInputStream<*> = when {
            lower.endsWith(".zip") -> ZipArchiveInputStream(buffered)
            lower.endsWith(".tar.gz") || lower.endsWith(".tgz") ->
                TarArchiveInputStream(GzipCompressorInputStream(buffered))
            lower.endsWith(".tar.bz2") || lower.endsWith(".tbz2") ->
                TarArchiveInputStream(BZip2CompressorInputStream(buffered))
            lower.endsWith(".tar") -> TarArchiveInputStream(buffered)
            else -> throw IllegalArgumentException("Unsupported archive format: $fileName")
        }

        val canonicalTarget = targetDir.canonicalPath + File.separator
        archiveIn.use { ais ->
            while (true) {
                val entry = ais.nextEntry ?: break
                val outFile = File(targetDir, entry.name)
                val canonicalOut = outFile.canonicalPath
                if (!canonicalOut.startsWith(canonicalTarget)) {
                    throw SecurityException("Blocked unsafe archive entry: ${entry.name}")
                }
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { fos ->
                        ais.copyTo(fos, bufferSize = 64 * 1024)
                    }
                }
            }
        }
    }

    private fun flattenSingleNestedDirectoryIfNeeded(modelDir: File) {
        val children = modelDir.listFiles() ?: return
        if (children.size == 1 && children[0].isDirectory) {
            val nested = children[0]
            nested.listFiles()?.forEach { child ->
                child.renameTo(File(modelDir, child.name))
            }
            nested.delete()
        }
    }

    private fun verifySha256(file: File, expectedHex: String): Boolean {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { fis ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val r = fis.read(buf)
                if (r <= 0) break
                digest.update(buf, 0, r)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        return actual.equals(expectedHex.trim(), ignoreCase = true)
    }

    private fun resolveDisplayName(uri: Uri): String? {
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) return cursor.getString(idx)
                    }
                }
        }
        return uri.lastPathSegment?.substringAfterLast('/')
    }

    private fun isArchiveFileName(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".zip") ||
            lower.endsWith(".tar.gz") ||
            lower.endsWith(".tgz") ||
            lower.endsWith(".tar.bz2") ||
            lower.endsWith(".tbz2") ||
            lower.endsWith(".tar")
    }

    private fun sanitizeId(raw: String): String =
        raw.replace(Regex("[^a-zA-Z0-9._-]"), "_").trim('_').take(64).ifEmpty { "model" }

    private fun sanitizeFileName(raw: String): String =
        raw.replace(Regex("[^a-zA-Z0-9._-]"), "_").ifEmpty { "model.bin" }

    private fun updateTransientState(modelId: String, state: ModelInstallState) {
        _downloadStates.update { it + (modelId to state) }
        refreshLocalInventory()
    }

    private fun clearTransientState(modelId: String) {
        _downloadStates.update { it - modelId }
    }
}
