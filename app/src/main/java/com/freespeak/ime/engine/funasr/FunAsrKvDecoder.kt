package com.freespeak.ime.engine.funasr

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import com.freespeak.ime.engine.AsrInputTooLongException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer

/** sherpa-onnx Fun-ASR decoder: full prompt prefill, then single-token steps with KV deltas. */
internal class FunAsrKvDecoder(private val session: OrtSession, contextLimit: Int?) {
    private val shape = session.inputInfo.getValue("cache_key_0").info as TensorInfo
    private val dims = shape.shape.also {
        require(it.size == 4 && it[2] > 0 && it[3] > 0 && shape.type == OnnxJavaType.FLOAT) {
            "Unsupported Fun-ASR KV cache shape or type"
        }
    }
    private val layers = session.inputNames.count { it.startsWith("cache_key_") }
    private val graphCapacity = dims[1].takeIf { it > 0 }?.toInt()
    private val limit = listOfNotNull(
        graphCapacity,
        contextLimit,
        session.metadata.customMetadata["max_total_len"]?.toIntOrNull()?.takeIf { it > 0 },
    ).minOrNull() ?: 512
    val hiddenSize = (session.inputInfo.getValue("inputs_embeds").info as TensorInfo).shape.last().toInt()
    private val stride = Math.toIntExact(dims[2] * dims[3])

    init {
        require(limit > 0)
        require(hiddenSize > 0)
        require(setOf("attention_mask", "cache_position").all { it in session.inputNames })
        require((0 until layers).all {
            "cache_key_$it" in session.inputNames && "cache_value_$it" in session.inputNames &&
                "key_delta_$it" in session.outputNames && "value_delta_$it" in session.outputNames
        } && "logits" in session.outputNames) { "Unsupported Fun-ASR KV decoder layout" }
    }

    data class Output(val tokens: IntArray, val limited: Boolean)

    fun decode(
        env: OrtEnvironment,
        prompt: FloatArray,
        maxNewTokens: Int,
        stopIds: Set<Int>,
        embedding: (Int) -> FloatArray,
    ): Output {
        require(prompt.isNotEmpty() && prompt.size % hiddenSize == 0)
        val promptLen = prompt.size / hiddenSize
        val budget = generationBudget(promptLen, limit, maxNewTokens)
        val capacity = graphCapacity ?: (promptLen + budget - 1)
        val cacheShape = longArrayOf(1, capacity.toLong(), dims[2], dims[3])
        val feed = mutableMapOf<String, OnnxTensor>()
        val caches = ArrayList<Pair<String, FloatBuffer>>(2 * layers)
        val generated = IntArray(budget)
        try {
            for (layer in 0 until layers) {
                for ((part, delta) in listOf("key" to "key_delta", "value" to "value_delta")) {
                    val data = ByteBuffer.allocateDirect(Math.multiplyExact(Math.multiplyExact(capacity, stride), 4))
                        .order(ByteOrder.nativeOrder()).asFloatBuffer()
                    feed["cache_${part}_$layer"] = OnnxTensor.createTensor(env, data, cacheShape)
                    caches += "${delta}_$layer" to data
                }
            }
            var current = prompt
            var past = 0
            for (step in 0 until budget) {
                val seq = current.size / hiddenSize
                val total = past + seq
                val next = runStep(env, current, past, feed, caches, stopIds, step + 1 < budget)
                check(next >= 0) { "Fun-ASR decoder returned no valid token" }
                if (next in stopIds) return Output(generated.copyOf(step), limited = false)
                generated[step] = next
                past = total
                if (step + 1 < budget) current = embedding(next)
            }
            return Output(generated, limited = true)
        } finally {
            feed.values.forEach { it.close() }
        }
    }

    private fun runStep(
        env: OrtEnvironment,
        current: FloatArray,
        past: Int,
        feed: Map<String, OnnxTensor>,
        caches: List<Pair<String, FloatBuffer>>,
        stopIds: Set<Int>,
        hasNextStep: Boolean,
    ): Int {
        val seq = current.size / hiddenSize
        val total = past + seq
        val embedShape = longArrayOf(1, seq.toLong(), hiddenSize.toLong())
        val mask = LongBuffer.wrap(LongArray(total) { 1 })
        val position = LongBuffer.wrap(LongArray(seq) { (past + it).toLong() })
        OnnxTensor.createTensor(env, FloatBuffer.wrap(current), embedShape).use { inputs ->
            OnnxTensor.createTensor(env, mask, longArrayOf(1, total.toLong())).use { maskTensor ->
                OnnxTensor.createTensor(env, position, longArrayOf(seq.toLong())).use { posTensor ->
                    val stepFeed = feed + mapOf("inputs_embeds" to inputs, "attention_mask" to maskTensor, "cache_position" to posTensor)
                    return session.run(stepFeed).use { outputs ->
                        val best = lastTokenArgmax(outputs.get("logits").get() as OnnxTensor)
                        if (best >= 0 && best !in stopIds && hasNextStep) {
                            for ((name, cache) in caches) {
                                val delta = (outputs.get(name).get() as OnnxTensor).floatBuffer
                                check(delta.remaining() == seq * stride) { "Unexpected Fun-ASR cache delta size for $name" }
                                cache.position(past * stride)
                                cache.put(delta)
                                cache.rewind()
                            }
                        }
                        best
                    }
                }
            }
        }
    }

    private fun lastTokenArgmax(logits: OnnxTensor): Int {
        val vocab = logits.info.shape.last().toInt()
        val scores = logits.floatBuffer
        val offset = scores.limit() - vocab
        var best = -1
        var bestScore = Float.NEGATIVE_INFINITY
        for (id in 0 until vocab) {
            val score = scores[offset + id]
            if (score > bestScore) {
                best = id
                bestScore = score
            }
        }
        return best
    }

    companion object {
        internal fun generationBudget(promptTokens: Int, capacity: Int, maxNewTokens: Int): Int {
            // Leave one context slot for generation; never drop audio embeddings to fit.
            if (promptTokens >= capacity) throw AsrInputTooLongException()
            return minOf(capacity - promptTokens, maxNewTokens.coerceAtLeast(1))
        }
    }
}
