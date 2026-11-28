# FreeSpeak IME

[English](#english) | [简体中文](#简体中文)

---

## English

**FreeSpeak IME** is a lightweight, native Android voice input method (IME) that runs **100% on-device**. It is free, open-source, uncensored, privacy-preserving, and built for modern small speech recognition models.

### Why FreeSpeak IME?

Most voice keyboards on Android today force an unpleasant compromise:
- **Cloud-based keyboards** offer good accuracy and punctuation, but require an internet connection, upload your voice to remote servers, and charge expensive subscriptions.
- **Built-in offline keyboards** rely on dated models with poor punctuation and weak code-switching (e.g., speaking English technical terms inside a Chinese sentence).
- **Made-in-China keyboards** impose content filtering, censorship and collect telemetry.
- **Other free voice keyboards** rarely update their bundled models and lock users into a single fixed engine.

FreeSpeak IME has modern compact ASR models. These models run on your phone, offering fast processing speeds and high recognition accuracy. Some models include advanced features such as translation and "um uh" removal.

### Key Features

- **100% Offline & Private**: The `InputMethodService` never makes network requests. Audio is processed strictly in local RAM and discarded immediately after transcription. Network access is only used when you explicitly download models.
- **Zero Censorship, Zero Telemetry**: No app-level word filtering, analytics, or content moderation. Optional Chinese script conversion is applied before insertion.
- **Natural Punctuation & Code-Switching**: Powered by modern models (`Qwen3-ASR-0.6B`, `Fun-ASR-Nano`, `Whisper Small`), outputting punctuated text and handling mixed-language speech naturally.
- **Smart VAD Silence Filtering (No Utterance Splitting)**: Built-in Silero VAD v5 filters leading/trailing dead air and compresses long silent pauses to prevent hallucinations and save CPU—without chopping your speech into fragmented segments.
- **Lightweight by Design**:
  - Pure-voice native XML + Canvas IME panel for low UI overhead; model loading and inference costs vary by device.
  - Automatic idle model unloading when the keyboard stays hidden, freeing memory in the background.

### Architecture

```text
FreeSpeakIME/
├── app/                          # Android IME service, minimalist Settings UI, Onboarding, Model Hub
│   └── src/main/java/com/freespeak/ime/
│       ├── audio/                # 16kHz mono PCM capture + Silero VAD v5 silence filter
│       ├── engine/               # Unified AsrEngine interface & idle-unloading EngineManager
│       ├── model/                # Declarative model.json codec, resumable downloader, SAF importer
│       ├── data/                 # DataStore user preferences
│       ├── ime/                  # FreeSpeakInputMethodService & custom Canvas waveform view
│       └── ui/                   # Onboarding flow + clean Settings list & detail sub-screens
└── engines/
    ├── dsp-jni/                  # C++17 Kaldi Fbank + LFR + CMVN & 128-bin Slaney Mel frontend
    ├── whisper-jni/              # C/C++17 whisper.cpp (v1.7.4) + ggml-cpu ARMv8.2-FP16 JNI bridge
    └── qwen3-asr-jni/            # C++17 greedy/CTC logits kernel & Byte-Level BPE / Base64 tokenizer
```

### Optional NeMo number and text formatting

Settings → **Number & text formatting** provides model-independent, offline
inverse text normalization (ITN). Download a language pack and enable the toggle
to format spoken numbers, dates, times, currency and measurements with full
NVIDIA NeMo grammars. Choose a formatting language explicitly when the model
does not report its output language. Coverage depends on the language grammar.

Eight language packs are listed in the app and downloaded on demand from the
[NeMo pack release](https://github.com/JonasGlacier/FreeSpeak/releases/tag/nemo-itn-46033201756-1).
See [pack export and publishing instructions](tools/nemo-itn-export/README.md).

### Building from Source

Requirements:
- JDK 17+
- Android SDK 36 & NDK `28.2.13676358` (with CMake `3.22.1`)

```bash
# Run unit tests and build Debug APK
./gradlew testDebugUnitTest assembleDebug

# Install on a connected arm64-v8a Android 8.0+ device
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### License

Open-source under the Apache License 2.0 (bundled `whisper.cpp` and `silero-vad` components are under the MIT License).
OpenCC dictionary copyright, source version, license and adapter details are included in `app/src/main/assets/opencc/`.

---

## 简体中文

**FreeSpeak IME** 是一款轻量级 Android 原生离线语音输入法。**免费、开源、100% 本地运算、无审查、保护隐私**，专为新一代轻量级多语言语音识别模型打造。能够识别各种中文方言，可切换简体/繁体输出，支持台湾国语词汇自动转换。

### 为什么要做这款语音输入法？

目前主流的 Android 语音输入法普遍存在明显痛点：
- **云端AI语音输入法**（如 Typeless 等）：体验好，但语音上传云端，且订阅费用很昂贵。
- **系统自带语音输入**：（如 Google Voice Input）底层模型陈旧，缺乏标点符号，中英文夹杂（Code-Switching）几乎无法正常识别。
- **中国产商业输入法**：（如微信输入法）存在内容审查、敏感词过滤以及后台遥测收集，有資安隐患。
- **其他免费开源输入法**：多语言能力弱，长期不更新，用户无法自行更换最新的识别模型。

FreeSpeak IME 的多引擎架构集成了 **`Fun-ASR-Nano`、`Qwen3-ASR-0.6B`、`SenseVoice`** 等最新量化小模型。这些模型在你手机上运行，速度快，识别准确率高。部分模型带有自动翻译等高级功能。

### 核心特点

- **完全离线，保护隐私**：输入法服务（`InputMethodService`）零网络请求，录音数据仅在手机内存中处理并即刻销毁；仅在用户主动下载模型时访问网络。
- **无言论审查，零遥测**：无敏感词过滤、无内容审查、无日志上传和统计埋点；可选中文简繁转换在上屏前完成。
- **中文方言与中英混输**：发挥 `Qwen3-ASR-0.6B`等现代模型优势，自然输出标点符号，识别多种中文方言，与多语言混说。
- **智能 VAD 静音过滤（不切碎句子）**：内置 Silero VAD v5 仅用于检测语音活动、剔除首尾留白与超长停顿以消除静音幻觉并节省算力。
- **轻量**：
  - 纯语音输入面板采用原生轻量视图，冷启动快。
  - 键盘收起闲置超时后自动卸载模型释放内存，不拖慢手机日常运行。

### 自动翻译与中文变体

- 提供 [Fluid Speech Pro by FluidVoice（ONNX INT8，CC BY 4.0）](https://huggingface.co/FluidVoice-Labs/fluid-speech-pro-onnx)。它自动识别 25 种欧洲语言，可在模型设置中开启「清理转写」。

- 提供 **Canary 1B v2** 的 [ONNX INT8](https://huggingface.co/Sarphix/canary-1b-v2-sherpa-onnx-int8)（1.42 GB）和 [GGUF Q4_K](https://huggingface.co/cstr/canary-1b-v2-GGUF)（611 MB）社区量化版。两者基于 [NVIDIA Canary 1B v2（CC BY 4.0）](https://huggingface.co/nvidia/canary-1b-v2)，支持 25 种欧洲语言转写以及英语与其他 24 种语言的双向翻译。GGUF 使用独立的 [CrispASR](https://github.com/CrispStrobe/CrispASR) CPU 后端。

- 內置 **OpenCC 1.1.9 官方詞典**，通過 Kotlin 最長前綴匹配適配器離線按需加載。TW 使用台灣模式、HK/MO 使用香港模式。台灣模式包含慣用詞，香港模式調整字形，不套用台灣術語。 英文、數字和標點不變；識別為日文、包含日文假名或英文翻譯時跳過轉換。自動檢測下純漢字日文仍可能存在歧義，需完全保留原字形可選擇「保持模型原文」。

### 编译与安装

环境要求：
- JDK 17+
- Android SDK 36、NDK `28.2.13676358`、CMake `3.22.1`

```bash
# 运行单元测试并构建 Debug 安装包
./gradlew testDebugUnitTest assembleDebug

# 安装至 arm64-v8a 安卓手机（Android 8.0+）
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### 开源协议

本项目基于 Apache License 2.0 协议开源（其中内置的 `whisper.cpp` 与 `silero-vad` 组件遵循 MIT 协议）。
OpenCC 词典的版权、固定版本、协议及适配器说明见 `app/src/main/assets/opencc/`。
