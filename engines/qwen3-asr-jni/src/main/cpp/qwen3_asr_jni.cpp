#include <jni.h>
#include <vector>
#include <cmath>
#include <limits>
#include <cstring>
#include <algorithm>

extern "C" JNIEXPORT jint JNICALL
Java_com_freespeak_engine_qwen3asr_Qwen3AsrNative_selectNextTokenGreedy(
        JNIEnv* env,
        jobject /* thiz */,
        jfloatArray logitsArr,
        jint logitsOffset,
        jint vocabSize,
        jintArray generatedTokens,
        jintArray suppressTokens,
        jfloat repetitionPenalty) {
    if (logitsArr == nullptr || vocabSize <= 0) return -1;

    const jsize totalLen = env->GetArrayLength(logitsArr);
    if (logitsOffset < 0 || logitsOffset + vocabSize > totalLen) return -1;

    std::vector<float> logits(vocabSize);
    env->GetFloatArrayRegion(logitsArr, logitsOffset, vocabSize, logits.data());

    // Apply repetition penalty on previously generated tokens
    if (generatedTokens != nullptr && repetitionPenalty > 1.0f) {
        const jsize genLen = env->GetArrayLength(generatedTokens);
        if (genLen > 0) {
            std::vector<jint> history(genLen);
            env->GetIntArrayRegion(generatedTokens, 0, genLen, history.data());
            for (jint tok : history) {
                if (tok >= 0 && tok < vocabSize) {
                    float score = logits[tok];
                    logits[tok] = (score < 0.0f) ? (score * repetitionPenalty) : (score / repetitionPenalty);
                }
            }
        }
    }

    // Suppress forbidden tokens
    if (suppressTokens != nullptr) {
        const jsize supLen = env->GetArrayLength(suppressTokens);
        if (supLen > 0) {
            std::vector<jint> sup(supLen);
            env->GetIntArrayRegion(suppressTokens, 0, supLen, sup.data());
            for (jint tok : sup) {
                if (tok >= 0 && tok < vocabSize) {
                    logits[tok] = -1e30f;
                }
            }
        }
    }

    int bestIdx = 0;
    float bestVal = std::isfinite(logits[0]) ? logits[0] : -1e30f;
    for (int i = 1; i < vocabSize; ++i) {
        float cur = std::isfinite(logits[i]) ? logits[i] : -1e30f;
        if (cur > bestVal) {
            bestVal = cur;
            bestIdx = i;
        }
    }
    return bestIdx;
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_freespeak_engine_qwen3asr_Qwen3AsrNative_spliceMultimodalEmbeddings(
        JNIEnv* env,
        jobject /* thiz */,
        jfloatArray prefixEmbeds,
        jfloatArray audioEmbeds,
        jfloatArray suffixEmbeds) {
    const jsize pLen = prefixEmbeds != nullptr ? env->GetArrayLength(prefixEmbeds) : 0;
    const jsize aLen = audioEmbeds != nullptr ? env->GetArrayLength(audioEmbeds) : 0;
    const jsize sLen = suffixEmbeds != nullptr ? env->GetArrayLength(suffixEmbeds) : 0;

    const jsize total = pLen + aLen + sLen;
    jfloatArray merged = env->NewFloatArray(total);
    if (total == 0) return merged;

    std::vector<float> buf(total);
    if (pLen > 0) env->GetFloatArrayRegion(prefixEmbeds, 0, pLen, buf.data());
    if (aLen > 0) env->GetFloatArrayRegion(audioEmbeds, 0, aLen, buf.data() + pLen);
    if (sLen > 0) env->GetFloatArrayRegion(suffixEmbeds, 0, sLen, buf.data() + pLen + aLen);

    env->SetFloatArrayRegion(merged, 0, total, buf.data());
    return merged;
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_freespeak_engine_qwen3asr_Qwen3AsrNative_ctcGreedyDecodeIds(
        JNIEnv* env,
        jobject /* thiz */,
        jfloatArray logitsFlat,
        jint timeSteps,
        jint vocabSize,
        jint blankId) {
    if (logitsFlat == nullptr || timeSteps <= 0 || vocabSize <= 0) {
        return env->NewIntArray(0);
    }

    const jsize expected = timeSteps * vocabSize;
    if (env->GetArrayLength(logitsFlat) < expected) {
        return env->NewIntArray(0);
    }

    std::vector<float> logits(expected);
    env->GetFloatArrayRegion(logitsFlat, 0, expected, logits.data());

    std::vector<jint> decoded;
    decoded.reserve(timeSteps / 2);

    int prevId = -1;
    for (int t = 0; t < timeSteps; ++t) {
        const float* row = logits.data() + static_cast<size_t>(t) * vocabSize;
        int maxId = 0;
        float maxVal = row[0];
        for (int v = 1; v < vocabSize; ++v) {
            if (row[v] > maxVal) {
                maxVal = row[v];
                maxId = v;
            }
        }
        if (maxId != blankId && maxId != prevId) {
            decoded.push_back(maxId);
        }
        prevId = maxId;
    }

    jintArray out = env->NewIntArray(static_cast<jsize>(decoded.size()));
    if (!decoded.empty()) {
        env->SetIntArrayRegion(out, 0, static_cast<jsize>(decoded.size()), decoded.data());
    }
    return out;
}
