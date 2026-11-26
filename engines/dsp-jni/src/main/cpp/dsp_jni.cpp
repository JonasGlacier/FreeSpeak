#include <jni.h>
#include <cmath>
#include <vector>
#include <algorithm>
#include <cstring>

namespace {

constexpr float kPi = 3.14159265358979323846f;

// In-place iterative radix-2 Cooley-Tukey complex FFT (N must be power of 2)
void fftRadix2(std::vector<float>& re, std::vector<float>& im, int n) {
    int j = 0;
    for (int i = 1; i < n; ++i) {
        int bit = n >> 1;
        while (j & bit) {
            j ^= bit;
            bit >>= 1;
        }
        j ^= bit;
        if (i < j) {
            std::swap(re[i], re[j]);
            std::swap(im[i], im[j]);
        }
    }

    for (int len = 2; len <= n; len <<= 1) {
        float ang = -2.0f * kPi / static_cast<float>(len);
        float wlenRe = std::cos(ang);
        float wlenIm = std::sin(ang);
        for (int i = 0; i < n; i += len) {
            float wRe = 1.0f;
            float wIm = 0.0f;
            int half = len >> 1;
            for (int k = 0; k < half; ++k) {
                float uRe = re[i + k];
                float uIm = im[i + k];
                float vRe = re[i + k + half] * wRe - im[i + k + half] * wIm;
                float vIm = re[i + k + half] * wIm + im[i + k + half] * wRe;
                re[i + k] = uRe + vRe;
                im[i + k] = uIm + vIm;
                re[i + k + half] = uRe - vRe;
                im[i + k + half] = uIm - vIm;
                float nextWRe = wRe * wlenRe - wIm * wlenIm;
                wIm = wRe * wlenIm + wIm * wlenRe;
                wRe = nextWRe;
            }
        }
    }
}

inline float hzToKaldiMel(float hz) {
    return 1127.0f * std::log(1.0f + hz / 700.0f);
}

// Slaney mel scale used by librosa / WhisperFeatureExtractor / Qwen3-ASR
inline float hzToSlaneyMel(float freq) {
    constexpr float minLogHz = 1000.0f;
    constexpr float minLogMel = 15.0f;
    const float logStep = std::log(6.4f) / 27.0f;
    if (freq < minLogHz) {
        return 3.0f * freq / 200.0f;
    }
    return minLogMel + std::log(freq / minLogHz) / logStep;
}

inline float slaneyMelToHz(float mel) {
    constexpr float minLogHz = 1000.0f;
    constexpr float minLogMel = 15.0f;
    const float logStep = std::log(6.4f) / 27.0f;
    if (mel < minLogMel) {
        return 200.0f * mel / 3.0f;
    }
    return minLogHz * std::exp(logStep * (mel - minLogMel));
}

std::vector<float> buildKaldiMelBank(int numMels, int fftSize, float sampleRate, float lowFreq, float highFreq) {
    int numBins = fftSize / 2 + 1;
    std::vector<float> bank(static_cast<size_t>(numMels) * numBins, 0.0f);

    float melLow = hzToKaldiMel(lowFreq);
    float melHigh = hzToKaldiMel(highFreq);
    float melDelta = (melHigh - melLow) / static_cast<float>(numMels + 1);
    float fftBinWidth = sampleRate / static_cast<float>(fftSize);

    for (int m = 0; m < numMels; ++m) {
        float leftMel = melLow + m * melDelta;
        float centerMel = melLow + (m + 1) * melDelta;
        float rightMel = melLow + (m + 2) * melDelta;
        for (int b = 0; b < numBins; ++b) {
            float freq = b * fftBinWidth;
            float mel = hzToKaldiMel(freq);
            float weight = 0.0f;
            if (mel > leftMel && mel < rightMel) {
                if (mel <= centerMel) {
                    weight = (mel - leftMel) / (centerMel - leftMel);
                } else {
                    weight = (rightMel - mel) / (rightMel - centerMel);
                }
            }
            bank[static_cast<size_t>(m) * numBins + b] = weight;
        }
    }
    return bank;
}

std::vector<float> buildSlaneyMelBank(int numMels, int nFft, float sampleRate) {
    int numBins = nFft / 2 + 1;
    std::vector<float> bank(static_cast<size_t>(numMels) * numBins, 0.0f);

    float melMin = hzToSlaneyMel(0.0f);
    float melMax = hzToSlaneyMel(sampleRate * 0.5f);
    std::vector<float> filterFreqs(numMels + 2);
    for (int i = 0; i < numMels + 2; ++i) {
        float mel = melMin + (melMax - melMin) * static_cast<float>(i) / static_cast<float>(numMels + 1);
        filterFreqs[i] = slaneyMelToHz(mel);
    }

    std::vector<float> fftFreqs(numBins);
    for (int b = 0; b < numBins; ++b) {
        fftFreqs[b] = (sampleRate * 0.5f) * static_cast<float>(b) / static_cast<float>(numBins - 1);
    }

    for (int m = 0; m < numMels; ++m) {
        float fLeft = filterFreqs[m];
        float fCenter = filterFreqs[m + 1];
        float fRight = filterFreqs[m + 2];
        float enorm = 2.0f / (fRight - fLeft);

        for (int b = 0; b < numBins; ++b) {
            float lower = (fftFreqs[b] - fLeft) / (fCenter - fLeft);
            float upper = (fRight - fftFreqs[b]) / (fRight - fCenter);
            float w = std::max(0.0f, std::min(lower, upper)) * enorm;
            bank[static_cast<size_t>(m) * numBins + b] = w;
        }
    }
    return bank;
}

} // namespace

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_freespeak_engine_dsp_AudioDspNative_extractKaldiFbankLfrCmvn(
        JNIEnv* env,
        jobject /* thiz */,
        jfloatArray pcm16k,
        jint numMels,
        jint lfrM,
        jint lfrN,
        jboolean upscaleSamples,
        jfloatArray cmvnNegMean,
        jfloatArray cmvnInvStd,
        jboolean sherpaFunAsrFrontend) {
    if (pcm16k == nullptr || numMels <= 0) {
        return env->NewFloatArray(0);
    }

    const jsize numSamples = env->GetArrayLength(pcm16k);
    if (numSamples <= 0) {
        return env->NewFloatArray(0);
    }

    std::vector<float> audio(numSamples);
    env->GetFloatArrayRegion(pcm16k, 0, numSamples, audio.data());

    constexpr int kFrameLen = 400;   // 25 ms @ 16 kHz
    constexpr int kFrameShift = 160; // 10 ms @ 16 kHz
    constexpr int kFftSize = 512;
    constexpr int kNumBins = kFftSize / 2 + 1;
    constexpr float kPreemph = 0.97f;

    if (!sherpaFunAsrFrontend && static_cast<int>(audio.size()) < kFrameLen) {
        audio.resize(kFrameLen, 0.0f);
    }

    const float scale = upscaleSamples ? 32768.0f : 1.0f;
    if (scale != 1.0f) {
        for (float& s : audio) {
            s *= scale;
        }
    }

    const int sampleCount = static_cast<int>(audio.size());
    const int numFrames = sherpaFunAsrFrontend ? (sampleCount + kFrameShift / 2) / kFrameShift
                                             : 1 + (sampleCount - kFrameLen) / kFrameShift;
    std::vector<float> window(kFrameLen);
    for (int i = 0; i < kFrameLen; ++i) {
        float a = 2.0f * kPi * static_cast<float>(i) / static_cast<float>(kFrameLen - 1);
        window[i] = sherpaFunAsrFrontend ? 0.54f - 0.46f * std::cos(a)
                                       : std::pow(0.5f - 0.5f * std::cos(a), 0.85f);
    }

    const std::vector<float> melBank = buildKaldiMelBank(numMels, kFftSize, 16000.0f, 20.0f, 8000.0f);
    std::vector<float> fbank(static_cast<size_t>(numFrames) * numMels, 0.0f);

    std::vector<float> re(kFftSize, 0.0f);
    std::vector<float> im(kFftSize, 0.0f);
    std::vector<float> power(kNumBins, 0.0f);
    std::vector<float> reflectedFrame(kFrameLen);

    for (int f = 0; f < numFrames; ++f) {
        const int start = f * kFrameShift - (sherpaFunAsrFrontend ? (kFrameLen - kFrameShift) / 2 : 0);
        const float* frameSrc;
        if (start >= 0 && start + kFrameLen <= sampleCount) {
            frameSrc = audio.data() + start;
        } else {
            for (int i = 0; i < kFrameLen; ++i) {
                int pos = start + i;
                while (pos < 0 || pos >= sampleCount) {
                    pos = pos < 0 ? -pos - 1 : 2 * sampleCount - 1 - pos;
                }
                reflectedFrame[i] = audio[pos];
            }
            frameSrc = reflectedFrame.data();
        }
        float mean = 0.0f;
        for (int i = 0; i < kFrameLen; ++i) {
            mean += frameSrc[i];
        }
        mean /= static_cast<float>(kFrameLen);

        std::fill(re.begin(), re.end(), 0.0f);
        std::fill(im.begin(), im.end(), 0.0f);

        float prev = frameSrc[0] - mean;
        re[0] = (prev * (1.0f - kPreemph)) * window[0];
        for (int i = 1; i < kFrameLen; ++i) {
            float cur = frameSrc[i] - mean;
            re[i] = (cur - kPreemph * prev) * window[i];
            prev = cur;
        }

        fftRadix2(re, im, kFftSize);

        for (int b = 0; b < kNumBins; ++b) {
            power[b] = re[b] * re[b] + im[b] * im[b];
        }

        float* dst = fbank.data() + static_cast<size_t>(f) * numMels;
        for (int m = 0; m < numMels; ++m) {
            const float* weights = melBank.data() + static_cast<size_t>(m) * kNumBins;
            float energy = 0.0f;
            for (int b = 0; b < kNumBins; ++b) {
                energy += weights[b] * power[b];
            }
            dst[m] = std::log(std::max(energy, 1.1920929e-7f));
        }
    }

    // Apply LFR (Low Frame Rate) if requested
    std::vector<float> output;
    int outDim = numMels;
    if (lfrM > 1 && lfrN >= 1) {
        outDim = numMels * lfrM;
        const int leftPad = sherpaFunAsrFrontend ? 0 : (lfrM - 1) / 2;
        const int totalPadded = numFrames + leftPad;
        const int numLfrFrames = sherpaFunAsrFrontend
            ? (numFrames < lfrM ? 0 : 1 + (numFrames - lfrM) / lfrN)
            : std::max<int>(1, (totalPadded + lfrN - 1) / lfrN);
        output.resize(static_cast<size_t>(numLfrFrames) * outDim);

        for (int t = 0; t < numLfrFrames; ++t) {
            float* outRow = output.data() + static_cast<size_t>(t) * outDim;
            int startPadded = t * lfrN;
            for (int k = 0; k < lfrM; ++k) {
                int srcIdx = startPadded + k - leftPad;
                if (srcIdx < 0) srcIdx = 0;
                if (srcIdx >= numFrames) srcIdx = numFrames - 1;
                std::memcpy(
                    outRow + static_cast<size_t>(k) * numMels,
                    fbank.data() + static_cast<size_t>(srcIdx) * numMels,
                    static_cast<size_t>(numMels) * sizeof(float)
                );
            }
        }
    } else {
        output = std::move(fbank);
    }

    // Apply optional CMVN: (x + negMean) * invStd
    if (cmvnNegMean != nullptr && cmvnInvStd != nullptr) {
        const jsize meanLen = env->GetArrayLength(cmvnNegMean);
        const jsize stdLen = env->GetArrayLength(cmvnInvStd);
        if (meanLen == outDim && stdLen == outDim) {
            std::vector<float> negMean(outDim);
            std::vector<float> invStd(outDim);
            env->GetFloatArrayRegion(cmvnNegMean, 0, outDim, negMean.data());
            env->GetFloatArrayRegion(cmvnInvStd, 0, outDim, invStd.data());

            const size_t totalFrames = output.size() / static_cast<size_t>(outDim);
            for (size_t t = 0; t < totalFrames; ++t) {
                float* row = output.data() + t * static_cast<size_t>(outDim);
                for (int d = 0; d < outDim; ++d) {
                    row[d] = (row[d] + negMean[d]) * invStd[d];
                }
            }
        }
    }

    jfloatArray result = env->NewFloatArray(static_cast<jsize>(output.size()));
    if (!output.empty()) {
        env->SetFloatArrayRegion(result, 0, static_cast<jsize>(output.size()), output.data());
    }
    return result;
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_freespeak_engine_dsp_AudioDspNative_extractWhisperMelSpectrogram(
        JNIEnv* env,
        jobject /* thiz */,
        jfloatArray pcm16k,
        jint numMels,
        jint padOrTrimSamples) {
    if (pcm16k == nullptr || (numMels != 80 && numMels != 128)) {
        return env->NewFloatArray(0);
    }

    const jsize rawLen = env->GetArrayLength(pcm16k);
    if (rawLen <= 0 && padOrTrimSamples <= 0) {
        return env->NewFloatArray(0);
    }

    std::vector<float> audio(rawLen);
    if (rawLen > 0) {
        env->GetFloatArrayRegion(pcm16k, 0, rawLen, audio.data());
    }

    if (padOrTrimSamples > 0) {
        if (static_cast<int>(audio.size()) > padOrTrimSamples) {
            audio.resize(padOrTrimSamples);
        } else if (static_cast<int>(audio.size()) < padOrTrimSamples) {
            audio.resize(padOrTrimSamples, 0.0f);
        }
    } else if (static_cast<int>(audio.size()) < 400) {
        audio.resize(400, 0.0f);
    }

    constexpr int kNFft = 400;
    constexpr int kHop = 160;
    constexpr int kPad = kNFft / 2; // 200 reflect padding
    constexpr int kNumBins = kNFft / 2 + 1; // 201

    // Center reflect pad matching torch.stft(..., center=True, pad_mode="reflect")
    const int n = static_cast<int>(audio.size());
    std::vector<float> padded(n + 2 * kPad, 0.0f);
    for (int i = 0; i < kPad; ++i) {
        int idx = std::min(n - 1, kPad - i);
        padded[i] = audio[std::max(0, idx)];
    }
    std::memcpy(padded.data() + kPad, audio.data(), static_cast<size_t>(n) * sizeof(float));
    for (int i = 0; i < kPad; ++i) {
        int idx = std::max(0, n - 2 - i);
        padded[kPad + n + i] = audio[idx];
    }

    // WhisperFeatureExtractor drops the trailing frame (stft[..., :-1])
    int totalStftFrames = 1 + (static_cast<int>(padded.size()) - kNFft) / kHop;
    int numFrames = std::max(1, totalStftFrames - 1);

    std::vector<float> hann(kNFft);
    for (int i = 0; i < kNFft; ++i) {
        hann[i] = 0.5f * (1.0f - std::cos(2.0f * kPi * static_cast<float>(i) / static_cast<float>(kNFft)));
    }

    // Precompute DFT cosine/sine tables for exact 400-point STFT
    std::vector<float> cosTable(static_cast<size_t>(kNumBins) * kNFft);
    std::vector<float> sinTable(static_cast<size_t>(kNumBins) * kNFft);
    for (int b = 0; b < kNumBins; ++b) {
        for (int i = 0; i < kNFft; ++i) {
            float angle = 2.0f * kPi * static_cast<float>(b * i) / static_cast<float>(kNFft);
            cosTable[static_cast<size_t>(b) * kNFft + i] = std::cos(angle) * hann[i];
            sinTable[static_cast<size_t>(b) * kNFft + i] = std::sin(angle) * hann[i];
        }
    }

    const std::vector<float> melBank = buildSlaneyMelBank(numMels, kNFft, 16000.0f);

    // Channel-first layout: [numMels, numFrames]
    std::vector<float> logMel(static_cast<size_t>(numMels) * numFrames, 0.0f);
    std::vector<float> power(kNumBins, 0.0f);
    float maxLogVal = -1e20f;

    for (int f = 0; f < numFrames; ++f) {
        const float* src = padded.data() + f * kHop;
        for (int b = 0; b < kNumBins; ++b) {
            const float* cRow = cosTable.data() + static_cast<size_t>(b) * kNFft;
            const float* sRow = sinTable.data() + static_cast<size_t>(b) * kNFft;
            float reSum = 0.0f;
            float imSum = 0.0f;
            for (int i = 0; i < kNFft; ++i) {
                reSum += src[i] * cRow[i];
                imSum -= src[i] * sRow[i];
            }
            power[b] = reSum * reSum + imSum * imSum;
        }

        for (int m = 0; m < numMels; ++m) {
            const float* weights = melBank.data() + static_cast<size_t>(m) * kNumBins;
            float spec = 0.0f;
            for (int b = 0; b < kNumBins; ++b) {
                spec += weights[b] * power[b];
            }
            float val = std::log10(std::max(spec, 1e-10f));
            logMel[static_cast<size_t>(m) * numFrames + f] = val;
            if (val > maxLogVal) {
                maxLogVal = val;
            }
        }
    }

    const float minClamp = maxLogVal - 8.0f;
    for (float& v : logMel) {
        v = (std::max(v, minClamp) + 4.0f) * 0.25f;
    }

    jfloatArray result = env->NewFloatArray(static_cast<jsize>(logMel.size()));
    env->SetFloatArrayRegion(result, 0, static_cast<jsize>(logMel.size()), logMel.data());
    return result;
}

extern "C" JNIEXPORT jfloat JNICALL
Java_com_freespeak_engine_dsp_AudioDspNative_computeNormalizedRms(
        JNIEnv* env,
        jobject /* thiz */,
        jfloatArray pcmChunk,
        jint length) {
    if (pcmChunk == nullptr || length <= 0) return 0.0f;
    const jsize arrLen = env->GetArrayLength(pcmChunk);
    const int n = std::min(static_cast<int>(arrLen), static_cast<int>(length));
    if (n <= 0) return 0.0f;

    std::vector<float> buf(n);
    env->GetFloatArrayRegion(pcmChunk, 0, n, buf.data());

    double sumSq = 0.0;
    for (int i = 0; i < n; ++i) {
        sumSq += static_cast<double>(buf[i]) * static_cast<double>(buf[i]);
    }
    float rms = static_cast<float>(std::sqrt(sumSq / static_cast<double>(n)));
    // Map -45 dBFS .. -5 dBFS into [0.0, 1.0]
    if (rms <= 1e-5f) return 0.0f;
    float db = 20.0f * std::log10(rms);
    return std::clamp((db + 45.0f) / 40.0f, 0.0f, 1.0f);
}
