// ============================================================================
// npu_adapter_probe.cpp  →  编译成 libneuron_adapter.so
//
// 背景：vivo 把系统里的 NeuronAdapter（NPU 入口库）裁成了一批 0 字节的符号链接，
// 只剩 libneuron_runtime.7.so 这个真身能读、能 dlopen。
// 而 Google 官方的 LiteRT MTK dispatch 库只会按固定名字去 dlopen adapter：
//     <nativeLibraryDir>/libneuron_adapter.so   ← 我们自己提供，优先级最高
//     libneuronusdk_adapter.so / .9 / .10 / mgvi.so ...
//
// 所以我们自己造一个同名 .so 顶上。
//
// 这一版是【探针版】：43 个符号全部导出（少一个 LiteRT 只会 warning 并置 null，
// 但一旦被调用就是空指针崩溃），每个函数把调用顺序和关键参数写进
//     /data/data/com.example.bonsailite/files/npu_adapter.log
// 目的不是立刻跑通，而是先看清 LiteRT 到底按什么顺序、带什么参数调用它们。
//
// 已知签名参考 NNAPI（ANeuralNetworks.h），NeuronAdapter 是它的 MTK 对应物。
// ============================================================================
#include <android/log.h>
#include <cstdint>
#include <cstddef>
#include <cstdio>
#include <cstdarg>
#include <cstring>
#include <cstdlib>

#define TAG "NeuronAdapterProbe"
#define NEURON_NO_ERROR 0
#define NEURON_ERR 1

static const char* kLogPath = "/data/data/com.example.bonsailite/files/npu_adapter.log";

static void plog(const char* fmt, ...) {
    char buf[512];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buf, sizeof(buf), fmt, ap);
    va_end(ap);
    __android_log_print(ANDROID_LOG_INFO, TAG, "%s", buf);
    FILE* f = fopen(kLogPath, "a");
    if (f) { fprintf(f, "%s\n", buf); fclose(f); }
}

// ---- 我们自己造的假对象（只为了在调用之间传递上下文）----------------------
struct FakeModel       { int magic; const void* buf; size_t size; int32_t n_ops; };
struct FakeCompilation { int magic; FakeModel* m; };
struct FakeExecution   { int magic; FakeCompilation* c; };
struct FakeMemory      { int magic; void* ptr; size_t size; };

#define MAGIC_MODEL 0x4D4F444C
#define MAGIC_COMP  0x434F4D50
#define MAGIC_EXEC  0x45584543
#define MAGIC_MEM   0x4D454D4F

typedef struct { uint8_t major, minor, patch; } NeuronVersion;

extern "C" {

// ---------------------------------------------------------------------------
// 版本 / 平台信息
// ---------------------------------------------------------------------------
int Neuron_getVersion(NeuronVersion* v) {
    if (v) { v->major = 9; v->minor = 0; v->patch = 0; }
    plog("Neuron_getVersion -> 9.0.0");
    return NEURON_NO_ERROR;
}

int Neuron_getL1MemorySizeKb(void* out) {
    if (out) *(uint32_t*) out = 512;
    plog("Neuron_getL1MemorySizeKb -> 512");
    return NEURON_NO_ERROR;
}

int NeuronService_getNeuroPilotMagicNumber(void* out) {
    plog("NeuronService_getNeuroPilotMagicNumber -> 0 (unsupported)");
    return NEURON_ERR;
}

// ---------------------------------------------------------------------------
// NeuronModel —— 模型容器
// ---------------------------------------------------------------------------
int NeuronModel_create(FakeModel** out) {
    FakeModel* m = (FakeModel*) calloc(1, sizeof(FakeModel));
    m->magic = MAGIC_MODEL;
    *out = m;
    plog("NeuronModel_create -> %p", (void*) m);
    return NEURON_NO_ERROR;
}

void NeuronModel_free(FakeModel* m) {
    plog("NeuronModel_free(%p)", (void*) m);
    free(m);
}

int NeuronModel_addOperand(FakeModel* m, const void* type) {
    if (m) m->n_ops++;
    plog("NeuronModel_addOperand(model=%p type=%p) total=%d", (void*) m, type,
         m ? m->n_ops : -1);
    return NEURON_NO_ERROR;
}

int NeuronModel_setOperandValue(FakeModel* m, int32_t idx, const void* data, uint32_t len) {
    plog("NeuronModel_setOperandValue(idx=%d data=%p len=%u)", idx, data, len);
    return NEURON_NO_ERROR;
}

int NeuronModel_addOperation(FakeModel* m, int32_t type, uint32_t inCount,
                             const uint32_t* ins, uint32_t outCount, const uint32_t* outs) {
    plog("NeuronModel_addOperation(type=%d in=%u out=%u)", type, inCount, outCount);
    return NEURON_NO_ERROR;
}

int NeuronModel_identifyInputsAndOutputs(FakeModel* m, uint32_t inCount,
                                         const uint32_t* ins, uint32_t outCount,
                                         const uint32_t* outs) {
    plog("NeuronModel_identifyIO(in=%u out=%u)", inCount, outCount);
    return NEURON_NO_ERROR;
}

int NeuronModel_setName(FakeModel* m, const char* name) {
    plog("NeuronModel_setName(%s)", name ? name : "(null)");
    return NEURON_NO_ERROR;
}

int NeuronModel_getExtensionOperandType(FakeModel* m, const char* name, int32_t* type) {
    plog("NeuronModel_getExtensionOperandType(%s)", name ? name : "(null)");
    return NEURON_ERR;
}

int NeuronModel_getExtensionOperationType(FakeModel* m, const char* name, int32_t* type) {
    plog("NeuronModel_getExtensionOperationType(%s)", name ? name : "(null)");
    return NEURON_ERR;
}

int NeuronModel_setOperandSymmPerChannelQuantParams(FakeModel* m, int32_t idx, const void* p) {
    plog("NeuronModel_setOperandSymmPerChannelQuantParams(idx=%d)", idx);
    return NEURON_NO_ERROR;
}

int NeuronModel_setOperandPerChannelQuantParams(FakeModel* m, int32_t idx, const void* p) {
    plog("NeuronModel_setOperandPerChannelQuantParams(idx=%d)", idx);
    return NEURON_NO_ERROR;
}

int NeuronModel_relaxComputationFloat32toFloat16(FakeModel* m, bool allow) {
    plog("NeuronModel_relaxFp32ToFp16(%d)", (int) allow);
    return NEURON_NO_ERROR;
}

// ★ 关键：模型里已经带好了 NPU bytecode，LiteRT 会用这个把字节码塞进来
int NeuronModel_restoreFromCompiledNetwork(FakeModel* m, const void* buffer, size_t size) {
    plog("★ NeuronModel_restoreFromCompiledNetwork(model=%p buffer=%p size=%zu)",
         (void*) m, buffer, size);
    if (m) { m->buf = buffer; m->size = size; }
    if (buffer && size) {
        const unsigned char* p = (const unsigned char*) buffer;
        char head[128];
        size_t n = size < 64 ? size : 64;
        for (size_t i = 0; i < n; i++) sprintf(head + i * 2, "%02x", p[i]);
        head[n * 2] = 0;
        plog("   字节码前 %zu 字节: %s", n, head);
    }
    return NEURON_NO_ERROR;
}

int NeuronModel_restoreFromCompiledNetworkV3(FakeModel* m, const void* buffer, size_t size,
                                             const void* options) {
    plog("★ NeuronModel_restoreFromCompiledNetworkV3(buffer=%p size=%zu options=%p)",
         buffer, size, options);
    return NeuronModel_restoreFromCompiledNetwork(m, buffer, size);
}

int NeuronModel_finish(FakeModel* m) {
    plog("NeuronModel_finish(model=%p bytecode=%zu)", (void*) m, m ? m->size : 0);
    return NEURON_NO_ERROR;
}

// ---------------------------------------------------------------------------
// NeuronCompilation —— 编译（模型已带字节码时理论上走不到）
// ---------------------------------------------------------------------------
int NeuronCompilation_create(FakeModel* m, FakeCompilation** out) {
    FakeCompilation* c = (FakeCompilation*) calloc(1, sizeof(FakeCompilation));
    c->magic = MAGIC_COMP;
    c->m = m;
    *out = c;
    plog("NeuronCompilation_create -> %p", (void*) c);
    return NEURON_NO_ERROR;
}

int NeuronCompilation_createWithOptions(FakeModel* m, FakeCompilation** out, const char* opts) {
    plog("NeuronCompilation_createWithOptions(opts=%s)", opts ? opts : "(null)");
    return NeuronCompilation_create(m, out);
}

int NeuronCompilation_finish(FakeCompilation* c) {
    plog("NeuronCompilation_finish(%p)", (void*) c);
    return NEURON_ERR;   // 我们没有 MTK 编译器，编译路径注定失败
}

void NeuronCompilation_free(FakeCompilation* c) {
    plog("NeuronCompilation_free(%p)", (void*) c);
    free(c);
}

int NeuronCompilation_getCompiledNetworkSize(FakeCompilation* c, uint32_t* size) {
    plog("NeuronCompilation_getCompiledNetworkSize");
    return NEURON_ERR;
}

int NeuronCompilation_storeCompiledNetwork(FakeCompilation* c, void* buffer, uint32_t size) {
    plog("NeuronCompilation_storeCompiledNetwork(size=%u)", size);
    return NEURON_ERR;
}

int NeuronCompilation_getInputPaddedSize(FakeCompilation* c, int32_t idx, uint32_t* size) {
    if (size) *size = 4 * 1024 * 1024;
    plog("NeuronCompilation_getInputPaddedSize(idx=%d)", idx);
    return NEURON_NO_ERROR;
}

int NeuronCompilation_getOutputPaddedSize(FakeCompilation* c, int32_t idx, uint32_t* size) {
    if (size) *size = 4 * 1024 * 1024;
    plog("NeuronCompilation_getOutputPaddedSize(idx=%d)", idx);
    return NEURON_NO_ERROR;
}

int NeuronCompilation_getInputPaddedDimensions(FakeCompilation* c, int32_t idx,
                                               uint32_t* dims, int32_t maxDim) {
    plog("NeuronCompilation_getInputPaddedDimensions(idx=%d)", idx);
    return NEURON_ERR;
}

int NeuronCompilation_getOutputPaddedDimensions(FakeCompilation* c, int32_t idx,
                                                uint32_t* dims, int32_t maxDim) {
    plog("NeuronCompilation_getOutputPaddedDimensions(idx=%d)", idx);
    return NEURON_ERR;
}

int NeuronCompilation_getSupportedOperations(FakeCompilation* c, const FakeModel* m,
                                             uint32_t* ops, uint32_t* count) {
    plog("NeuronCompilation_getSupportedOperations");
    return NEURON_ERR;
}

int NeuronCompilation_setOptimizationString(FakeCompilation* c, const char* s) {
    plog("NeuronCompilation_setOptimizationString(%s)", s ? s : "(null)");
    return NEURON_NO_ERROR;
}

int NeuronCompilation_setOptimizationHint(FakeCompilation* c, int hint) {
    plog("NeuronCompilation_setOptimizationHint(%d)", hint);
    return NEURON_NO_ERROR;
}

int NeuronCompilation_setPreference(FakeCompilation* c, int pref) {
    plog("NeuronCompilation_setPreference(%d)", pref);
    return NEURON_NO_ERROR;
}

int NeuronCompilation_setPriority(FakeCompilation* c, int prio) {
    plog("NeuronCompilation_setPriority(%d)", prio);
    return NEURON_NO_ERROR;
}

int NeuronCompilation_setL1MemorySizeKb(FakeCompilation* c, uint32_t kb) {
    plog("NeuronCompilation_setL1MemorySizeKb(%u)", kb);
    return NEURON_NO_ERROR;
}

// ---------------------------------------------------------------------------
// NeuronExecution —— 真正跑推理
// ---------------------------------------------------------------------------
int NeuronExecution_create(FakeCompilation* c, FakeExecution** out) {
    FakeExecution* e = (FakeExecution*) calloc(1, sizeof(FakeExecution));
    e->magic = MAGIC_EXEC;
    e->c = c;
    *out = e;
    plog("NeuronExecution_create -> %p", (void*) e);
    return NEURON_NO_ERROR;
}

void NeuronExecution_free(FakeExecution* e) {
    plog("NeuronExecution_free(%p)", (void*) e);
    free(e);
}

int NeuronExecution_setInputFromMemory(FakeExecution* e, int32_t idx, FakeMemory* mem,
                                       uint32_t offset, uint32_t size) {
    plog("NeuronExecution_setInputFromMemory(idx=%d mem=%p off=%u size=%u)", idx,
         (void*) mem, offset, size);
    return NEURON_NO_ERROR;
}

int NeuronExecution_setOutputFromMemory(FakeExecution* e, int32_t idx, FakeMemory* mem,
                                        uint32_t offset, uint32_t size) {
    plog("NeuronExecution_setOutputFromMemory(idx=%d mem=%p off=%u size=%u)", idx,
         (void*) mem, offset, size);
    return NEURON_NO_ERROR;
}

int NeuronExecution_setConfig(FakeExecution* e, const void* config) {
    plog("NeuronExecution_setConfig(%p)", config);
    return NEURON_NO_ERROR;
}

int NeuronExecution_setBoostHint(FakeExecution* e, int hint) {
    plog("NeuronExecution_setBoostHint(%d)", hint);
    return NEURON_NO_ERROR;
}

int NeuronExecution_compute(FakeExecution* e) {
    plog("★★ NeuronExecution_compute(%p) —— 探针版故意返回失败", (void*) e);
    return NEURON_ERR;
}

// ---------------------------------------------------------------------------
// NeuronMemory
// ---------------------------------------------------------------------------
int NeuronMemory_createFromFd(size_t size, int type, int fd, size_t offset, FakeMemory** out) {
    FakeMemory* m = (FakeMemory*) calloc(1, sizeof(FakeMemory));
    m->magic = MAGIC_MEM;
    m->size = size;
    *out = m;
    plog("NeuronMemory_createFromFd(size=%zu type=%d fd=%d off=%zu) -> %p", size, type,
         fd, offset, (void*) m);
    return NEURON_NO_ERROR;
}

int NeuronMemory_createFromAHardwareBuffer(void* ahwb, FakeMemory** out) {
    FakeMemory* m = (FakeMemory*) calloc(1, sizeof(FakeMemory));
    m->magic = MAGIC_MEM;
    *out = m;
    plog("NeuronMemory_createFromAHardwareBuffer(%p) -> %p", ahwb, (void*) m);
    return NEURON_NO_ERROR;
}

void NeuronMemory_free(FakeMemory* m) {
    plog("NeuronMemory_free(%p)", (void*) m);
    free(m);
}

}  // extern "C"
