#include <jni.h>
#include <algorithm>
#include <cstdlib>
#include <cstring>
#include <exception>
#include <memory>
#include <vector>

#include "canary.h"

namespace {
void fail(JNIEnv* env, const char* message) {
    if (env->ExceptionCheck()) return;
    if (const auto type = env->FindClass("java/lang/IllegalStateException")) {
        env->ThrowNew(type, message);
    }
}

class UtfChars {
public:
    UtfChars(JNIEnv* env, jstring value) : env_(env), value_(value),
        data(value ? env->GetStringUTFChars(value, nullptr) : nullptr) {}
    ~UtfChars() { if (data) env_->ReleaseStringUTFChars(value_, data); }
    UtfChars(const UtfChars&) = delete;
    UtfChars& operator=(const UtfChars&) = delete;
private:
    JNIEnv* env_;
    jstring value_;
public:
    const char* const data;
};
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_freespeak_engine_canary_CanaryGgufNative_open(JNIEnv* env, jobject, jstring path, jint threads) {
    try {
        const UtfChars model_path(env, path);
        if (!model_path.data) return 0;
        auto params = canary_context_default_params();
        params.n_threads = std::clamp(static_cast<int>(threads), 1, 8);
        params.use_gpu = false;
        params.verbosity = 0;
        return reinterpret_cast<jlong>(canary_init_from_file(model_path.data, params));
    } catch (const std::exception&) {
        fail(env, "Could not load Canary GGUF model");
        return 0;
    }
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_freespeak_engine_canary_CanaryGgufNative_infer(JNIEnv* env, jobject, jlong handle, jfloatArray audio,
                                                     jstring source, jstring target) {
    try {
        const UtfChars src(env, source);
        if (!src.data) return nullptr;
        const UtfChars tgt(env, target);
        if (!tgt.data) return nullptr;
        const auto count = env->GetArrayLength(audio);
        std::vector<float> pcm(count);
        env->GetFloatArrayRegion(audio, 0, count, pcm.data());
        if (env->ExceptionCheck()) return nullptr;
        std::unique_ptr<canary_result, decltype(&canary_result_free)> result(
            canary_transcribe_streamed(reinterpret_cast<canary_context*>(handle), pcm.data(), count,
                                       src.data, tgt.data, true, 0, 0, 0), canary_result_free);
        if (!result || !result->text) {
            fail(env, "Canary GGUF inference failed");
            return nullptr;
        }
        const auto size = static_cast<jsize>(std::strlen(result->text));
        const auto out = env->NewByteArray(size);
        if (out) env->SetByteArrayRegion(out, 0, size, reinterpret_cast<const jbyte*>(result->text));
        return out;
    } catch (const std::exception&) {
        fail(env, "Canary GGUF inference failed");
        return nullptr;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_freespeak_engine_canary_CanaryGgufNative_close(JNIEnv*, jobject, jlong handle) {
    canary_free(reinterpret_cast<canary_context*>(handle));
}
