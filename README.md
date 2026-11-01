# BonsaiAgent —— 本地 AI 混合 Agent（Android 原生 Kotlin + Compose）

纯原生 UI（Jetpack Compose，无 WebView 套壳），内置 **PrismML fork 的 llama.cpp**（分支 `prism`）
做本地推理，用来跑你的 **Ternary-Bonsai-8B-Q2_0** 三值量化模型。标准 llama.cpp / MediaPipe 不支持 Q2_0，
所以必须用这个 fork，本项目已按它的 C API 写好 JNI 封装。

三个页面：
1. **对话**：本地模型 ChatGPT 式聊天，流式逐字（打字机）输出。
2. **路由 / Agent**：极简系统提示词，模型输出 `[CALL_CLOUD]` 自动调 DeepSeek；输出 `[EXEC: 命令]` 在本机执行 shell 并回传。
3. **设置**：滑动条调推理参数、系统提示词、下载源、DeepSeek Key、本机 API 开关、导入 .gguf 模型。

---

## 一、为什么必须在电脑上编

- 推理引擎是 **C++（llama.cpp）**，要用 **Android NDK + CMake** 交叉编译成 `libbonsai_llm.so`。
- 手机（Termux）没有 NDK，也没有 Android SDK，`gradle` 编不出 APK。
- 所以：**沙箱和手机都编不了**，只能在装了 Android Studio + NDK 的电脑上编。下面是从零步骤。

---

## 二、电脑从零安装到编出 APK（一步步）

### 1. 安装 Android Studio
- 去 https://developer.android.com/studio 下载并安装（Windows / macOS / Linux 都行）。
- 首次启动会进 Setup Wizard，按默认装好 **Android SDK**（API 34）。

### 2. 安装 NDK 和 CMake
- 打开 Android Studio → `File → Settings → Android SDK → SDK Tools` 标签页。
- 勾选：
  - **NDK (Side by side)**（建议 25.x 或 26.x）
  - **CMake**（勾选后安装）
- 点 Apply 安装。装完记下 NDK 路径（如 `~/Android/Sdk/ndk/25.2.9519653`），一般不用手动配，Gradle 会自动找。

### 3. 下载 PrismML fork 的 llama.cpp 源码（关键）
本项目 JNI 只引用它，不会自动下载，需你手动 clone 到指定位置：

```bash
cd BonsaiAgent/app/src/main/cpp
git clone --branch prism https://github.com/PrismML-Eng/llama.cpp llamacpp
cd llamacpp
git submodule update --init --recursive   # 若 fork 用了 submodule
```

放好后目录应是：
```
app/src/main/cpp/llamacpp/   <-- PrismML fork 的源码（含 llama.cpp / ggml 等）
app/src/main/cpp/llama-jni.cpp   <-- 我们写的 JNI 桥
```

> 若 PrismML 的 fork 把 ggml 拆成了多个 target（如 `ggml-base` / `ggml-cpu`），
> 打开 `app/CMakeLists.txt`，把 `target_link_libraries` 里的 `llama ggml` 改成 fork 实际产出的库名即可。
> 文件里已有注释提示。

### 4. 打开工程并编译
- Android Studio → `Open` → 选 `BonsaiAgent/` 目录。
- 等待 **Gradle Sync** 完成（首次会下载依赖，需联网）。
- 连上手机（开发者模式 + USB 调试），或建一个 **Pixel 模拟器（arm64）**。
- 点 ▶ Run，或 `Build → Build Bundle(s) / APK(s) → Build APK(s)`。
- 产物在 `app/build/outputs/apk/debug/app-debug.apk`。

### 5. 安装到手机
- 直接 Run 会自动装；或命令行：`adb install app/build/outputs/apk/debug/app-debug.apk`。
- 手机需 **Android 8.0（API 26）及以上**。

---

## 三、首次使用

1. 打开 App，进 **设置** 页。
2. 点「导入本地模型」，从手机存储选你的 `Ternary-Bonsai-8B-Q2_0.gguf`（建议先放 Download 目录）。
   - 用系统文件选择器（SAF），**不需要** 授权存储权限。
   - 模型会被复制到 App 私有目录（llama.cpp 需要真实文件路径）。
3. 点「加载模型」，等状态变成「模型就绪 ✅」（8B 模型加载要十几秒到几十秒）。
4. 去「对话」页聊天；去「路由 / Agent」页体验 `[CALL_CLOUD]` / `[EXEC:]` 自动路由。
5. 云端路由：在「设置」填 DeepSeek Key（`apiBase` 默认 `https://api.deepseek.com`）。
6. 本机 API：开「开启本机 HTTP API」开关后，手机内其他程序可访问 `http://127.0.0.1:11434/api/generate`。

---

## 四、JNI / C API 适配说明

`app/src/main/cpp/llama-jni.cpp` 按 **现代 llama.cpp C API** 编写，函数名与 PrismML 的 `prism` 分支一致：
`llama_load_model_from_file` / `llama_new_context_with_model` / `llama_tokenize(model,…)` /
`llama_decode` / `llama_get_logits_ith` / `llama_token_to_piece` / `llama_token_eos`。

若 PrismML 的 `prism` 分支用的是更老或细化版本，仅需改 **【API 适配点】** 注释处：
- tokenize 的首参数（model 还是 ctx）；
- 取 logits 的函数（`llama_get_logits_ith` 还是 `llama_get_logits`）；
- 上下文维护（batch API 是否需要 `llama_kv_cache` 相关）。
JNI 的 Java 签名**不变**，改完重编即可。

采样（temperature / top-k / top-p / 重复惩罚）在 JNI 里**自己实现**，不依赖 llama 版本差异，最大化兼容。

---

## 五、已知限制 / 注意

- **只编了 arm64（`arm64-v8a`）**，x86 模拟器跑不了，请用真机或 arm64 模拟器。
- 8B 模型 + 手机 CPU 推理较慢（约 1–3 tok/s），这是硬件上限；线程数调高可略快。
- 命令行执行有安全黑名单拦截（`rm -rf /`、`mkfs`、`dd if=` 等），避免模型被诱导搞破坏。
- DeepSeek 联网需 `INTERNET` 权限（已声明），且手机要能访问外网。

---

## 六、目录结构

```
BonsaiAgent/
├── settings.gradle / build.gradle / gradle.properties   # 工程配置
└── app/
    ├── build.gradle / CMakeLists.txt / proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml
        ├── cpp/llama-jni.cpp                 # JNI 桥（我们的）
        ├── cpp/llamacpp/                      # PrismML fork（你 clone 进来）
        ├── res/values/                        # 主题/字符串
        └── java/com/example/bonsaiagent/
            ├── MainActivity.kt                # 入口 + 三页 Tab
            ├── MainViewModel.kt               # 状态中枢
            ├── llm/Llama.kt                   # JNI Kotlin 封装
            ├── llm/ModelLoader.kt             # .gguf 导入
            ├── data/SettingsStore.kt          # 参数持久化
            ├── agent/Router.kt                # [CALL_CLOUD]/[EXEC:] 解析
            ├── agent/Executor.kt              # shell 执行 + 安全墙
            ├── agent/CloudClient.kt           # DeepSeek 调用
            ├── api/LocalApiServer.kt          # 本机 HTTP API
            └── ui/                            # Compose 三页面 + 主题
```
