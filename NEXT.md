# 明天要办的事（2026-11-10）

## 当前状态：可用、稳定

- 主力：**llama.cpp + Qwen3-4B Q4_K_M，11.2 tok/s**，不发烫、不卡
- 手机上那个 2.17 GB 的 `qwen3_4b_mixed_int4.litertlm` **可以删**（LiteRT 路线已结案）
- 最新版：**v10.32**（含 NPU 全套探针，日常不用它们）

---

## 唯一的目标：拿到真正的 `libneuron_adapter.so`

**它约 200 KB，是 NPU 的"编译 + 执行入口"。系统里被 vivo 裁成了 0 字节符号链接。**

拿到之后 **一行代码都不用改** —— 直接放进 `app/src/main/jniLibs/arm64-v8a/` 就能生效
（日志 018 已经证明：LiteRT 会优先从这个目录加载它，并且已经成功调用了我造的假版本 190 次）。

### 找什么（按优先级）

| 文件名 | 说明 |
|---|---|
| `libneuron_adapter.so` | 标准版 |
| `libneuronusdk_adapter.mtk.so` | USDK 变体（LiteRT 第二顺位） |
| `libneuronusdk_adapter.10.mtk.so` | 新版本变体 |
| `libneuron_adapter_mgvi.so` | 系统预装变体（**我们设备上这个是空的，别拿这个**） |
| `libneuron_sys_util.mtk.so` | 配套辅助库，也缺，一起找 |

### 去哪找

**① 同芯片机型固件（最有希望）**

MT6989 = 天玑 9300 / 9300+：

```
vivo X100 / X100 Pro
iQOO Neo9 Pro
OPPO Find X7
Redmi K70 至尊版
```

做法：下官方固件包（不是 OTA 增量包，要完整包），解开后在 `vendor/lib64/` 或
`vendor/lib64/mt6989/` 里搜 `neuron`。

**② NeuroPilot SDK Public 免费版**

- 门户：`neuropilot.mediatek.com` / `genio.mediatek.com`
- 需要注册账号（可能过人机验证 —— 你之前卡在这）
- Public 级不含运行时库，但可能带 converter 和示例里附 adapter

**③ 搜索关键词**

```
libneuronusdk_adapter.mtk.so
libneuron_adapter.so MT6989
neuropilot sdk basic download
```

**④ 只要拿到 `.dlp` 文件也行**

`.dlp` 是编译好的 NPU 模型。有它就能直接测 `NeuronRuntime_loadNetworkFromBuffer`，
跳过 adapter 这一整条链。但**编译器 `ncc-tflite` 在 PC 上**，手机端没有。

---

## 拿到之后怎么做

```bash
mkdir -p app/src/main/jniLibs/arm64-v8a
cp libneuron_adapter.so app/src/main/jniLibs/arm64-v8a/
```

然后编译一版，装上去点「⚡ 一键 NPU 自检」。

**看日志 019 的最后几行：**

```
adapter 被调用 N 次
  NeuronCompilation_createWithOptions(...)
  NeuronCompilation_finish(...)          ← 如果这行返回成功，说明编译过了
  NeuronCompilation_storeCompiledNetwork(...)
```

**关键判断**：
- `finish` 之后出现 `NeuronExecution_compute` 且**没报错** → **NPU 真的跑起来了**
- `finish` 报错 → 版本不匹配（adapter 和 runtime 必须是同一代，设备是 Neuron 7）

---

## 版本必须对得上

设备上的运行时是 **`libneuron_runtime.7.so`**（名字里的 7 = NeuroPilot 7）。
**所以 adapter 也要 7 那一代的**，太新太旧都会在 `Neuron_getVersion` 之后的初始化里失败。

怎么判断版本：看 `libneuron_adapter*.so` 里有没有 `NeuronRuntimeV2_*`、`NeuronDLAMuxer_*` 这些符号
（设备 runtime 有，所以 adapter 也该有）。

---

## 如果彻底找不到

**就认了。** 这条路上限到此为止，原因写在 `DOSSIER.md` 第三节 B：

> MTK 把编译能力放在 Basic 级授权里（企业资质 + NDA），
> Public 免费版只有转换工具，没有运行时和 adapter。

---

## 顺带可以做的（优先级低）

- `DOSSIER.md` 第三节 A：验证 KV 续接是否真的生效（第二轮是否出现"复用 N token"且 N 很大）
- 如果哪天嫌 11.2 tok/s 还不够：Qwen3-4B 换 Q4_0 + repack 也许能再快 5~10%，但收益有限
