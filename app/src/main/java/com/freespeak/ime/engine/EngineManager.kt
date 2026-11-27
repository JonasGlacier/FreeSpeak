package com.freespeak.ime.engine

import com.freespeak.ime.engine.canary.CanaryEngine
import com.freespeak.ime.engine.canary.CanaryGgufEngine
import com.freespeak.ime.engine.fluid.FluidSpeechEngine
import com.freespeak.ime.engine.funasr.FunAsrNanoEngine
import com.freespeak.ime.engine.qwen3asr.Qwen3AsrEngine
import com.freespeak.ime.model.EngineType
import com.freespeak.ime.model.ModelManifest
import com.freespeak.ime.model.ModelRepository
import com.freespeak.ime.text.OpenCcConverter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

sealed interface EngineRuntimeStatus {
    data object Unloaded : EngineRuntimeStatus
    data class Loading(val modelName: String) : EngineRuntimeStatus
    data class Ready(val modelId: String, val modelName: String) : EngineRuntimeStatus
    data class Transcribing(val modelName: String) : EngineRuntimeStatus
    data class Error(val message: String) : EngineRuntimeStatus
}

/**
 * Thread-safe coordinator managing ASR engine lifecycle, lazy loading, serial native execution,
 * and automatic idle memory unloading when the IME panel is hidden.
 */
class EngineManager(
    private val modelRepository: ModelRepository,
    private val chineseConverter: OpenCcConverter,
    private val itnProcessor: com.freespeak.ime.itn.NemoItnProcessor? = null,
) {
    private val engineDispatcher = Dispatchers.Default.limitedParallelism(1)
    private val managerScope = CoroutineScope(SupervisorJob() + engineDispatcher)
    private val mutex = Mutex()

    private var currentEngine: AsrEngine? = null
    private var loadedModelId: String? = null
    private var loadedThreads: Int = -1
    private var idleUnloadJob: Job? = null

    private val _status = MutableStateFlow<EngineRuntimeStatus>(EngineRuntimeStatus.Unloaded)
    val status: StateFlow<EngineRuntimeStatus> = _status.asStateFlow()

    suspend fun ensureModelLoaded(
        modelId: String,
        numThreads: Int,
    ): Result<ModelManifest> = mutex.withLock {
        idleUnloadJob?.cancel()
        idleUnloadJob = null

        val resolvedManifest = resolveTargetManifest(modelId)
            ?: return Result.failure(IllegalStateException("No offline speech model installed"))

        if (currentEngine?.isLoaded == true &&
            loadedModelId == resolvedManifest.id &&
            loadedThreads == numThreads
        ) {
            _status.value = EngineRuntimeStatus.Ready(resolvedManifest.id, resolvedManifest.name)
            return Result.success(resolvedManifest)
        }

        return withContext(engineDispatcher) {
            _status.value = EngineRuntimeStatus.Loading(resolvedManifest.name)
            currentEngine?.release()
            currentEngine = null
            loadedModelId = null
            System.gc()

            val newEngine = createEngine(resolvedManifest.engineType)
            val modelDir = modelRepository.getModelDirectory(resolvedManifest.id)

            newEngine.load(modelDir, resolvedManifest, numThreads).fold(
                onSuccess = {
                    currentEngine = newEngine
                    loadedModelId = resolvedManifest.id
                    loadedThreads = numThreads
                    _status.value = EngineRuntimeStatus.Ready(resolvedManifest.id, resolvedManifest.name)
                    Result.success(resolvedManifest)
                },
                onFailure = { err ->
                    newEngine.release()
                    val msg = err.message ?: "Model load failed"
                    _status.value = EngineRuntimeStatus.Error(msg)
                    Result.failure(err)
                }
            )
        }
    }

    suspend fun transcribe(
        pcm16k: FloatArray,
        preferredModelId: String,
        options: AsrInferenceOptions,
    ): Result<AsrTranscriptionResult> {
        val manifest = ensureModelLoaded(preferredModelId, options.numThreads).getOrElse {
            return Result.failure(it)
        }

        return mutex.withLock {
            withContext(engineDispatcher) {
                val eng = currentEngine
                    ?: return@withContext Result.failure(IllegalStateException("Engine not ready"))
                _status.value = EngineRuntimeStatus.Transcribing(manifest.name)
                val effectiveOptions = options.forModel(manifest)
                val res = eng.transcribe(pcm16k, effectiveOptions).mapCatching { result ->
                    val outputLanguage = if (manifest.engineType == EngineType.CANARY_ONNX || manifest.engineType == EngineType.CANARY_GGUF) {
                        val source = com.freespeak.ime.model.CanaryLanguages.source(manifest, effectiveOptions.language)
                        com.freespeak.ime.model.CanaryLanguages.target(manifest, source, effectiveOptions.targetLanguage)
                    } else result.detectedLanguage ?: effectiveOptions.language
                    val itnLanguage = com.freespeak.ime.itn.resolveItnLanguage(
                        effectiveOptions.itnLanguage, null, outputLanguage, effectiveOptions.language,
                    )
                    val text = if (effectiveOptions.itnEnabled) {
                        itnProcessor?.normalize(result.text, itnLanguage) ?: result.text
                    } else result.text
                    result.copy(text = chineseConverter.convert(
                        text, effectiveOptions.chineseOutputMode,
                        outputLanguage,
                        effectiveOptions.targetLanguage == "en",
                    ))
                }
                if (res.isSuccess || res.exceptionOrNull() is AsrInputTooLongException) {
                    _status.value = EngineRuntimeStatus.Ready(manifest.id, manifest.name)
                } else {
                    _status.value = EngineRuntimeStatus.Error(res.exceptionOrNull()?.message ?: "Transcription failed")
                }
                res
            }
        }
    }

    fun onImeShown(preferredModelId: String, numThreads: Int) {
        idleUnloadJob?.cancel()
        idleUnloadJob = null
        managerScope.launch {
            if (currentEngine?.isLoaded != true || (preferredModelId.isNotBlank() && preferredModelId != loadedModelId)) {
                ensureModelLoaded(preferredModelId, numThreads)
            }
        }
    }

    fun onImeHidden(idleUnloadMinutes: Int) {
        idleUnloadJob?.cancel()
        if (idleUnloadMinutes <= 0) return
        idleUnloadJob = managerScope.launch {
            delay(idleUnloadMinutes * 60_000L)
            unloadNow()
        }
    }

    suspend fun unloadNow() = mutex.withLock {
        withContext(engineDispatcher) {
            itnProcessor?.releaseAll()
            currentEngine?.release()
            currentEngine = null
            loadedModelId = null
            _status.value = EngineRuntimeStatus.Unloaded
        }
    }

    private fun resolveTargetManifest(preferredId: String): ModelManifest? {
        if (preferredId.isNotBlank()) {
            modelRepository.findInstalledManifest(preferredId)?.let { return it }
        }
        return modelRepository.listInstalledManifests().firstOrNull()
    }

    private fun createEngine(type: EngineType): AsrEngine = when (type) {
        EngineType.FUN_ASR_ONNX -> FunAsrNanoEngine()
        EngineType.QWEN3_ASR -> Qwen3AsrEngine()
        EngineType.CANARY_ONNX -> CanaryEngine()
        EngineType.CANARY_GGUF -> CanaryGgufEngine()
        EngineType.FLUID_TDT -> FluidSpeechEngine()
    }
}
