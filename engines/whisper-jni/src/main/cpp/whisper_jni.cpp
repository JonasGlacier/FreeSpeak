#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <algorithm>
#include "whisper.h"

#define LOG_TAG "FreeSpeakWhisperJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

std::string trimWhitespace(const std::string& s) {
    size_t start = 0;
    while (start < s.size() && (s[start] == ' ' || s[start] == '\t' || s[start] == '\n' || s[start] == '\r')) {
        ++start;
    }
    size_t end = s.size();
    while (end > start && (s[end - 1] == ' ' || s[end - 1] == '\t' || s[end - 1] == '\n' || s[end - 1] == '\r')) {
        --end;
    }
    return s.substr(start, end - start);
}

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_freespeak_engine_whisper_WhisperNative_initContext(
        JNIEnv* env,
        jobject /* thiz */,
        jstring modelPath) {
    if (modelPath == nullptr) return 0L;
    const char* pathChars = env->GetStringUTFChars(modelPath, nullptr);
    if (pathChars == nullptr) return 0L;

    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;
    cparams.flash_attn = true;

    LOGI("Loading Whisper model from: %s", pathChars);
    whisper_context* ctx = whisper_init_from_file_with_params(pathChars, cparams);
    env->ReleaseStringUTFChars(modelPath, pathChars);

    if (ctx == nullptr) {
        LOGE("Failed to initialize whisper_context");
        return 0L;
    }
    return reinterpret_cast<jlong>(ctx);
}

extern "C" JNIEXPORT void JNICALL
Java_com_freespeak_engine_whisper_WhisperNative_freeContext(
        JNIEnv* /* env */,
        jobject /* thiz */,
        jlong contextPtr) {
    auto* ctx = reinterpret_cast<whisper_context*>(contextPtr);
    if (ctx != nullptr) {
        whisper_free(ctx);
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_freespeak_engine_whisper_WhisperNative_isMultilingual(
        JNIEnv* /* env */,
        jobject /* thiz */,
        jlong contextPtr) {
    auto* ctx = reinterpret_cast<whisper_context*>(contextPtr);
    if (ctx == nullptr) return JNI_FALSE;
    return whisper_is_multilingual(ctx) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_freespeak_engine_whisper_WhisperNative_transcribe(
        JNIEnv* env,
        jobject /* thiz */,
        jlong contextPtr,
        jfloatArray pcm16k,
        jint numThreads,
        jstring language,
        jstring initialPrompt,
        jboolean translateToEnglish) {
    auto* ctx = reinterpret_cast<whisper_context*>(contextPtr);
    if (ctx == nullptr || pcm16k == nullptr) {
        return env->NewStringUTF("");
    }

    const jsize audioLen = env->GetArrayLength(pcm16k);
    if (audioLen <= 0) {
        return env->NewStringUTF("");
    }

    std::vector<float> samples(audioLen);
    env->GetFloatArrayRegion(pcm16k, 0, audioLen, samples.data());

    std::string langStr = "auto";
    if (language != nullptr) {
        const char* langChars = env->GetStringUTFChars(language, nullptr);
        if (langChars != nullptr) {
            langStr = langChars;
            env->ReleaseStringUTFChars(language, langChars);
        }
    }

    std::string promptStr;
    if (initialPrompt != nullptr) {
        const char* promptChars = env->GetStringUTFChars(initialPrompt, nullptr);
        if (promptChars != nullptr) {
            promptStr = promptChars;
            env->ReleaseStringUTFChars(initialPrompt, promptChars);
        }
    }

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.print_realtime = false;
    params.print_progress = false;
    params.print_timestamps = false;
    params.print_special = false;
    params.translate = (translateToEnglish == JNI_TRUE);
    params.n_threads = std::max(1, static_cast<int>(numThreads));
    params.no_context = true;
    params.single_segment = false;
    params.suppress_blank = true;
    params.suppress_nst = true;
    params.temperature = 0.0f;
    params.temperature_inc = 0.2f;

    if (!whisper_is_multilingual(ctx)) {
        params.language = "en";
        params.detect_language = false;
    } else if (langStr.empty() || langStr == "auto") {
        params.language = "auto";
    } else {
        params.language = langStr.c_str();
    }

    if (!promptStr.empty()) {
        params.initial_prompt = promptStr.c_str();
    }

    int rc = whisper_full(ctx, params, samples.data(), static_cast<int>(samples.size()));
    if (rc != 0) {
        LOGE("whisper_full failed with code %d", rc);
        return env->NewStringUTF("");
    }

    const int nSegments = whisper_full_n_segments(ctx);
    std::string fullText;
    for (int i = 0; i < nSegments; ++i) {
        const char* seg = whisper_full_get_segment_text(ctx, i);
        if (seg != nullptr) {
            fullText += seg;
        }
    }

    std::string cleaned = trimWhitespace(fullText);
    return env->NewStringUTF(cleaned.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_freespeak_engine_whisper_WhisperNative_getDetectedLanguage(
        JNIEnv* env,
        jobject /* thiz */,
        jlong contextPtr) {
    auto* ctx = reinterpret_cast<whisper_context*>(contextPtr);
    if (ctx == nullptr) return env->NewStringUTF("");
    int langId = whisper_full_lang_id(ctx);
    if (langId < 0) return env->NewStringUTF("");
    const char* langShort = whisper_lang_str(langId);
    return env->NewStringUTF(langShort != nullptr ? langShort : "");
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_freespeak_engine_whisper_WhisperNative_getSystemInfo(
        JNIEnv* env,
        jobject /* thiz */) {
    const char* info = whisper_print_system_info();
    return env->NewStringUTF(info != nullptr ? info : "");
}
