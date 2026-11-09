# BonsaiAgent 技术档案（2026-11-08）

## 一、当前最优配置

| 项 | 值 |
|---|---|
| 模型 | **Qwen3-4B-Q4_K_M.gguf**（2.33 GB） |
| 线程 | **4**（4×Cortex-X4） |
| 生成速度 | **10.1 tok/s** |
| prefill | **3.8 秒** |
| 版本 | v10.19 |

模型来源：https://hf-mirror.com/Qwen/Qwen3-4B-GGUF/resolve/main/Qwen3-4B-Q4_K_M.gguf

### 线程数实测对比（Qwen3-4B Q4_K_M）

| 线程 | prefill | 生成 |
|---|---|---|
| **4** | **3.8 秒** | **10.1 tok/s** |
| 6 | ~4 秒 | ~9.5 |
| 8 | 3.5 秒 | 7.7 |

结论：4 线程最优，8 线程生成掉 24%。

---

## 二、关键结论（都是实测出来的）

### 1. 三值模型在 ARM 上是"不受支持的后娘养的"

- llama.cpp 的 `arch/arm/repack.cpp` 里，Q1_0 有 i8mm GEMM，**Q2_0（三值）完全没有，只有朴素 vec_dot**
- PrismML 论文自己承认："the corresponding 2-bit kernels in llama.cpp are far from optimal"
- PrismML 的 tiled/TPP 引擎**只实现了 x86（AVX2）**，`tiled-kernel.h` 第一行注释写明 "Currently only optimized for x86"
- 论文的 30.8 tok/s 是 x86 平台 TPP 的成绩，与 ARM 无关

**因此 8B 三值的 6.4 tok/s 已经是 ARM 上的正常水平，不是配置问题。**

### 2. 换 Q4_K_M 才是正解

| | 8B 三值 | Qwen3-4B Q4_K_M |
|---|---|---|
| 大小 | 2.03 GB | 2.33 GB |
| kernel | 朴素 vec_dot | repack + i8mm |
| 生成 | 6.4 tok/s | **10.1 tok/s** |
| prefill | 8 秒 | **3.8 秒** |

**提升 58%，模型还更小。** 三值模型的"智能密度"优势被 kernel 缺失完全抵消。

### 3. 已证伪的方案（不要再试）

| 方案 | 实测结果 |
|---|---|
| 绑核到 X4 大核 | 46 线程挤 4 核 → 1.74 tok/s，负优化 |
| 8 线程 | 生成掉到 7.7 |
| Vulkan（Mali GPU） | 社区实测 prefill 反而慢 6 倍；Mali 有 open 的正确性 bug（输出乱码）；不支持 Q2_0 |
| 关 mmap | 新版 llama.cpp 已删除该字段，改不了 |
| TL1/tiled 内核移植 | 只有 x86 实现，移植等于重写库 |
| 投机解码（dspark） | drafter 只发布了 27B 版，8B 没有 |
| NPU + 三值模型 | APU 最低支持 INT4，三值填成 INT4 体积翻倍 |

### 4. 已修的关键 bug

| bug | 根因 |
|---|---|
| 动作不执行 | v10.4 批量替换误伤的：Router 解析的是界面内容而非模型原文 |
| 对话页吞回答 | 同上，v10.4 把对话页的流式显示也改了 |
| 线程数设置无效 | `coerceIn(1, 6)`，8 核永远跑 6 线程 |
| KV 续接只复用 17 token | 时间戳前缀临时拼接没存进消息，历史每轮分叉 |
| prefill 40 秒 | 提示词 300 token，压回 60 token |

---

## 三、还没做完的

### A. KV 续接（v10.16 的修复未验证）

- 改了 `promptText` 字段让前缀跟着消息走
- 但有 bug：`chatMessages.lastOrNull { it.proMPText.isEmpty() }` 在每轮新消息上都会设置，可能重复
- **需要验证：第二句是否出现 "KV 续接：复用 N token" 且 N 很大**

### B. NPU —— 已结案：厂商把关键组件裁掉了

**一句话结论：天玑 9300+（MT6989）的 NPU，第三方 App 碰不到。**
不是技术不成立，是 vivo 把系统里的 NeuronAdapter（NPU 的编译+执行入口）删成了一堆 0 字节符号链接，只留下运行时。

#### 完整链路与每一环的状态

```
.litertlm 模型（内含 NPU bytecode）
   ↓
libLiteRtDispatch_MediaTek.so       ✅ Google 官方 AAR 自带，已打进 APK
   ↓
libneuron_adapter.so                ❌ 系统里被裁 → 我们自己造了一个（能接住调用，但没有编译能力）
   ↓
libneuron_runtime.7.so              ✅ 系统里有，可读、可 dlopen
   ↓
NPU 硬件                            ❌ /dev 下没有任何 apu/npu/mdla 节点
```

#### 验证过程（每一步都有日志）

| 日志 | 验证了什么 |
|---|---|
| 012 | `/vendor/etc/public.libraries.txt` 只有 8 行，神经相关 3 条，**没有 neuron**；`/system/etc/public.libraries.txt` 31 行，0 条 |
| 013 | **绕过 namespace 可行**：直连 `/vendor/lib64/x.so` 报 `not accessible for namespace "clns-9"`；先复制到 App 私有目录再 dlopen → **成功** |
| 013/015 | 递归扫 `/vendor/lib64` 两层，命中 24~27 个 neuron 相关文件，**真文件只有 7 个** |
| 015 | 自己写 ELF 解析器 dump `libneuron_runtime.7.so` 的 .dynsym → **98 个 C 函数全部在** |
| 018 | **自造 adapter 被 LiteRT 加载，调用 190 次** |

**真文件只有这 7 个**（其余全是 len=0 的坏符号链接：`libneuron_adapter_mgvi.so`、`libneuron_wrapper.so`、`libneuron_runtime.so`、`libnir_neon_driver.so`、`vendor.mediatek.hardware.neuropilot.agent*.so`）：

```
libneuron_runtime.7.so            1,810,864   ← 唯一有用的
libneuron_platform.so               194,488   （缺 libbase.so）
libapu_mdw.so                       141,432
libapu_mdw_batch.so                  10,440
libarmnn_ndk.mtk.vndk.so         12,873,264
libnir_neon_driver_ndk.mtk.vndk.so 8,822,008
```

#### 重大发现：Google 官方提供了完整的 MTK NPU 运行时

```
Maven: com.google.ai.edge.litert:litert-npu-runtime-mediatek:2.3.2
URL:   https://dl.google.com/dl/android/maven2/com/google/ai/edge/litert/
       litert-npu-runtime-mediatek/2.3.2/litert-npu-runtime-mediatek-2.3.2.aar
内含:  jni/arm64-v8a/libLiteRtDispatch_MediaTek.so        330,968 字节
       jni/arm64-v8a/libLiteRtCompilerPlugin_MediaTek.so  429,120 字节
```

已加进 `app/build.gradle`，从 v10.28 起每个 APK 都带。

#### 源码级确认：LiteRT 的 adapter 查找顺序

文件：`litert/vendors/mediatek/neuron_adapter_api.cc`（google-ai-edge/LiteRT）

```cpp
so_paths.push_back(nativeLibraryDir + "/libneuron_adapter.so");  // ← 第一顺位，APK 自己的 lib 目录
so_paths.push_back("libneuronusdk_adapter.so");
so_paths.push_back("libneuronusdk_adapter.10.mtk.so");
so_paths.push_back("libneuronusdk_adapter.9.mtk.so");
so_paths.push_back("libneuronusdk_adapter.8.mtk.so");
so_paths.push_back("libneuronusdk_adapter.mtk.so");
so_paths.push_back("libneuron_adapter_mgvi.so");
```

**所以自造 adapter 放进 APK 就能被优先加载**，日志 018 已实证。
另有：`LOAD_SYMB` 缺失不致命，只打印 WARNING 并把指针置 null。

#### runtime 保留的 98 个 C 函数（全部无 C++ mangling）

```
NeuronRuntime_create / release / inferenceEnqueue / setOffsetedInput
NeuronRuntime_setQoSOption / setSingleOutput / getInputNumber / getOutputNumber
NeuronRuntime_getInputRank / getOutputRank / getInputPaddedSize / getVersion
NeuronRuntimeV2_createFromBuffer_with_options / run / setInputShape / setQoS
NeuronRuntimeV2_getInputSize / getOutputSize / getMetadata / getMetadataInfo
NeuronDLAMuxer_createFromBuffer / createWithOptions / inference / inferenceTrigger
NeuronDLAMuxer_getDLACount / setInput / setQoS / getInputSize / getOutputPaddedSize
```

#### 最后一环：编译能力在 adapter 内部，拿不到

日志 018 的 adapter 调用轨迹（190 次）：

```
NeuronModel_addOperand(...) total=1 → 164
NeuronModel_addOperation(type=86 in=3 out=1)   ← type 86/36/2/22
NeuronModel_identifyIO(in=76 out=73)
NeuronModel_setOperandValue(idx=149 len=16)
```

说明 **LiteRT 不是"读现成字节码"，而是把 NPU 那部分算子重新搭成一张 NNAPI 风格的图（164 个张量、76 进 73 出），再要 adapter 编译它。**

而 `libLiteRtCompilerPlugin_MediaTek.so` 经 readelf + strings 验证，**它也只是个"传话的"**：

```
它 dlopen 的库：libneuron_adapter.so / libneuronusdk_adapter.*.so / libneuron_sys_util.mtk.so
它调用的函数：NeuronCompilation_create / createWithOptions / finish / storeCompiledNetwork
```

**真正编译在 adapter 里**。而 runtime 那 98 个符号里，一个 Compilation 系列都没有。系统还缺 `libneuron_sys_util.mtk.so`。

#### MTK 的三级授权（决定了为什么拿不到）

| 级别 | 谁能拿 | 有什么 |
|---|---|---|
| **Public** | 任何人，免费 | 只有 NP Converter + TFLite Shim API，**没有运行时库** |
| **Basic** | 企业开发者 + NDA | 完整（含 adapter + 编译器） |
| **Premium** | OEM | 完整 + 高级 |

我们有的（runtime）来自设备，缺的（adapter/编译器）在 Basic 级。

#### 如果以后还要继续，需要什么

**路径 A：设备端 JIT —— 需要真正的 `libneuron_adapter.so`（约 200 KB）**

- 来源 1：NeuroPilot SDK（Basic 级，需企业资质 + NDA）
- 来源 2：**从别的天玑 9300 机型固件里扒**（vivo X100 / iQOO Neo9 Pro / OPPO Find X7 / Redmi K70 至尊版），进 `vendor/lib64/` 找 `libneuron_adapter*.so` 或 `libneuronusdk_adapter*.mtk.so`
- 拿到后直接放进 `app/src/main/jniLibs/arm64-v8a/`，**代码不用改**（日志 018 已证明这条链路能跑通）

**路径 B：PC 端 AOT —— 需要 `ncc-tflite`（Neuron Compiler）**

- 来源：NeuroPilot SDK Public 免费版
- 用法：PC 上把 tflite 编译成 `.dlp`，手机上用 `NeuronRuntime_loadNetworkFromBuffer` 直接加载，绕开 LiteRT 和 adapter
- 代价：**必须有 Linux PC**；Qwen3-4B 能否编译成 dlp 完全未知（公开案例全是 CV 模型）

#### 副作用警告

用 LiteRT 加载 `qwen3_4b_mixed_int4.litertlm`（2167 MB）时实测：

```
加载前：系统可用 4621MB，本进程 294MB
加载后：系统可用 1158MB，本进程 774MB
生成中：本进程涨到 2919MB，生成 36 字用了 52 秒
```

**手机会发烫、会卡、可能闪退 —— 这不是 bug，是 LiteRT 自己的重。日常不要走这条路。**

## 四、代码位置

仓库：`/sandbox/workspace/BonsaiAgent`（GitHub: 000510/BonsaiAgent，**目前是 public**）

关键文件：
- `app/src/main/cpp/llama-jni.cpp` — JNI 层（KV 续接、线程设置、采样、dlopen 探针 `dlopenProbe`）
- `app/src/main/cpp/npu_adapter_probe.cpp` — **自造 NeuronAdapter**（43 个符号全导出，产物名 libneuron_adapter.so）
- `app/src/main/java/com/example/bonsaiagent/llm/ElfDump.kt` — ELF .dynsym 解析（用来 dump runtime 的导出函数）
- `app/src/main/java/com/example/bonsaiagent/llm/LiteRtEngine.kt` — LiteRT-LM 封装（NPU 后端用 `Backend.NPU(nativeLibraryDir)`）
- `app/src/main/java/com/example/bonsaiagent/MainViewModel.kt` — 推理调度、历史管理、快路径
  - `probeNpu()` / `probeNpuDeep()` / `probeNpuRun()` — 三级 NPU 探测（最后一个是"一键自检"，逐步落盘）
- `app/src/main/java/com/example/bonsaiagent/agent/` — 动作、路由、应用索引
- `app/src/main/java/com/example/bonsaiagent/data/SettingsStore.kt` — 设置与提示词

构建要点：
- `app/CMakeLists.txt` 里 `neuron_adapter` target 的 **OUTPUT_NAME 必须是 `neuron_adapter`**，
  产物才会是 `libneuron_adapter.so`，正好命中 LiteRT 的第一顺位查找路径。
- NPU 依赖：`com.google.ai.edge.litert:litert-npu-runtime-mediatek:2.3.2`（Google Maven，`dl.google.com` 可达）

CI：GitHub Actions，push 到 main 自动编译并发布到 Release。
构建命令在 `.github/workflows/build.yml`（手动装 Gradle，绕开 GitHub cache 504）。

---

## 五、教训

1. **改 UI 显示逻辑时，别顺手把数据源也改了**（v10.4 的血泪）
2. **`str.replace` 批量替换会命中所有位置**，改一处要确认影响范围
3. **先看日志里的实际数值，再下结论** —— 我多次用现象反推原因，全错
4. **社区数据要核对设备和量化格式** —— Mali 的 Vulkan 数据在 Q8_0 和 Q4 上结论完全相反
5. **"厂商支持"≠"你能用"** —— MT6989 在 LiteRT 官方支持列表里，但设备上的入口库被 OEM 裁掉了。支持列表只说明"理论上可以"，要看系统里实际剩什么
6. **排障日志必须逐步落盘** —— 攒到最后一次性写，一崩溃就全丢，等于睁眼瞎（v10.31→v10.32 的教训）
7. **工程结论要给证据，不要给推测** —— 这轮能收尾，靠的是 readelf/strings/dynsym 这些硬证据，不是"我猜它缺编译器"
