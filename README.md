# 大肥鱼 · 手表对话宠物（FatFish）

> A voice/keyboard chat pet that runs on Android-based smartwatches (e.g. OPPO Watch). It listens locally (offline ASR), thinks in the cloud (any OpenAI-compatible LLM), and replies as floating text bubbles — no GPU required.

一只跑在 **OPPO Watch 等魔改安卓表**（完整 Android 系统）上的对话宠物。程序化绘制的大肥鱼会游动、眨眼、冒泡泡，支持**按住说话 / 键盘打字 → 本地离线听懂 → 云端大模型思考 → 气泡文字回复**。

- **本地离线听**：sherpa-onnx + Paraformer-zh-small，纯 CPU 推理，手表芯片即可跑，**不需要 CUDA/GPU**。
- **云端想**：OpenAI 兼容协议，支持 DeepSeek 官方、任意中转站、Anthropic Claude。
- **气泡说**：回复以漂浮文本框展示（无本地语音合成，省资源）。

---

## ✨ 功能特性

| 能力 | 说明 |
|---|---|
| 🐟 大肥鱼动画 | Canvas 程序化绘制，6 种状态：待机游动 / 聆听 / 思考冒泡 / 说话 / 开心蹦跳 / 犯困 |
| 🎙️ 本地语音识别 | Paraformer-zh-small int8（约 79MB），离线 CPU 推理 |
| ⌨️ 键盘文字输入 | 输入方式二选一，适合不方便开口的场景 |
| 🧠 云端大模型 | OpenAI 兼容接口：DeepSeek / 中转站 / Claude，支持 http 明文地址 |
| 🤖 Agent 能力 | function calling：查天气（wttr.in）、联网搜索（必应）、系统时间 |
| 🎭 6 套人格 | 软萌 / 傲娇 / 摸鱼 / 毒舌 / 元气 / 知性，**各自独立对话与记忆** |
| 🧠 持久记忆 | 多轮对话持久化，重启不丢 |
| ⚙️ 丰富设置 | 大肥鱼大小无极调节、随重力旋转、返回按钮、模型列表拉取 |
| 📱 手机配置推送 | 配套 FatFishPhone，HTTP 一键把配置推给手表（告别手表小屏填 Key） |

## 🏗️ 架构

```
语音输入：按住说话 → AudioRecord 录音(16k PCM)
键盘输入：底部输入框打字
        ↓
① 本地 ASR   sherpa-onnx + Paraformer-zh-small → 文字        （离线，CPU 推理）
        ↓
② 云端大脑   OpenAI 兼容接口（DeepSeek / 中转站 / Claude）     （唯一联网环节）
             Agent function calling：天气 + 联网搜索 + 时间
        ↓
③ 气泡回复   漂浮文本框展示文字
```

## 📁 目录结构

```
FatFish/
├── watch/                      # 手表端（OPPO Watch 等魔改安卓表）
│   ├── app/src/main/
│   │   ├── java/com/example/fatfish/     # 应用核心代码
│   │   ├── java/com/k2fsa/sherpa/onnx/   # sherpa-onnx Kotlin 绑定层（官方源码）
│   │   ├── assets/asr/                   # ASR 模型（已内置，79MB）
│   │   ├── jniLibs/                      # sherpa-onnx + onnxruntime 预编译 .so
│   │   └── res/                          # 布局 / 大肥鱼帧图 / 图标
│   └── build.gradle / settings.gradle / gradlew
├── phone/                      # 手机配置端（HTTP 服务推配置给手表）
├── docs/releases.md            # APK 版本清单 + Release 上传指引
├── CHANGELOG.md                # 版本迭代记录
├── NOTICE                      # 第三方组件版权声明
└── LICENSE                     # Apache 2.0
```

## 🚀 快速开始

### 环境要求

- JDK 17、Android SDK（compileSdk 34）
- 手表需为**完整 Android 系统**（OPPO Watch 等魔改表），minSdk 26（Android 8.0+）

### 构建

```bash
# 手表端
cd watch
./gradlew assembleDebug        # 产物：app/build/outputs/apk/debug/app-debug.apk
# 或 assembleRelease

# 手机配置端
cd phone
./gradlew assembleDebug
```

> 国内网络下 gradle wrapper 已配置腾讯镜像下载 Gradle 8.5；如需切换官方源，改
> `watch/gradle/wrapper/gradle-wrapper.properties` 里的 `distributionUrl`。

### 侧载到手表

```bash
adb connect <手表IP>:5555
adb devices                     # 确认在线
adb install -r app-debug.apk
adb shell am start -n com.example.fatfish/.MainActivity
```

> 手表需先开开发者模式 + USB/WiFi 调试；部分 OPPO Watch 首次连接要配对码。

## ⚙️ 配置大模型

手表设置页（或手机配置端）填写：

| 字段 | 说明 |
|---|---|
| API 地址 | OpenAI 兼容 base_url，如 `https://api.deepseek.com` |
| 协议 | `openai`（OpenAI 兼容）/ `anthropic`（Claude） |
| 模型名 | 如 `deepseek-chat`，或点「获取可用模型」自动拉取 `/models` 列表 |
| API Key | 你的 Key；中转站 Key 不一定 `sk-` 开头，非空即视为已配置 |
| 城市 | 查天气用的默认城市 |

支持 **http 明文地址**（自建中转站）。

### 手机端一键推配置（推荐）

手表屏幕小、填 Key 麻烦，用配套手机端代替：

1. 手机安装 `FatFishPhone_v1.apk`，填写 API 地址/协议/模型/Key/城市，打开同步服务，界面显示 `http://手机IP:8765`。
2. 手表设置页 → 「远程拉取配置」→ 输入手机显示的地址 → 一键拉取覆盖。
3. 前提：手机、手表连**同一 WiFi**；地址会自动记住。

> 手动填写方式完全保留。

## 🎭 人格系统

6 套人格，**每个人格独立对话框、独立记忆**，可在设置页「人格」子页面切换、清空记忆：

| 人格 | 风格 |
|---|---|
| 软萌 | 撒娇、软乎乎，语气治愈 |
| 傲娇 | 嘴硬心软，口是心非 |
| 摸鱼 | 能拖就拖，佛系摆烂 |
| 毒舌 | 犀利吐槽，一针见血 |
| 元气 | 热情满满，元气打气 |
| 知性 | 理性温和，有条理 |

## 📦 下载（Releases）

本仓库**不提交 APK**，所有安装包通过 **GitHub Releases** 分发：

- 最新手表端：`FatFishWatch_v15.apk`
- 手机配置端：`FatFishPhone_v1.apk`
- 历史版本（v3～v14）已永久留档，完整清单见 [docs/releases.md](docs/releases.md) 与 [CHANGELOG.md](CHANGELOG.md)。

上传方式见 [docs/releases.md](docs/releases.md#上传指引)。

## 📜 版本历史

见 [CHANGELOG.md](CHANGELOG.md)。当前最新：**v15**（远程拉取配置，对接手机端）。

## 🙏 技术栈与致谢

- [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx)（Apache 2.0）：本地 ASR 推理引擎与 Kotlin 绑定
- [ONNX Runtime](https://github.com/microsoft/onnxruntime)（MIT）：模型推理运行时
- [FunASR / 阿里达摩院](https://github.com/modelscope/FunASR)：Paraformer-zh-small 语音识别模型
- [wttr.in](https://wttr.in)：免费天气接口
- 必应搜索：联网检索能力

第三方组件许可详见 [NOTICE](NOTICE)。

## 📄 License

[Apache License 2.0](LICENSE) © 2026 FatFish contributors
