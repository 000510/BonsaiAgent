// ============================================================================
// llama-jni.cpp
// JNI 桥：Kotlin 侧通过它调用 PrismML fork (branch: prism) 的 llama.cpp C API。
//
// 改动记录：
//   v1.2  补 llama_backend_init()、日志回调透传真实错误、ChatML 提示词
//   v1.5  自动修正 GGUF 旧类型标签（Kotlin 侧 GgufTypeFixer）
//   v1.6  【修复点发送后闪退】
//         1) 原自写采样器每个 token 要分配 4 个 ~15 万元素的数组（≈3.6MB），
//            prefill 一结束就分配，内存吃紧直接被系统杀 —— 改用官方 sampler 链；
//         2) llama_get_logits_ith(ctx, i) 的 i 是「输出序号」不是 batch 位置，
//            原来传 batch.n_tokens-1 取不到 logits —— 改用 llama_sampler_sample(smpl, ctx, -1)；
//         3) n_batch / n_ubatch 512 → 256，n_ctx 默认降到 2048，降低峰值内存；
//         4) max_tokens 按剩余上下文裁剪，防止 pos 越界触发 GGML_ASSERT；
//         5) 加原生崩溃捕获，把信号号 + 最后执行到的阶段写进文件，界面可直接看。
// ============================================================================
#include <jni.h>
#include <string>
#include <vector>
#include <cmath>
#include <algorithm>
#include <cstdio>
#include <cstring>
#include <cstdarg>
#include <csignal>
#include <fcntl.h>
#include <unistd.h>
#include <sys/stat.h>
#include <android/log.h>
#include "llama.h"

#define TAG "BonsaiLLM"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// ---------------------------------------------------------------------------
// 崩溃现场记录
// ---------------------------------------------------------------------------
static char g_crashFile[512] = {0};
static char g_stage[64] = "idle";

static void set_stage(const char* s) {
    snprintf(g_stage, sizeof(g_stage), "%s", s);
}

static void crash_handler(int sig) {
    if (g_crashFile[0]) {
        char buf[256];
        int n = snprintf(buf, sizeof(buf),
                         "NATIVE CRASH: signal=%d(%s) stage=%s\n",
                         sig,
                         sig == SIGSEGV ? "SIGSEGV" : sig == SIGABRT ? "SIGABRT" :
                         sig == SIGFPE  ? "SIGFPE"  : sig == SIGILL  ? "SIGILL"  :
                         sig == SIGBUS  ? "SIGBUS"  : "SIG?",
                         g_stage);
        int fd = ::open(g_crashFile, O_WRONLY | O_CREAT | O_APPEND, 0600);
        if (fd >= 0) {
            ssize_t ignored = ::write(fd, buf, (size_t) n);
            (void) ignored;
            ::close(fd);
        }
    }
    ::signal(sig, SIG_DFL);
    ::raise(sig);
}

static void install_crash_handlers() {
    struct sigaction sa{};
    sa.sa_handler = crash_handler;
    sa.sa_flags = SA_RESTART;
    sigemptyset(&sa.sa_mask);
    for (int sig : {SIGSEGV, SIGABRT, SIGFPE, SIGILL, SIGBUS}) {
        struct sigaction old{};
        ::sigaction(sig, &sa, &old);
    }
}

// 从模型路径推出崩溃日志位置：.../files/models/xxx.gguf -> .../files/last_crash.txt
static void set_crash_file_from_model(const std::string& modelPath) {
    size_t p1 = modelPath.rfind('/');
    if (p1 == std::string::npos || p1 == 0) return;
    size_t p2 = modelPath.rfind('/', p1 - 1);
    if (p2 == std::string::npos) return;
    std::string dir = modelPath.substr(0, p2);
    std::string f = dir + "/last_crash.txt";
    if (f.size() < sizeof(g_crashFile)) {
        snprintf(g_crashFile, sizeof(g_crashFile), "%s", f.c_str());
        LOGI("崩溃日志将写入 %s", g_crashFile);
    }
}

// ---------------------------------------------------------------------------
// 捕获 llama.cpp / ggml 的日志，交给 Kotlin 显示
// ---------------------------------------------------------------------------
static std::string g_lastError;
static const size_t kMaxErrLen = 2000;

static void ggml_log_cb(enum ggml_log_level level, const char * text, void * /*user_data*/) {
    if (!text) return;
    __android_log_print(level == GGML_LOG_LEVEL_ERROR ? ANDROID_LOG_ERROR :
                        level == GGML_LOG_LEVEL_WARN  ? ANDROID_LOG_WARN  :
                                                        ANDROID_LOG_INFO,
                        "llama.cpp", "%s", text);
    if (level == GGML_LOG_LEVEL_ERROR) {
        g_lastError += text;
        if (g_lastError.size() > kMaxErrLen) {
            g_lastError = g_lastError.substr(g_lastError.size() - kMaxErrLen);
        }
    }
}

struct LlamaState {
    llama_model*   model;
    llama_context* ctx;
};

// ---- 流式回调 ----
static JavaVM*   g_vm       = nullptr;
static jobject   g_cbObj    = nullptr;
static jmethodID g_cbMethod = nullptr;

JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void*) {
    g_vm = vm;
    install_crash_handlers();
    llama_backend_init();
    llama_log_set(ggml_log_cb, nullptr);
    LOGI("llama_backend_init 完成");
    return JNI_VERSION_1_6;
}

static jlong fail(JNIEnv* /*env*/, const char* stage) {
    std::string msg = g_lastError.empty() ? std::string("未知错误") : g_lastError;
    LOGE("%s 失败: %s", stage, msg.c_str());
    return 0;
}

// ---- 初始化 ----
extern "C" JNIEXPORT jlong JNICALL
Java_com_example_bonsaiagent_llm_Llama_init(
        JNIEnv* env, jobject,
        jstring jpath, jint n_threads, jint n_ctx, jint n_gpu_layers) {

    g_lastError.clear();
    set_stage("init");

    const char* path = env->GetStringUTFChars(jpath, nullptr);
    std::string spath(path ? path : "");
    env->ReleaseStringUTFChars(jpath, path);
    set_crash_file_from_model(spath);

    struct stat fst{};
    if (::stat(spath.c_str(), &fst) != 0) {
        g_lastError = "文件不存在或无法访问: " + spath;
        return fail(env, "加载模型");
    }
    if (fst.st_size < 1024 * 1024) {
        char buf[256];
        snprintf(buf, sizeof(buf), "文件太小(%lld 字节)，可能没复制完整", (long long) fst.st_size);
        g_lastError = buf;
        return fail(env, "加载模型");
    }
    LOGI("准备加载: %s (%lld 字节)", spath.c_str(), (long long) fst.st_size);

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = n_gpu_layers;

    llama_model* model = llama_model_load_from_file(spath.c_str(), mparams);
    if (!model) {
        if (g_lastError.empty()) g_lastError = "llama.cpp 未能加载该文件（格式不支持或内存不足）";
        return fail(env, "加载模型");
    }
    LOGI("模型加载成功");

    llama_context_params cparams = llama_context_default_params();
    // 手机上上下文别开太大：4096 时 KV cache 已占 ~600MB
    uint32_t want_ctx = n_ctx > 0 ? (uint32_t) n_ctx : 2048u;
    if (want_ctx > 4096u) want_ctx = 4096u;
    if (want_ctx < 512u)  want_ctx = 512u;
    cparams.n_ctx           = want_ctx;
    cparams.n_batch         = 256;      // 峰值内存和 prefill 速度的平衡点
    cparams.n_ubatch        = 256;
    cparams.n_threads       = (uint32_t) (n_threads > 0 ? n_threads : 4);
    cparams.n_threads_batch = cparams.n_threads;
    cparams.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_DISABLED;

    llama_context* ctx = llama_init_from_model(model, cparams);
    if (!ctx) {
        llama_model_free(model);
        if (g_lastError.empty()) g_lastError = "上下文创建失败（内存不足，试试把上下文长度调小）";
        return fail(env, "创建上下文");
    }

    auto* st = new LlamaState{model, ctx};
    LOGI("模型已加载, n_ctx=%u n_batch=%u threads=%u",
         cparams.n_ctx, cparams.n_batch, cparams.n_threads);
    set_stage("idle");
    return reinterpret_cast<jlong>(st);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_bonsaiagent_llm_Llama_lastError(JNIEnv* env, jobject) {
    if (g_lastError.empty()) return env->NewStringUTF("");
    std::string s = g_lastError;
    if (s.size() > 400) s = "…" + s.substr(s.size() - 400);
    return env->NewStringUTF(s.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_bonsaiagent_llm_Llama_setCallback(JNIEnv* env, jobject, jobject cb) {
    if (g_cbObj) env->DeleteGlobalRef(g_cbObj);
    g_cbObj = env->NewGlobalRef(cb);
    jclass cls = env->GetObjectClass(cb);
    g_cbMethod = env->GetMethodID(cls, "onToken", "(Ljava/lang/String;)V");
}

// ---------------------------------------------------------------------------
// 生成（流式）
// ---------------------------------------------------------------------------
extern "C" JNIEXPORT jstring JNICALL
Java_com_example_bonsaiagent_llm_Llama_generate(
        JNIEnv* env, jobject,
        jlong handle, jstring jprompt,
        jfloat temp, jint top_k, jfloat top_p, jfloat rep_pen, jint max_tokens) {

    auto* st = reinterpret_cast<LlamaState*>(handle);
    if (!st || !st->ctx || !st->model) return env->NewStringUTF("[模型未初始化]");

    g_lastError.clear();
    set_stage("tokenize");

    const char* cprompt = env->GetStringUTFChars(jprompt, nullptr);
    std::string prompt(cprompt ? cprompt : "");
    env->ReleaseStringUTFChars(jprompt, cprompt);

    const struct llama_vocab * vocab = llama_model_get_vocab(st->model);
    if (!vocab) {
        g_lastError = "取不到词表";
        return env->NewStringUTF("[词表为空]");
    }
    const int n_vocab = llama_vocab_n_tokens(vocab);

    int need = llama_tokenize(vocab, prompt.c_str(), (int) prompt.size(),
                              nullptr, 0, true, true);
    if (need == 0) return env->NewStringUTF("");
    if (need < 0) need = -need;

    std::vector<llama_token> tokens((size_t) need + 8);
    int n_tok = llama_tokenize(vocab, prompt.c_str(), (int) prompt.size(),
                               tokens.data(), (int) tokens.size(), true, true);
    if (n_tok <= 0) {
        g_lastError = "tokenize 返回 " + std::to_string(n_tok);
        return env->NewStringUTF("[tokenize 失败]");
    }
    tokens.resize((size_t) n_tok);

    const int n_ctx = (int) llama_n_ctx(st->ctx);
    int room = n_ctx - 8;
    if (n_tok > room) {              // prompt 本身过长，截断
        n_tok = room;
        if (n_tok <= 0) return env->NewStringUTF("[上下文太小]");
        tokens.resize((size_t) n_tok);
    }

    // 预算：不超过剩余窗口，默认 2048
    int budget = max_tokens > 0 ? max_tokens : 2048;
    int left = n_ctx - n_tok - 1;
    if (budget > left) budget = left;
    if (budget <= 0) return env->NewStringUTF("[上下文不够，先清空对话或调大上下文]");

    // ---- 官方采样器链：penalties → top-k → top-p → temp → dist ----
    set_stage("sampler");
    llama_sampler* smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    if (!smpl) {
        g_lastError = "采样器创建失败";
        return env->NewStringUTF("[采样器失败]");
    }
    if (rep_pen > 1.0001f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_penalties(n_vocab, 64, rep_pen, 0.0f, 0.0f));
    }
    if (top_k > 0) llama_sampler_chain_add(smpl, llama_sampler_init_top_k(top_k));
    if (top_p > 0.0f && top_p < 0.999f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_top_p(top_p, 1));
    }
    if (temp > 0.0001f) llama_sampler_chain_add(smpl, llama_sampler_init_temp(temp));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    // ---- prefill ----
    set_stage("prefill");
    int cap = std::max(n_tok, 8);
    llama_batch batch = llama_batch_init(cap, 0, 1);
    llama_seq_id seq0 = 0;

    batch.n_tokens = n_tok;
    for (int i = 0; i < n_tok; i++) {
        batch.token[i]    = tokens[(size_t) i];
        batch.pos[i]      = i;
        batch.n_seq_id[i] = 1;
        batch.seq_id[i]   = &seq0;
        batch.logits[i]   = 0;
    }
    batch.logits[n_tok - 1] = 1;

    if (llama_decode(st->ctx, batch) != 0) {
        llama_batch_free(batch);
        llama_sampler_free(smpl);
        g_lastError = "prefill 失败：" + g_lastError;
        set_stage("idle");
        return env->NewStringUTF("[prefill 失败]");
    }

    // ---- 逐 token 生成 ----
    set_stage("generate");
    std::string full;
    int n_past = n_tok;
    char buf[512];

    for (int i = 0; i < budget; i++) {
        // idx = -1 表示「最后一个输出」，与 logits 输出序号的语义一致
        llama_token next = llama_sampler_sample(smpl, st->ctx, -1);
        if (next < 0) break;
        if (llama_vocab_is_eog(vocab, next)) break;

        int len = llama_token_to_piece(vocab, next, buf, (int32_t) sizeof(buf), 0, false);
        if (len > 0) {
            full.append(buf, (size_t) len);
            if (g_cbObj && g_cbMethod) {
                jstring jp = env->NewStringUTF(std::string(buf, (size_t) len).c_str());
                env->CallVoidMethod(g_cbObj, g_cbMethod, jp);
                env->DeleteLocalRef(jp);
            }
        }

        batch.n_tokens    = 1;
        batch.token[0]    = next;
        batch.pos[0]      = n_past;
        batch.n_seq_id[0] = 1;
        batch.seq_id[0]   = &seq0;
        batch.logits[0]   = 1;

        if (llama_decode(st->ctx, batch) != 0) {
            g_lastError = "decode 失败：" + g_lastError;
            break;
        }
        n_past++;
        if (n_past >= n_ctx - 1) break;   // 窗口到底了，别越界
    }

    llama_batch_free(batch);
    llama_sampler_free(smpl);
    set_stage("idle");
    LOGI("生成结束，共 %zu 字节，%d tokens", full.size(), n_past - n_tok);
    return env->NewStringUTF(full.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_bonsaiagent_llm_Llama_freeNative(JNIEnv*, jobject, jlong handle) {
    auto* st = reinterpret_cast<LlamaState*>(handle);
    if (!st) return;
    if (st->ctx)   llama_free(st->ctx);
    if (st->model) llama_model_free(st->model);
    delete st;
    LOGI("已释放模型");
}
