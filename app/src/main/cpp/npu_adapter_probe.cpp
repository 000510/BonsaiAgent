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
#include <unistd.h>

static const char* kPrivDir = "/data/data/com.example.bonsailite/files/npulibs";

// 把系统库复制到私有目录 —— 私有目录不受 namespace "clns-9" 限制（已验证）
static bool copy_to_priv(const char* name, char* outPath, size_t outLen) {
    char src[512], dst[512];
    snprintf(src, sizeof(src), "/system_ext/lib64/%s", name);
    if (access(src, R_OK) != 0) {
        snprintf(src, sizeof(src), "/vendor/lib64/%s", name);
    }
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

// ---------------------------------------------------------------------------
// v10.41 核心：自动补齐依赖链
//
// 事实：Android 只允许 App dlopen「NDK 公开」的系统库。
//   liblog.so   → OK
//   libutils.so / libcutils.so / libbase.so → not accessible
// 而 MTK 的编译组件（graph_delegate）恰恰依赖这些内部库。
//
// 但私有目录不受这个白名单限制（已验证 libneuron_runtime.7.so 私有副本能加载）。
// 所以做法：把库连同它缺的依赖一层层复制到私有目录，直到能加载为止。
// ---------------------------------------------------------------------------
static bool copyLibToPriv(const char* name, char* outPath, size_t outLen) {
    const char* dirs[] = {
        "/system/lib64", "/system_ext/lib64", "/vendor/lib64",
        "/apex/com.android.runtime/lib64", "/apex/com.android.art/lib64",
    };
    for (const char* d : dirs) {
        char src[512];
        snprintf(src, sizeof(src), "%s/%s", d, name);
        FILE* in = fopen(src, "rb");
        if (!in) continue;
        snprintf(outPath, outLen, "%s/%s", kPrivDir, name);
        FILE* o = fopen(outPath, "wb");
        if (!o) { fclose(in); return false; }
        char b[65536];
        size_t k;
        while ((k = fread(b, 1, sizeof(b), in)) > 0) fwrite(b, 1, k, o);
        fclose(in);
        fclose(o);
        return true;
    }
    return false;
}

// bionic 核心库绝不能自己复制一份加载：同一进程里出现两个链接器/两套 libc 必崩
static bool isCoreBionic(const char* n) {
    // 只排除真正的「进程级唯一」组件：链接器、libc、libm、libdl。
    // libc++.so 可以加载副本 —— 上次崩溃的元凶是 ld-android.so 的副本，不是它。
    static const char* core[] = {
        "ld-android.so", "libc.so", "libm.so", "libdl.so",
    };
    for (const char* c : core) {
        if (strcmp(n, c) == 0) return true;
    }
    return false;
}

static void* loadWithDeps(const char* name, int depth) {
    char buf[420];
    if (depth > 6) return nullptr;
    if (isCoreBionic(name)) {
        // 链接器 / libc / libm / libdl 本来就在进程里，
        // 私有一份副本会造成两套运行时，必崩。
        // 这里只尝试按名字拿系统那份；拿不到也返回非空句柄，
        // 因为它们确实已加载，只是没有常规 dlopen 句柄。
        void* h = dlopen(name, RTLD_NOW | RTLD_GLOBAL);
        static int shown = 0;
        if (shown < 3) {
            shown++;
            snprintf(buf, sizeof(buf), "  %s %s（核心库，已在进程内）", h ? "✓" : "≈", name);
            plog(buf);
        }
        return h ? h : (void*) 1;
    }

    // v10.44：先试系统裸名 —— libdl_android / libvndksupport 这类
    // 走系统 namespace 往往就能拿到，没必要复制副本。
    {
        void* h = dlopen(name, RTLD_NOW | RTLD_GLOBAL);
        if (h) {
            snprintf(buf, sizeof(buf), "  ✓ %s（系统自带）", name);
            plog(buf);
            return h;
        }
    }
    char priv[512];
    if (!copyLibToPriv(name, priv, sizeof(priv))) {
        snprintf(buf, sizeof(buf), "  ✗ %s 在系统里找不到源文件", name);
        plog(buf);
        return nullptr;
    }
    for (int i = 0; i < 12; i++) {
        void* h = dlopen(priv, RTLD_NOW | RTLD_GLOBAL);
        if (h) {
            snprintf(buf, sizeof(buf), "  ✓ %s 已加载", name);
            plog(buf);
            return h;
        }
        const char* e = dlerror();
        const char* q1 = strstr(e, "library \"");
        const char* q2 = q1 ? strstr(q1 + 9, "\" not found") : nullptr;
        if (!q1 || !q2 || (q2 - (q1 + 9)) > 120) {
            snprintf(buf, sizeof(buf), "  ✗ %s 加载失败: %s", name, e);
            plog(buf);
            return nullptr;
        }
        char miss[128];
        size_t len = (size_t)(q2 - (q1 + 9));
        memcpy(miss, q1 + 9, len);
        miss[len] = 0;
        snprintf(buf, sizeof(buf), "  ↓ %s 缺 %s，递归补", name, miss);
        plog(buf);
        if (!loadWithDeps(miss, depth + 1)) return nullptr;
    }
    return nullptr;
}

__attribute__((constructor)) static void bridge_init() {
    mkdir(kPrivDir, 0700);
    char buf[512];

    // 先把 MTK 编译组件连同依赖链准备好（能成就成，不成也不影响后面加载 adapter）
    loadWithDeps("libneuron_sys_util.mtk.so", 0);

    // v10.45：先试 HAL 代理版（250 KB）—— 它走 neuronservice 服务，
    // 不依赖 libutils 那一串被 NDK 白名单挡死的内部库。
    const char* paths[] = {
        "/system_ext/lib64/libneuronservice_adapter.mtk.so",
        "/system_ext/lib64/libneuronusdk_adapter.9.mtk.so",
        "/system_ext/lib64/libneuronusdk_adapter.mtk.so",
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
    plog("❌ 所有路径均加载失败");
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
// 单独实现：必须把 adapter 填进结构体的真实版本号记录下来，
// LiteRT 会拿它和 bytecode 的 major 版本比对，不一致就直接放弃。
extern "C" int64_t Neuron_getVersion(uint64_t a, uint64_t b, uint64_t c, uint64_t d,
                                     uint64_t e, uint64_t f, uint64_t g, uint64_t h) {
    static Fn8 fn = nullptr;
    if (!fn) fn = g_real ? (Fn8) dlsym(g_real, "Neuron_getVersion") : nullptr;
    if (!fn) { plog("缺符号: Neuron_getVersion"); return -1; }
    int64_t r = fn(a, b, c, d, e, f, g, h);
    if (a) {
        const unsigned char* v = (const unsigned char*) a;
        char buf[128];
        snprintf(buf, sizeof(buf), "Neuron_getVersion -> ret=%lld, 版本=%u.%u.%u",
                 (long long) r, v[0], v[1], v[2]);
        plog(buf);
    }
    return r;
}
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
extern "C" int64_t NeuronModel_finish(uint64_t a, uint64_t b, uint64_t c, uint64_t d,
                                     uint64_t e, uint64_t f, uint64_t g, uint64_t h) {
    static Fn8 fn = nullptr;
    if (!fn) fn = g_real ? (Fn8) dlsym(g_real, "NeuronModel_finish") : nullptr;
    if (!fn) { plog("缺符号: NeuronModel_finish"); return -1; }
    int64_t r = fn(a, b, c, d, e, f, g, h);
    char buf[128];
    snprintf(buf, sizeof(buf), "★ NeuronModel_finish -> %lld", (long long) r);
    plog(buf);
    return r;
}

// ---- Compilation ----
FWD(NeuronCompilation_create)
FWD(NeuronCompilation_createWithOptions)
extern "C" int64_t NeuronCompilation_finish(uint64_t a, uint64_t b, uint64_t c, uint64_t d,
                                           uint64_t e, uint64_t f, uint64_t g, uint64_t h) {
    static Fn8 fn = nullptr;
    if (!fn) fn = g_real ? (Fn8) dlsym(g_real, "NeuronCompilation_finish") : nullptr;
    if (!fn) { plog("缺符号: NeuronCompilation_finish"); return -1; }
    int64_t r = fn(a, b, c, d, e, f, g, h);
    char buf[128];
    snprintf(buf, sizeof(buf), "★ NeuronCompilation_finish -> %lld", (long long) r);
    plog(buf);
    return r;
}
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
