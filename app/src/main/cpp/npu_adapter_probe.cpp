// ============================================================================
// npu_adapter_bridge.cpp  →  编译成 libneuron_adapter.so
//
// 背景（2026-11-10 终于找到真相）：
//   设备的 NPU 入口库并没有被裁掉，它们在 **/system_ext/lib64/**：
//       libneuronusdk_adapter.mtk.so        13.6 MB
//       libneuronusdk_adapter.9.mtk.so      14.1 MB
//       libneuronservice_adapter.mtk.so      250 KB
//       libneuron_sys_util.mtk.so             52 KB
//
//   问题只在于 LiteRT 用「裸库名」去 dlopen，能不能命中取决于 namespace 搜索路径，
//   而这一层我们控制不了。
//
// 做法：
//   占住 LiteRT 查找的第一顺位（nativeLibraryDir/libneuron_adapter.so），
//   在构造函数里用 **绝对路径** 把真 adapter 拉进来，然后把 43 个函数原样转发过去。
//
// 转发为什么安全：
//   AArch64 上整型/指针参数全部走 x0~x7，我们把收到的 8 个寄存器值原样再传下去，
//   等价于直接调用。NeuronAdapter API 里没有按值传递的结构体，参数最多 6 个。
//   返回值统一用 int64_t 承接，调用方按 int 读 w0，高位不影响。
// ============================================================================
#include <android/log.h>
#include <dlfcn.h>
#include <cstdint>
#include <cstdio>
#include <cstring>

#define TAG "NeuronAdapterBridge"

static const char* kLogPath = "/data/data/com.example.bonsailite/files/npu_bridge.log";
static void* g_real = nullptr;

static void plog(const char* s) {
    __android_log_print(ANDROID_LOG_INFO, TAG, "%s", s);
    FILE* f = fopen(kLogPath, "a");
    if (f) {
        fprintf(f, "%s\n", s);
        fclose(f);
    }
}

#include <sys/stat.h>

static const char* kPrivDir = "/data/data/com.example.bonsailite/files/npulibs";

// 把系统库复制到私有目录 —— 私有目录不受 namespace "clns-9" 限制（已验证）
static bool copy_to_priv(const char* name, char* outPath, size_t outLen) {
    char src[512], dst[512];
    snprintf(src, sizeof(src), "/system_ext/lib64/%s", name);
    snprintf(dst, sizeof(dst), "%s/%s", kPrivDir, name);
    FILE* in = fopen(src, "rb");
    if (!in) return false;
    FILE* out = fopen(dst, "wb");
    if (!out) { fclose(in); return false; }
    char buf[64 * 1024];
    size_t n;
    while ((n = fread(buf, 1, sizeof(buf), in)) > 0) fwrite(buf, 1, n, out);
    fclose(in);
    fclose(out);
    snprintf(outPath, outLen, "%s", dst);
    return true;
}

__attribute__((constructor)) static void bridge_init() {
    mkdir(kPrivDir, 0700);
    // 先把辅助库以 RTLD_GLOBAL 拉进来，真 adapter 可能按裸名依赖它们
    char buf[512];

    // ① 能直接从 /system_ext 加载的，直接加载
    {
        const char* ok[] = {"/system_ext/lib64/libneuron_sys_util.mtk.so"};
        for (const char* p : ok) {
            void* h = dlopen(p, RTLD_NOW | RTLD_GLOBAL);
            snprintf(buf, sizeof(buf), "预载 %s -> %s", p, h ? "OK" : dlerror());
            plog(buf);
        }
    }

    // ② 被 namespace 拦的，先复制到私有目录再加载。
    //    关键：加载同名 SONAME 后，后续任何对裸库名的 dlopen 都会命中已加载的这份，
    //    不会再回 /system_ext 去撞 namespace。
    {
        const char* needed[] = {
            "libneuron_graph_delegate.mtk.so",
            "libnir_neon_driver_ndk.mtk.so",
        };
        for (const char* name : needed) {
            char priv[512];
            if (!copy_to_priv(name, priv, sizeof(priv))) {
                snprintf(buf, sizeof(buf), "  复制失败 %s（系统里没有或读不到）", name);
                plog(buf);
                continue;
            }
            void* h = dlopen(priv, RTLD_NOW | RTLD_GLOBAL);
            snprintf(buf, sizeof(buf), "复制+预载 %s -> %s", name, h ? priv : dlerror());
            plog(buf);
            // 再试一次裸名，看是否命中已有 SONAME
            void* h2 = dlopen(name, RTLD_NOW | RTLD_GLOBAL);
            snprintf(buf, sizeof(buf), "   裸名复测 %s -> %s", name, h2 ? "OK" : "仍失败");
            plog(buf);
        }
    }

    const char* paths[] = {
        "/system_ext/lib64/libneuronusdk_adapter.mtk.so",
        "/system_ext/lib64/libneuronusdk_adapter.9.mtk.so",
        "/system_ext/lib64/libneuronservice_adapter.mtk.so",
        "/system_ext/lib64/libneuron_adapter.so",
        "/system_ext/lib64/libneuron_adapter_mgvi.so",
    };
    for (const char* p : paths) {
        g_real = dlopen(p, RTLD_NOW | RTLD_GLOBAL);
        if (g_real) {
            snprintf(buf, sizeof(buf), "★ 真实 adapter 已加载: %s", p);
            plog(buf);
            return;
        }
        snprintf(buf, sizeof(buf), "  失败 %s : %s", p, dlerror());
        plog(buf);
    }
    plog("❌ 所有路径均加载失败，转发层将全部返回错误");
}

// ---------------------------------------------------------------------------
// 万能转发：8 个 64 位参数，原样透传
// ---------------------------------------------------------------------------
using Fn8 = int64_t (*)(uint64_t, uint64_t, uint64_t, uint64_t,
                        uint64_t, uint64_t, uint64_t, uint64_t);

#define FWD(name)                                                              \
    extern "C" int64_t name(uint64_t a, uint64_t b, uint64_t c, uint64_t d,     \
                            uint64_t e, uint64_t f, uint64_t g, uint64_t h) {   \
        static Fn8 fn = nullptr;                                               \
        static bool tried = false;                                             \
        if (!tried) {                                                          \
            tried = true;                                                      \
            fn = g_real ? (Fn8) dlsym(g_real, #name) : nullptr;                 \
            if (!fn) plog("缺符号: " #name);                                    \
        }                                                                      \
        if (!fn) return -1;                                                    \
        int64_t r = fn(a, b, c, d, e, f, g, h);                                \
        static int cnt = 0;                                                    \
        if (cnt < 400) {                                                       \
            cnt++;                                                             \
            char b[160];                                                       \
            snprintf(b, sizeof(b), "%s -> %lld", #name, (long long) r);        \
            plog(b);                                                           \
        }                                                                      \
        return r;                                                              \
    }

extern "C" {

// ---- 版本 / 平台 ----
FWD(Neuron_getVersion)
FWD(Neuron_getL1MemorySizeKb)
FWD(NeuronService_getNeuroPilotMagicNumber)

// ---- Model ----
FWD(NeuronModel_create)
FWD(NeuronModel_free)
FWD(NeuronModel_addOperand)
FWD(NeuronModel_setOperandValue)
FWD(NeuronModel_addOperation)
FWD(NeuronModel_identifyInputsAndOutputs)
FWD(NeuronModel_setName)
FWD(NeuronModel_getExtensionOperandType)
FWD(NeuronModel_getExtensionOperationType)
FWD(NeuronModel_setOperandSymmPerChannelQuantParams)
FWD(NeuronModel_setOperandPerChannelQuantParams)
FWD(NeuronModel_relaxComputationFloat32toFloat16)
FWD(NeuronModel_restoreFromCompiledNetwork)
FWD(NeuronModel_restoreFromCompiledNetworkV3)
FWD(NeuronModel_finish)

// ---- Compilation ----
FWD(NeuronCompilation_create)
FWD(NeuronCompilation_createWithOptions)
FWD(NeuronCompilation_finish)
FWD(NeuronCompilation_free)
FWD(NeuronCompilation_getCompiledNetworkSize)
FWD(NeuronCompilation_storeCompiledNetwork)
FWD(NeuronCompilation_getInputPaddedSize)
FWD(NeuronCompilation_getOutputPaddedSize)
FWD(NeuronCompilation_getInputPaddedDimensions)
FWD(NeuronCompilation_getOutputPaddedDimensions)
FWD(NeuronCompilation_getSupportedOperations)
FWD(NeuronCompilation_setOptimizationString)
FWD(NeuronCompilation_setOptimizationHint)
FWD(NeuronCompilation_setPreference)
FWD(NeuronCompilation_setPriority)
FWD(NeuronCompilation_setL1MemorySizeKb)

// ---- Execution ----
FWD(NeuronExecution_create)
FWD(NeuronExecution_free)
FWD(NeuronExecution_setInputFromMemory)
FWD(NeuronExecution_setOutputFromMemory)
FWD(NeuronExecution_setConfig)
FWD(NeuronExecution_setBoostHint)
FWD(NeuronExecution_compute)

// ---- Memory ----
FWD(NeuronMemory_createFromFd)
FWD(NeuronMemory_createFromAHardwareBuffer)
FWD(NeuronMemory_free)

}  // extern "C"
