# FreeSpeak IME

[English](#english) | [简体中文](#简体中文)

---

## English

**FreeSpeak IME** is a lightweight, native Android voice input method (IME) that runs **100% on-device**. It is free, open-source, uncensored, privacy-preserving, and built for modern small speech recognition models.

### Why FreeSpeak IME?

Most voice keyboards on Android today force an unpleasant compromise:
- **Cloud-based keyboards** offer good accuracy and punctuation, but require an internet connection, upload your voice to remote servers, and charge expensive subscriptions.
- **Built-in offline keyboards** rely on dated models with poor punctuation and weak code-switching (e.g., speaking English technical terms inside a Chinese sentence).
- **Proprietary domestic keyboards** often impose content filtering/censorship and collect telemetry.
- **Other free voice keyboards** rarely update their bundled models and lock users into a single fixed engine.

FreeSpeak IME solves this by combining **modern compact ASR models** (`Fun-ASR-Nano`, `Qwen3-ASR-0.6B`, `Whisper`, `SenseVoice`) with an **open model manifest system**—allowing users to import or subscribe to newer models anytime without waiting for an app update.

### Key Features

- **100% Offline & Private**: The `InputMethodService` never makes network requests. Audio is processed strictly in local RAM and discarded immediately after transcription. Network access is only used when you explicitly download models.
- **Zero Censorship, Zero Telemetry**: Raw model output goes straight to your text cursor with no word filtering, analytics, or logging.
- **Natural Punctuation & Code-Switching**: Powered by modern models (`Qwen3-ASR-0.6B`, `Fun-ASR-Nano`, `Whisper Small`), outputting punctuated text and handling mixed-language speech naturally.
- **Smart VAD Silence Filtering (No Utterance Splitting)**: Built-in Silero VAD v5 filters leading/trailing dead air and compresses long silent pauses to prevent hallucinations and save CPU—without chopping your speech into fragmented segments.
- **Open & Extensible Models (`model.json`)**:
  - Download curated quantized models via HuggingFace, HF-Mirror, ModelScope, or a custom mirror.
  - Import `.bin`, `.gguf`, `.onnx`, `.zip`, `.tar.gz`, or `.tar.bz2` packages offline from local storage (SAF).
  - Add direct model URLs or subscribe to external community JSON catalogs.
- **Lightweight by Design**:
  - Pure-voice native XML + Canvas IME panel for instant startup (<50 ms) and minimal RAM footprint.
  - Automatic idle model unloading when the keyboard stays hidden, freeing memory in the background.
  - System-locale aware UI (English, Simplified/Traditional Chinese, Japanese, Korean).

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

### Adding Custom Models (`model.json`)

You can import raw `.gguf`/`.bin`/`.onnx` archives directly (FreeSpeak auto-detects common layouts), or bundle a `model.json` at the root of your model archive:

```json
{
  "id": "my-custom-whisper",
  "name": "Whisper Turbo Q5",
  "description": "Custom quantized Whisper model",
  "engineType": "WHISPER_CPP",
  "quantization": "Q5_1",
  "runtimeConfig": {
    "primaryModelFile": "ggml-model-q5_1.bin"
  }
}
```

Supported `engineType` values:
- `WHISPER_CPP`: Any `whisper.cpp` compatible `.bin` or `.gguf` model.
- `FUN_ASR_ONNX`: Single-graph CTC ONNX (`model.int8.onnx` + `tokens.txt`) or 3-part Encoder/Embedding/LLM ONNX.
- `QWEN3_ASR`: Multimodal ONNX (`conv_frontend.onnx` + `encoder.int8.onnx` + `decoder.int8.onnx` + `vocab.json`/`tokenizer.json`).

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

---

## 简体中文

**FreeSpeak IME** 是一款轻量级 Android 原生离线语音输入法。**免费、开源、100% 本地运算、无审查、保护隐私**，专为新一代轻量级多语言语音识别模型打造。

### 为什么要做这款语音输入法？

目前主流的 Android 语音输入法普遍存在明显痛点：
- **云端AI语音输入法**（如 Typeless 等）：体验好但必须联网，语音上传云端，且订阅费用昂贵。
- **系统自带离线语音输入**：底层模型陈旧，缺乏标点符号，中英文夹杂（Code-Switching）几乎无法正常识别。
- **国产商业输入法**：普遍存在内容审查、敏感词过滤以及后台遥测收集。
- **其他免费开源输入法**：多语言能力弱，长期不更新，用户无法自行更换最新的识别模型。

FreeSpeak IME 通过原生多引擎架构集成 **`Fun-ASR-Nano`、`Qwen3-ASR-0.6B`、`Whisper Small`、`SenseVoice`** 等最新量化小模型，并提供开放的模型接入规范——即使开发者不更新应用，用户发现更好的新模型也能随时自行导入使用。

### 核心特点

- **完全离线，保护隐私**：输入法服务（`InputMethodService`）零网络请求，录音数据仅在手机内存中处理并即刻销毁；仅在用户主动下载模型时访问网络。
- **无言论审查，零遥测**：模型原始识别文本直接上屏，无任何词库过滤、无日志上传、无统计埋点。
- **原生标点与中英混输**：直接发挥 `Qwen3-ASR-0.6B`、`Fun-ASR-Nano` 等现代模型优势，自然输出标点符号，精准识别中文里夹杂的英文单词与多语言混说。
- **智能 VAD 静音过滤（不切碎句子）**：内置 Silero VAD v5 仅用于检测语音活动、剔除首尾留白与超长停顿以消除静音幻觉并节省算力，**绝不按静音强行切分句子**，整段语音一次性转写以保留上下文与标点语感。
- **开放模型生态（`model.json`）**：
  - 内置精选模型下载，支持一键切换 `HF-Mirror`、`ModelScope（魔搭）`、`HuggingFace` 或自定义镜像源，支持断点续传。
  - 支持从手机本地存储（SAF）完全离线导入 `.bin` / `.gguf` / `.onnx` / `.zip` / `.tar.gz` / `.tar.bz2` 模型包。
  - 支持直链下载或订阅外部社区维护的 JSON 模型源。
- **极致轻量**：
  - 纯语音输入面板采用原生轻量视图与自定义声波绘制，冷启动极快；支持短按录音与按住说话（上滑取消）。
  - 键盘收起闲置超时后自动卸载模型释放内存，不拖慢手机日常运行。
  - 界面语言自动跟随 Android 系统设置（支持英文、简体/繁体中文、日文、韩文）。

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
