// ============================================================================
// llama-jni.cpp
// JNI 桥：Kotlin 侧通过它调用 PrismML fork (branch: prism) 的 llama.cpp C API。
//
// 改动记录：
//   v1.6  改用官方 sampler 链、sampler_sample(-1)、UI 回主线程
//   v1.7  【继续追闪退】
//         1) 全过程分步日志落盘（files/run_log.txt），崩在哪一步、当时剩多少内存都有记录；
//         2) n_ctx 上限压到 2048（4096 时 KV cache 就要 600MB），n_batch 128 / n_ubatch 64；
//         3) prefill 改成分块提交，降低单次 decode 的峰值内存；
//         4) decode 前查 /proc/meminfo，内存不够就直接返回提示而不是硬跑被系统杀。
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
// 日志文件 & 崩溃现场
// ---------------------------------------------------------------------------
static char g_logFile[512]   = {0};
static char g_crashFile[512] = {0};
static char g_stage[64]      = "idle";
static char g_stageBuf[128]  = "stage=idle\n";
// 备用信号栈：栈溢出导致的 SIGSEGV 用默认栈根本跑不起来 handler
static char g_altStack[64 * 1024] __attribute__((aligned(16)));

static void log_line(const char* fmt, ...) {
    char buf[768];
    va_list ap;
    va_start(ap, fmt);
    int n = vsnprintf(buf, sizeof(buf) - 2, fmt, ap);
    va_end(ap);
    if (n < 0) return;
    if (n > (int) sizeof(buf) - 2) n = (int) sizeof(buf) - 2;
    buf[n++] = '\n';
    buf[n] = '\0';
    __android_log_print(ANDROID_LOG_INFO, TAG, "%s", buf);
    if (!g_logFile[0]) return;
    int fd = ::open(g_logFile, O_WRONLY | O_CREAT | O_APPEND, 0600);
    if (fd >= 0) {
        ssize_t ignored = ::write(fd, buf, (size_t) n);
        (void) ignored;
        ::close(fd);
    }
}

// /proc/meminfo 的 MemAvailable（MB）
static long mem_available_mb() {
    FILE* f = fopen("/proc/meminfo", "r");
    if (!f) return -1;
    char line[256];
    long kb = -1;
    while (fgets(line, sizeof(line), f)) {
        if (strncmp(line, "MemAvailable:", 13) == 0) {
            long v = 0;
            if (sscanf(line + 13, "%ld", &v) == 1) kb = v;
            break;
        }
    }
    fclose(f);
    return kb < 0 ? -1 : kb / 1024;
}

static void set_stage(const char* s) {
    snprintf(g_stage, sizeof(g_stage), "%s", s);
    // 提前格式化，信号处理里只做 write，避免崩溃时死在 snprintf
    snprintf(g_stageBuf, sizeof(g_stageBuf), "stage=%s\n", s);
}

static const char* sig_name(int sig) {
    switch (sig) {
        case SIGSEGV: return "SIGSEGV ";
        case SIGABRT: return "SIGABRT ";
        case SIGFPE:  return "SIGFPE ";
        case SIGILL:  return "SIGILL ";
        case SIGBUS:  return "SIGBUS ";
        default:      return "SIG? ";
    }
}

static void crash_handler(int sig) {
    // 只做 open/write/close，全部用预格式化好的缓冲
    const char* name = sig_name(sig);
    size_t nl = strlen(name), sl = strlen(g_stageBuf);
    if (g_crashFile[0]) {
        int fd = ::open(g_crashFile, O_WRONLY | O_CREAT | O_APPEND, 0600);
        if (fd >= 0) {
            ssize_t ig;
            ig = ::write(fd, "NATIVE CRASH: ", 14); (void) ig;
            ig = ::write(fd, name, nl);             (void) ig;
            ig = ::write(fd, g_stageBuf, sl);       (void) ig;
            ::close(fd);
        }
    }
    if (g_logFile[0]) {
        int fd = ::open(g_logFile, O_WRONLY | O_CREAT | O_APPEND, 0600);
        if (fd >= 0) {
            ssize_t ig;
            ig = ::write(fd, "!!! ", 4);       (void) ig;
            ig = ::write(fd, name, nl);        (void) ig;
            ig = ::write(fd, g_stageBuf, sl);  (void) ig;
            ::close(fd);
        }
    }
    ::signal(sig, SIG_DFL);
    ::raise(sig);
}

static void install_crash_handlers() {
    stack_t ss{};
    ss.ss_sp    = g_altStack;
    ss.ss_size  = sizeof(g_altStack);
    ss.ss_flags = 0;
    ::sigaltstack(&ss, nullptr);

    struct sigaction sa{};
    sa.sa_handler = crash_handler;
    sa.sa_flags = SA_ONSTACK | SA_RESTART;
    sigemptyset(&sa.sa_mask);
    int sigs[5] = {SIGSEGV, SIGABRT, SIGFPE, SIGILL, SIGBUS};
    for (int i = 0; i < 5; i++) {
        struct sigaction old{};
        ::sigaction(sigs[i], &sa, &old);
    }
}

// .../files/models/xxx.gguf -> .../files/
static bool files_dir_from_model(const std::string& modelPath, std::string& out) {
    size_t p1 = modelPath.rfind('/');
    if (p1 == std::string::npos || p1 == 0) return false;
    size_t p2 = modelPath.rfind('/', p1 - 1);
    if (p2 == std::string::npos) return false;
    out = modelPath.substr(0, p2);
    return !out.empty();
}

// 把 C 层 stderr 也接到文件里：ggml 的 "fatal error" 大多走 fprintf(stderr)
static void redirect_stderr(const std::string& path) {
    int fd = ::open(path.c_str(), O_WRONLY | O_CREAT | O_TRUNC, 0600);
    if (fd >= 0) {
        ::dup2(fd, STDERR_FILENO);
        if (fd != STDERR_FILENO) ::close(fd);
    }
}

// ---------------------------------------------------------------------------
// llama.cpp 日志
// ---------------------------------------------------------------------------
static std::string g_lastError;
static const size_t kMaxErrLen = 2000;

static void ggml_log_cb(enum ggml_log_level level, const char * text, void * /*user_data*/) {
    if (!text) return;
    if (level == GGML_LOG_LEVEL_ERROR) {
        g_lastError += text;
        if (g_lastError.size() > kMaxErrLen) {
            g_lastError = g_lastError.substr(g_lastError.size() - kMaxErrLen);
        }
        log_line("[llama] %s", text);
    }
}

struct LlamaState {
    llama_model*   model;
    llama_context* ctx;
};

static JavaVM*   g_vm       = nullptr;
static jobject   g_cbObj    = nullptr;
static jmethodID g_cbMethod = nullptr;

JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void*) {
    g_vm = vm;
    install_crash_handlers();
    llama_backend_init();
    llama_log_set(ggml_log_cb, nullptr);
    return JNI_VERSION_1_6;
}

static jlong fail(const char* stage) {
    std::string msg = g_lastError.empty() ? std::string("未知错误") : g_lastError;
    log_line("[失败] %s: %s", stage, msg.c_str());
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

    std::string dir;
    if (files_dir_from_model(spath, dir)) {
        std::string lf = dir + "/run_log.txt";
        std::string cf = dir + "/last_crash.txt";
        std::string ef = dir + "/native_stderr.txt";
        if (lf.size() < sizeof(g_logFile))   snprintf(g_logFile,   sizeof(g_logFile),   "%s", lf.c_str());
        if (cf.size() < sizeof(g_crashFile)) snprintf(g_crashFile, sizeof(g_crashFile), "%s", cf.c_str());
        // 每次加载开一份新日志
        int fd = ::open(g_logFile, O_WRONLY | O_CREAT | O_TRUNC, 0600);
        if (fd >= 0) ::close(fd);
        redirect_stderr(ef);
    }

    log_line("===== 开始加载 =====");
    log_line("可用内存 %ld MB", mem_available_mb());
    log_line("路径 %s", spath.c_str());

    struct stat fst{};
    if (::stat(spath.c_str(), &fst) != 0) {
        g_lastError = "文件不存在或无法访问: " + spath;
        return fail("加载模型");
    }
    log_line("文件大小 %.2f GB", (double) fst.st_size / (1024.0 * 1024.0 * 1024.0));
    if (fst.st_size < 1024 * 1024) {
        char buf[256];
        snprintf(buf, sizeof(buf), "文件太小(%lld 字节)，可能没复制完整", (long long) fst.st_size);
        g_lastError = buf;
        return fail("加载模型");
    }

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = n_gpu_layers;

    llama_model* model = llama_model_load_from_file(spath.c_str(), mparams);
    if (!model) {
        if (g_lastError.empty()) g_lastError = "llama.cpp 未能加载该文件（格式不支持或内存不足）";
        return fail("加载模型");
    }
    log_line("模型加载完成，可用内存 %ld MB", mem_available_mb());

    llama_context_params cparams = llama_context_default_params();
    uint32_t want_ctx = n_ctx > 0 ? (uint32_t) n_ctx : 2048u;
    if (want_ctx > 2048u) want_ctx = 2048u;   // 4096 时 KV cache 约 600MB，手机扛不住
    if (want_ctx < 512u)  want_ctx = 512u;
    cparams.n_ctx           = want_ctx;
    cparams.n_batch         = 128;
    cparams.n_ubatch        = 64;
    cparams.n_threads       = (uint32_t) (n_threads > 0 ? n_threads : 4);
    cparams.n_threads_batch = cparams.n_threads;
    cparams.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_DISABLED;

    llama_context* ctx = llama_init_from_model(model, cparams);
    if (!ctx) {
        llama_model_free(model);
        if (g_lastError.empty()) g_lastError = "上下文创建失败（内存不足，试试把上下文长度调小）";
        return fail("创建上下文");
    }

    auto* st = new LlamaState{model, ctx};
    log_line("上下文就绪 n_ctx=%u n_batch=%u n_ubatch=%u threads=%u，可用内存 %ld MB",
             cparams.n_ctx, cparams.n_batch, cparams.n_ubatch, cparams.n_threads,
             mem_available_mb());
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
    log_line("----- generate 开始，可用内存 %ld MB -----", mem_available_mb());

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
    log_line("prompt %d tokens", n_tok);

    const int n_ctx = (int) llama_n_ctx(st->ctx);
    int room = n_ctx - 8;
    if (n_tok > room) {
        n_tok = room;
        if (n_tok <= 0) return env->NewStringUTF("[上下文太小]");
        tokens.resize((size_t) n_tok);
    }

    int budget = max_tokens > 0 ? max_tokens : 2048;
    int left = n_ctx - n_tok - 1;
    if (budget > left) budget = left;
    if (budget <= 0) return env->NewStringUTF("[上下文不够，先清空对话或调大上下文]");

    long mem = mem_available_mb();
    if (mem > 0 && mem < 600) {
        log_line("[放弃] 可用内存只有 %ld MB", mem);
        g_lastError = "可用内存不足(" + std::to_string(mem) + " MB)，先关掉别的应用再试";
        return env->NewStringUTF("[内存不足]");
    }

    // ---- 采样器 ----
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

    // ---- prefill：分块提交，单批不超 32 ----
    set_stage("prefill");
    const int kChunk = 8;    // 分小块提交，定位崩溃点更细
    int cap = std::max(kChunk, 8);
    llama_batch batch = llama_batch_init(cap, 0, 1);
    llama_seq_id seq0 = 0;

    for (int off = 0; off < n_tok; off += kChunk) {
        int cnt = std::min(kChunk, n_tok - off);
        batch.n_tokens = cnt;
        for (int i = 0; i < cnt; i++) {
            batch.token[i]    = tokens[(size_t) (off + i)];
            batch.pos[i]      = off + i;
            batch.n_seq_id[i] = 1;
            batch.seq_id[i]   = &seq0;
            batch.logits[i]   = 0;
        }
        bool last = (off + cnt == n_tok);
        batch.logits[cnt - 1] = last ? 1 : 0;

        char stage[64];
        snprintf(stage, sizeof(stage), "prefill %d..%d", off, off + cnt - 1);
        set_stage(stage);
        log_line("%s 开始", stage);
        if (llama_decode(st->ctx, batch) != 0) {
            llama_batch_free(batch);
            llama_sampler_free(smpl);
            g_lastError = "prefill 失败：" + g_lastError;
            log_line("[失败] %s", stage);
            set_stage("idle");
            return env->NewStringUTF("[prefill 失败]");
        }
        log_line("%s 完成", stage);
    }
    log_line("prefill 完成，可用内存 %ld MB", mem_available_mb());

    // ---- 生成 ----
    set_stage("generate");
    std::string full;
    int n_past = n_tok;
    char buf[512];

    for (int i = 0; i < budget; i++) {
        if (i % 8 == 0) {
            log_line("生成中… 第 %d 个 token，可用内存 %ld MB", i, mem_available_mb());
        }
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
            log_line("[失败] decode 第 %d 个 token", i);
            break;
        }
        n_past++;
        if (n_past >= n_ctx - 1) break;
    }

    llama_batch_free(batch);
    llama_sampler_free(smpl);
    set_stage("idle");
    log_line("生成结束：%zu 字节 / %d tokens，可用内存 %ld MB",
             full.size(), n_past - n_tok, mem_available_mb());
    return env->NewStringUTF(full.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_bonsaiagent_llm_Llama_freeNative(JNIEnv*, jobject, jlong handle) {
    auto* st = reinterpret_cast<LlamaState*>(handle);
    if (!st) return;
    if (st->ctx)   llama_free(st->ctx);
    if (st->model) llama_model_free(st->model);
    delete st;
    log_line("已释放模型");
}
