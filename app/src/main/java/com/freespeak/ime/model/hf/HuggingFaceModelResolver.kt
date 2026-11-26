package com.freespeak.ime.model.hf

import com.freespeak.ime.model.BuiltinModelCatalog
import com.freespeak.ime.model.EngineType
import com.freespeak.ime.model.ModelFileEntry
import com.freespeak.ime.model.ModelManifest
import com.freespeak.ime.model.ModelRuntimeConfig
import com.freespeak.ime.model.isCanary
import com.freespeak.ime.model.hasRumbleFormatting

enum class HfVariantStatus {
    /** Layout matches a graph the bundled engines are known to run. */
    SUPPORTED,

    /** Layout looks plausible for a bundled engine but has not been verified. */
    UNVERIFIED,
}

enum class HfIssue {
    GATED,
    DISABLED,
    GGUF,
    CTRANSLATE2,
    PYTORCH,
    WHISPER_ONNX,
    TRANSDUCER,
    OTHER_RUNTIME,
    UNKNOWN_ONNX,
    NO_WEIGHTS,
}

data class HfFile(
    val path: String,
    val size: Long?,
    val sha256: String?,
)

data class HfVariant(
    val key: String,
    val label: String,
    val engineType: EngineType,
    val status: HfVariantStatus,
    val quantization: String,
    /** Files keyed by their install-relative path. */
    val files: Map<String, HfFile>,
    val runtimeConfig: ModelRuntimeConfig,
    val languages: List<String>,
    val supportsLanguageHint: Boolean?,
    val supportsTranslateToEnglish: Boolean?,
) {
    val totalBytes: Long get() = files.values.sumOf { it.size ?: 0L }
}

data class HfRepoAnalysis(
    val info: HfModelInfo,
    val variants: List<HfVariant>,
    val issues: Set<HfIssue>,
) {
    val supported: List<HfVariant> get() = variants.filter { it.status == HfVariantStatus.SUPPORTED }
    val unverified: List<HfVariant> get() = variants.filter { it.status == HfVariantStatus.UNVERIFIED }
    val blocked: Boolean get() = HfIssue.GATED in issues || HfIssue.DISABLED in issues
}

/**
 * Classifies a Hugging Face repo file tree against the graph layouts supported by the
 * bundled engines. File extensions alone are never treated as proof of compatibility.
 */
object HuggingFaceModelResolver {

    private val WHISPER_LANGS = listOf("auto", "zh", "en", "ja", "ko", "fr", "de", "es", "ru")
    private val QWEN_LANGS = listOf("auto", "zh", "en", "yue", "ja", "ko", "fr", "de", "es", "ru")
    private val SENSEVOICE_LANGS = listOf("auto", "zh", "en", "yue", "ja", "ko")

    private val GGML_BIN = Regex("""(^|/)ggml-[^/]+\.bin$""", RegexOption.IGNORE_CASE)
    private val QUANT_TOKEN = Regex("""^(q\d(_\d|_k)?|f16|f32|fp16|fp32|int8|int4|uint8|q8)$""", RegexOption.IGNORE_CASE)

    fun analyze(info: HfModelInfo): HfRepoAnalysis {
        val issues = LinkedHashSet<HfIssue>()
        if (info.isGated) issues += HfIssue.GATED
        if (info.disabled) issues += HfIssue.DISABLED

        val files = info.siblings.map { HfFile(it.rfilename, it.size ?: it.lfs?.size, it.lfs?.sha256) }
            .filterNot { isIgnorable(it.path) }
        val variants = ArrayList<HfVariant>()

        variants += whisperVariants(info, files)
        pinnedVariant(info, files)?.let { variants += it }
        // Do not offer a second generic Qwen entry that would lose Rumble's prompt controls.
        if (builtinFor(info.id)?.hasRumbleFormatting != true) variants += qwen3Variants(info, files)
        variants += funAsrLlmVariants(info, files)
        variants += ctcVariants(info, files, issues)

        detectUnsupported(files, issues, consumed = variants.flatMap { v -> v.files.values.map { it.path } }.toSet())

        if (variants.isEmpty() && issues.none { it != HfIssue.GATED && it != HfIssue.DISABLED }) {
            issues += HfIssue.NO_WEIGHTS
        }
        val distinct = variants.distinctBy { it.key }
            .sortedWith(compareBy<HfVariant> { it.status.ordinal }.thenBy { it.totalBytes })
        return HfRepoAnalysis(info, distinct, issues)
    }

    private fun pinnedVariant(info: HfModelInfo, files: List<HfFile>): HfVariant? {
        val model = builtinFor(info.id)?.takeIf { it.engineType.isCanary || it.hasRumbleFormatting } ?: return null
        val matched = model.files.associate { entry ->
            val file = files.firstOrNull {
                it.path == entry.relativePath && (it.size == null || it.size == entry.sizeBytes) &&
                    (it.sha256 == null || it.sha256.equals(entry.sha256, ignoreCase = true))
            } ?: return null
            entry.relativePath to file
        }
        return HfVariant(model.id, model.name, model.engineType, HfVariantStatus.SUPPORTED,
            model.quantization, matched, model.runtimeConfig, model.languages,
            model.supportsLanguageHint, model.supportsTranslateToEnglish)
    }

    // ---------------------------------------------------------------- whisper.cpp (GGML)

    private fun whisperVariants(info: HfModelInfo, files: List<HfFile>): List<HfVariant> =
        files.filter { f ->
            GGML_BIN.containsMatchIn(f.path) &&
                !f.path.contains("for-tests", ignoreCase = true) &&
                !f.path.contains("encoder.mlmodelc", ignoreCase = true) &&
                (f.size == null || f.size > 1_000_000L)
        }.map { f ->
            val base = f.path.substringAfterLast('/')
            val stem = base.removePrefix("ggml-").removeSuffix(".bin")
            val englishOnly = Regex("""\.en(?:[-.]|$)""", RegexOption.IGNORE_CASE).containsMatchIn(stem)
            val quant = stem.substringAfterLast('-', "").takeIf { QUANT_TOKEN.matches(it) }?.uppercase() ?: "F16"
            HfVariant(
                key = "ggml:" + f.path,
                label = stem,
                engineType = EngineType.WHISPER_CPP,
                status = HfVariantStatus.SUPPORTED,
                quantization = quant,
                files = mapOf(base to f),
                runtimeConfig = ModelRuntimeConfig(primaryModelFile = base),
                languages = if (englishOnly) listOf("en") else WHISPER_LANGS,
                supportsLanguageHint = !englishOnly,
                supportsTranslateToEnglish = !englishOnly,
            )
        }

    // ---------------------------------------------------------------- Qwen3-ASR (3-graph ONNX)

    private fun qwen3Variants(info: HfModelInfo, files: List<HfFile>): List<HfVariant> {
        val out = ArrayList<HfVariant>()
        val byDir = files.groupBy { dirOf(it.path) }
        for ((dir, dirFiles) in byDir) {
            val names = dirFiles.associateBy { it.path.substringAfterLast('/') }
            val convs = names.keys.filter { it.startsWith("conv_frontend", true) && it.endsWith(".onnx", true) }
            if (convs.isEmpty()) continue
            val encoders = names.keys.filter { it.startsWith("encoder", true) && it.endsWith(".onnx", true) }
            val decoders = names.keys.filter { it.startsWith("decoder", true) && it.endsWith(".onnx", true) }
            if (names.keys.any { it.startsWith("joiner", true) }) continue
            val tokenizer = findQwenTokenizer(files, dir) ?: continue

            for (enc in encoders) {
                val q = quantOf(enc, "encoder")
                val dec = decoders.firstOrNull { quantOf(it, "decoder") == q } ?: continue
                val conv = convs.firstOrNull { quantOf(it, "conv_frontend") == q }
                    ?: convs.firstOrNull { quantOf(it, "conv_frontend") == "" }
                    ?: convs.first()
                val selected = LinkedHashMap<String, HfFile>()
                for (onnx in listOf(conv, enc, dec)) {
                    addWithExternalData(selected, dir, names.getValue(onnx), dirFiles)
                }
                tokenizer.forEach { (rel, f) -> selected[rel] = f }
                out += HfVariant(
                    key = "qwen3:$dir:$q",
                    label = labelFor(dir, q),
                    engineType = EngineType.QWEN3_ASR,
                    status = HfVariantStatus.SUPPORTED,
                    quantization = q.ifEmpty { "FP32" }.uppercase(),
                    files = selected,
                    runtimeConfig = ModelRuntimeConfig(
                        convFrontendFile = conv,
                        encoderFile = enc,
                        decoderFile = dec,
                        vocabFile = tokenizer.keys.first { it.endsWith("vocab.json") || it.endsWith("tokenizer.json") },
                        numMels = 128,
                        maxNewTokens = 128,
                    ),
                    languages = QWEN_LANGS,
                    supportsLanguageHint = true,
                    supportsTranslateToEnglish = false,
                )
            }
        }
        return out
    }

    /** Returns tokenizer files (install-relative path -> file) for Qwen-family BPE tokenizers. */
    private fun findQwenTokenizer(files: List<HfFile>, rootDir: String): Map<String, HfFile>? {
        val vocab = files.filter { it.path.endsWith("vocab.json") }
            .minByOrNull { relDepth(it.path, rootDir) }
            ?: files.filter { it.path.endsWith("tokenizer.json") }.minByOrNull { relDepth(it.path, rootDir) }
            ?: return null
        val vocabDir = dirOf(vocab.path)
        val targetDir = when {
            isUnder(vocabDir, rootDir) -> relativeTo(vocabDir, rootDir)
            else -> "tokenizer"
        }
        val out = LinkedHashMap<String, HfFile>()
        val companions = setOf("vocab.json", "tokenizer.json", "tokenizer_config.json", "merges.txt")
        files.filter { dirOf(it.path) == vocabDir && it.path.substringAfterLast('/') in companions }
            .sortedBy { if (it.path == vocab.path) 0 else 1 }
            .forEach { f ->
                // Skip a large tokenizer.json when vocab.json is available (loader prefers vocab.json).
                if (f.path.endsWith("tokenizer.json") && vocab.path.endsWith("vocab.json")) return@forEach
                out[joinPath(targetDir, f.path.substringAfterLast('/'))] = f
            }
        return out
    }

    // ---------------------------------------------------------------- Fun-ASR-Nano LLM (3-graph ONNX)

    private fun funAsrLlmVariants(info: HfModelInfo, files: List<HfFile>): List<HfVariant> {
        val out = ArrayList<HfVariant>()
        for ((dir, dirFiles) in files.groupBy { dirOf(it.path) }) {
            val names = dirFiles.associateBy { it.path.substringAfterLast('/') }
            val encs = names.keys.filter { it.startsWith("encoder_adaptor", true) && it.endsWith(".onnx", true) }
            val embs = names.keys.filter { it.startsWith("embedding", true) && it.endsWith(".onnx", true) }
            val llms = names.keys.filter { it.startsWith("llm", true) && it.endsWith(".onnx", true) }
            if (encs.isEmpty() || embs.isEmpty() || llms.isEmpty()) continue
            val tokenizer = findQwenTokenizer(files, dir) ?: continue
            for (enc in encs) {
                val q = quantOf(enc, "encoder_adaptor")
                val emb = embs.firstOrNull { quantOf(it, "embedding") == q } ?: embs.first()
                val llm = llms.firstOrNull { quantOf(it, "llm") == q } ?: continue
                val selected = LinkedHashMap<String, HfFile>()
                for (onnx in listOf(enc, emb, llm)) addWithExternalData(selected, dir, names.getValue(onnx), dirFiles)
                tokenizer.forEach { (rel, f) -> selected[rel] = f }
                out += HfVariant(
                    key = "funllm:$dir:$q",
                    label = labelFor(dir, q),
                    engineType = EngineType.FUN_ASR_ONNX,
                    status = HfVariantStatus.SUPPORTED,
                    quantization = q.ifEmpty { "FP32" }.uppercase(),
                    files = selected,
                    runtimeConfig = ModelRuntimeConfig(
                        encoderFile = enc,
                        embeddingFile = emb,
                        decoderFile = llm,
                        vocabFile = tokenizer.keys.first(),
                        numMels = 80,
                    ),
                    languages = SENSEVOICE_LANGS,
                    supportsLanguageHint = false,
                    supportsTranslateToEnglish = false,
                )
            }
        }
        return out
    }

    // ---------------------------------------------------------------- SenseVoice / Fun-ASR CTC (single ONNX)

    private fun ctcVariants(info: HfModelInfo, files: List<HfFile>, issues: MutableSet<HfIssue>): List<HfVariant> {
        val out = ArrayList<HfVariant>()
        val repoLower = info.id.lowercase()
        for ((dir, dirFiles) in files.groupBy { dirOf(it.path) }) {
            val names = dirFiles.associateBy { it.path.substringAfterLast('/') }
            val tokens = names["tokens.txt"] ?: continue
            val onnxNames = names.keys.filter { it.endsWith(".onnx", true) }
            if (onnxNames.isEmpty()) continue
            // Multi-graph exports (transducer, whisper-onnx, qwen3) are handled elsewhere.
            if (onnxNames.any { n ->
                    listOf("encoder", "decoder", "joiner", "conv_frontend", "embedding", "llm").any { n.contains(it, true) }
                }
            ) continue
            val models = onnxNames.filter { it.startsWith("model", true) }
            if (models.isEmpty()) {
                issues += HfIssue.UNKNOWN_ONNX
                continue
            }

            val ctx = (repoLower + "/" + dir.lowercase())
            val isFunAsr = ctx.contains("funasr") || ctx.contains("fun-asr")
            val isSenseVoice = ctx.contains("sense-voice") || ctx.contains("sensevoice") || ctx.contains("sense_voice")
            val builtin = builtinFor(info.id)

            for (model in models) {
                val q = quantOf(model, "model")
                val selected = LinkedHashMap<String, HfFile>()
                addWithExternalData(selected, dir, names.getValue(model), dirFiles)
                selected["tokens.txt"] = tokens

                val status: HfVariantStatus
                val cfg: ModelRuntimeConfig
                val hint: Boolean?
                when {
                    builtin != null && builtin.engineType == EngineType.FUN_ASR_ONNX -> {
                        status = HfVariantStatus.SUPPORTED
                        cfg = builtin.runtimeConfig.copy(primaryModelFile = model, vocabFile = "tokens.txt")
                        hint = builtin.supportsLanguageHint
                    }
                    isSenseVoice && !isFunAsr -> {
                        status = HfVariantStatus.SUPPORTED
                        cfg = ModelRuntimeConfig(
                            primaryModelFile = model,
                            vocabFile = "tokens.txt",
                            numMels = 80,
                            lfrWindowSize = 7,
                            lfrWindowShift = 6,
                            upscaleSamples = true,
                            blankId = 0,
                        )
                        hint = true
                    }
                    else -> {
                        // Paraformer / Zipformer-CTC / NeMo-CTC / Fun-ASR-Nano CTC forks etc. share
                        // the model.onnx + tokens.txt layout but use different front-ends or decoders.
                        status = HfVariantStatus.UNVERIFIED
                        cfg = ModelRuntimeConfig(
                            primaryModelFile = model,
                            vocabFile = "tokens.txt",
                            numMels = 80,
                        )
                        hint = false
                    }
                }
                out += HfVariant(
                    key = "ctc:$dir:$model",
                    label = labelFor(dir, q),
                    engineType = EngineType.FUN_ASR_ONNX,
                    status = status,
                    quantization = q.ifEmpty { "FP32" }.uppercase(),
                    files = selected,
                    runtimeConfig = cfg,
                    languages = SENSEVOICE_LANGS,
                    supportsLanguageHint = hint,
                    supportsTranslateToEnglish = false,
                )
            }
        }
        return out
    }

    private fun builtinFor(repoId: String): ModelManifest? =
        BuiltinModelCatalog.models.firstOrNull { m ->
            m.files.any { it.url.startsWith("https://huggingface.co/$repoId/") }
        }

    // ---------------------------------------------------------------- unsupported formats

    private fun detectUnsupported(files: List<HfFile>, issues: MutableSet<HfIssue>, consumed: Set<String>) {
        val names = files.map { it.path.lowercase() }
        val baseNames = names.map { it.substringAfterLast('/') }
        if (files.any { it.path.endsWith(".gguf", true) && it.path !in consumed }) issues += HfIssue.GGUF
        if (baseNames.any { it == "model.bin" } &&
            baseNames.any { it == "config.json" } &&
            baseNames.any { it.startsWith("vocabulary") || it == "tokenizer.json" }
        ) issues += HfIssue.CTRANSLATE2
        if (names.any {
                it.endsWith(".safetensors") || it.endsWith(".pt") || it.endsWith(".pth") || it.endsWith(".ckpt") ||
                    it.endsWith(".nemo") || it.substringAfterLast('/').startsWith("pytorch_model")
            }
        ) issues += HfIssue.PYTORCH
        if (names.any {
                it.endsWith(".tflite") || it.endsWith(".mlmodelc.zip") || it.endsWith(".mlpackage") ||
                    it.endsWith(".ncnn.param") || it.endsWith(".rknn") || it.endsWith(".axmodel") ||
                    it.endsWith(".engine") || it.endsWith(".mnn")
            } && names.none { GGML_BIN.containsMatchIn(it) }
        ) issues += HfIssue.OTHER_RUNTIME

        val unconsumedOnnx = files.filter { it.path.endsWith(".onnx", true) && it.path !in consumed }
        if (unconsumedOnnx.isEmpty()) return
        val onnxBase = unconsumedOnnx.map { it.path.substringAfterLast('/').lowercase() }
        when {
            onnxBase.any { it.contains("joiner") } -> issues += HfIssue.TRANSDUCER
            onnxBase.any { Regex("""^(tiny|base|small|medium|large|distil|turbo)[\w.-]*-(encoder|decoder)""").containsMatchIn(it) } ||
                (onnxBase.any { it.contains("encoder") } && onnxBase.any { it.contains("decoder") } &&
                    files.any { it.path.lowercase().endsWith("-tokens.txt") }) -> issues += HfIssue.WHISPER_ONNX
            consumed.none { it.endsWith(".onnx", true) } -> issues += HfIssue.UNKNOWN_ONNX
        }
    }

    // ---------------------------------------------------------------- manifest

    fun toManifest(info: HfModelInfo, variant: HfVariant, totalVariants: Int): ModelManifest {
        builtinFor(info.id)?.takeIf { (it.engineType.isCanary || it.hasRumbleFormatting) && it.id == variant.key }?.let { return it }
        val sha = info.sha ?: "main"
        val repoName = info.id.substringAfter('/')
        val entries = variant.files.map { (rel, f) ->
            ModelFileEntry(
                relativePath = rel,
                url = "https://huggingface.co/${info.id}/resolve/$sha/${encodePath(f.path)}",
                sha256 = f.sha256,
                sizeBytes = f.size,
            )
        }
        val total = variant.totalBytes
        val mb = (total / (1024 * 1024)).toInt()
        return ModelManifest(
            id = manifestId(info.id, variant.key),
            name = if (totalVariants > 1) "$repoName · ${variant.label}" else repoName,
            description = info.id,
            engineType = variant.engineType,
            version = sha.take(7),
            sizeBytes = total,
            languages = variant.languages,
            quantization = variant.quantization,
            recommendedRamMb = (mb * 1.3 + 150).toInt(),
            files = entries,
            runtimeConfig = variant.runtimeConfig,
            sourceTag = "huggingface",
            supportsLanguageHint = variant.supportsLanguageHint,
            supportsTranslateToEnglish = variant.supportsTranslateToEnglish,
            sourceRepo = info.id,
            sourceRevision = info.sha,
        )
    }

    /** Deterministic id (<= 64 chars) so a repo variant maps to the same installed model. */
    fun manifestId(repoId: String, variantKey: String): String {
        val slug = repoId.substringAfter('/').lowercase().replace(Regex("[^a-z0-9._-]"), "_").take(36)
        val hash = (repoId + "|" + variantKey).hashCode().toUInt().toString(16).padStart(8, '0')
        return "hf-$slug-$hash"
    }

    // ---------------------------------------------------------------- helpers

    private fun addWithExternalData(
        out: MutableMap<String, HfFile>,
        rootDir: String,
        onnx: HfFile,
        dirFiles: List<HfFile>,
    ) {
        out[relativeTo(onnx.path, rootDir)] = onnx
        val base = onnx.path.substringAfterLast('/')
        val stem = base.removeSuffix(".onnx")
        val candidates = setOf("$base.data", "${base}_data", "$stem.data", "$stem.onnx_data", "$stem.weights")
        dirFiles.filter { it.path.substringAfterLast('/') in candidates }
            .forEach { out[relativeTo(it.path, rootDir)] = it }
    }

    /** "encoder.int8.onnx" with prefix "encoder" -> "int8"; "encoder.onnx" -> "". */
    internal fun quantOf(fileName: String, prefix: String): String {
        val middle = fileName.removeSuffix(".onnx").removePrefix(prefix).trim('.', '-', '_')
        return middle.lowercase()
    }

    private fun labelFor(dir: String, quant: String): String {
        val parts = listOfNotNull(dir.substringAfterLast('/').takeIf { it.isNotBlank() }, quant.takeIf { it.isNotBlank() }?.uppercase())
        return parts.joinToString(" · ").ifEmpty { "FP32" }
    }

    private fun isIgnorable(path: String): Boolean {
        val lower = path.lowercase()
        val base = lower.substringAfterLast('/')
        return base.startsWith(".") || base == "readme" || base.endsWith(".md") ||
            lower.startsWith("test_wavs/") || lower.contains("/test_wavs/") ||
            base.endsWith(".wav") || base.endsWith(".mp3") || base.endsWith(".flac") ||
            base.endsWith(".png") || base.endsWith(".jpg") || base.endsWith(".py") || base.endsWith(".sh")
    }

    private fun dirOf(path: String): String = path.substringBeforeLast('/', "")

    private fun isUnder(dir: String, root: String): Boolean =
        root.isEmpty() || dir == root || dir.startsWith("$root/")

    private fun relativeTo(path: String, root: String): String =
        if (root.isEmpty()) path else path.removePrefix("$root/")

    private fun relDepth(path: String, root: String): Int =
        if (isUnder(dirOf(path), root)) relativeTo(path, root).count { it == '/' } else 100 + path.count { it == '/' }

    private fun joinPath(dir: String, name: String): String = if (dir.isEmpty()) name else "$dir/$name"

    private fun encodePath(path: String): String =
        path.split('/').joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
}
