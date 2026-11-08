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
#include <cerrno>
#include <fcntl.h>
#include <unistd.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <chrono>
#include <random>
#include <pthread.h>
#include <dlfcn.h>
#include <android/log.h>
#include "llama.h"
#include "ggml-backend.h"

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
        case SIGSYS:  return "SIGSYS ";
        case SIGTRAP: return "SIGTRAP ";
        case SIGQUIT: return "SIGQUIT ";
        default:      return "SIG? ";
    }
}

// ---------------------------------------------------------------------------
// v3.0 诊断：劫持"程序自尽"的四个入口
//
// 背景：ggml/llama 内部发现问题时走 GGML_ABORT()，最终落到 libc 的 abort()。
// Android 上 native stderr 默认丢弃，所以我们连一句错误都看不到，看起来"无声消失"。
// 用 -Wl,--wrap=xxx 把静态库(ggml)里对这些符号的引用重定向到我们自己的实现，
// 就能把死因原样写进 run_log.txt。
// ---------------------------------------------------------------------------
static void write_fatal(const char* what, const char* detail) {
    log_line("!!! 致命: %s%s%s", what, detail ? " | " : "", detail ? detail : "");
    if (g_crashFile[0]) {
        int fd = ::open(g_crashFile, O_WRONLY | O_CREAT | O_APPEND, 0600);
        if (fd >= 0) {
            ssize_t ig;
            ig = ::write(fd, what, strlen(what));  (void) ig;
            if (detail) { ig = ::write(fd, " | ", 3); (void) ig;
                          ig = ::write(fd, detail, strlen(detail)); (void) ig; }
            ig = ::write(fd, "\n", 1); (void) ig;
            ::close(fd);
        }
    }
}

extern "C" {
void __real_abort(void) __attribute__((noreturn));

// ggml 的 GGML_ABORT 最终调 abort()；这里接住并记录后立即结束，不让 ART 打 tombstone
void __wrap_abort(void) {
    write_fatal("ABORT (ggml/llama 内部致命错误)", g_stage);
    _exit(66);
}

// bionic 的 assert() 走 __assert2(file, line, func, expr)
void __wrap___assert2(const char* file, int line, const char* func, const char* expr) {
    char buf[512];
    snprintf(buf, sizeof(buf), "%s:%d %s() 断言失败: %s",
             file ? file : "?", line, func ? func : "?", expr ? expr : "?");
    write_fatal("ASSERT", buf);
    _exit(67);
}

// 栈被踩坏（缓冲区溢出）时 bionic 调这个
void __wrap___stack_chk_fail(void) {
    write_fatal("STACK_CHK_FAIL (栈溢出/缓冲区越界，__stack_chk_guard 被改)", g_stage);
    _exit(68);
}

// _FORTIFY_SOURCE 检查失败
void __wrap___fortify_fail(const char* msg) {
    write_fatal("FORTIFY_FAIL", msg ? msg : "?");
    _exit(69);
}

// __assert 是老的 assert 入口，一并接住
void __wrap___assert(const char* file, int line, const char* expr) {
    char buf[512];
    snprintf(buf, sizeof(buf), "%s:%d 断言失败: %s",
             file ? file : "?", line, expr ? expr : "?");
    write_fatal("ASSERT", buf);
    _exit(70);
}
}  // extern "C"

// ---------------------------------------------------------------------------
// v3.2：进程级故障信号拦截（带"只接自己线程"的过滤）
//
// 为什么加：
//   v3.1 的 wrap(abort/assert/...) 一个都没触发、stderr 也全空 —— 说明进程
//   不是"自己退出的"，而是撞了内存访问错误，或被系统直接 SIGKILL。
//   装上这个 handler 就能把前者抓出来（还能知道崩在哪个 .so、故障地址多少）。
//
// 为什么不破坏 ART：
//   ART 的并发 GC 用 SIGSEGV 做读写屏障，信号是高频的。如果全接住，
//   App 会在 GC 时被我们误杀。所以这里按【线程名】分流：
//     - 线程名含 llama/ggml/bonsai  → 是我们在跑推理，记录后退出
//     - 其它线程（main/GC/... 即 ART）→ 原样转发给 ART 自己的 handler
// ---------------------------------------------------------------------------
// v6.6：原来只接了 SEGV/BUS/ILL/FPE，漏掉了 SIGABRT ——
// 而 ggml 的 GGML_ABORT 最终就是走 abort()，等于把最常见的一类死因放过去了。
// 补上 SIGABRT / SIGSYS / SIGTRAP，做到"任何异常终止都会留记录"。
static struct sigaction g_old_sa[7];
static int              g_old_sig[7] = {SIGSEGV, SIGBUS, SIGILL, SIGFPE, SIGABRT, SIGSYS, SIGTRAP};

static void fault_handler(int sig, siginfo_t* si, void* uc) {
    char tname[64] = {0};
    pthread_getname_np(pthread_self(), tname, sizeof(tname));

    bool ours = strstr(tname, "llama") || strstr(tname, "ggml") || strstr(tname, "bonsai");

    if (!ours) {
        // ART 自己的线程：原封不动转交给 ART 的处理器（例如 GC 屏障）
        for (int i = 0; i < 4; i++) {
            if (g_old_sig[i] == sig) {
                const struct sigaction& o = g_old_sa[i];
                if (o.sa_flags & SA_SIGINFO) {
                    if (o.sa_sigaction) { o.sa_sigaction(sig, si, uc); return; }
                } else if (o.sa_handler != SIG_DFL && o.sa_handler != SIG_IGN) {
                    o.sa_handler(sig); return;
                }
                break;
            }
        }
        // 没有可转交的，走默认
        ::signal(sig, SIG_DFL);
        ::raise(sig);
        return;
    }

    // 是我们的线程崩了 —— 把能拿到的现场全记下来
    void* addr = si ? si->si_addr : nullptr;
    Dl_info info{};
    const char* where = "?";
    if (addr && ::dladdr(addr, &info) && info.dli_fname) where = info.dli_fname;

    char buf[512];
    snprintf(buf, sizeof(buf),
             "sig=%s thread=%s addr=%p in_lib=%s stage=%s",
             sig_name(sig), tname, addr, where, g_stage);
    write_fatal("原生信号故障", buf);

    // 调用栈：bionic 没有 glibc 的 backtrace()，这里退一步，
    // 只再取一层返回地址（能区分"崩在 llama_decode 直接调用层"还是更深）。
    if (g_crashFile[0]) {
        int fd = ::open(g_crashFile, O_WRONLY | O_CREAT | O_APPEND, 0600);
        if (fd >= 0) {
            Dl_info ri{};
            const char* rn = "?";
            void* ra = __builtin_return_address(0);
            if (ra && ::dladdr(ra, &ri) && ri.dli_fname) rn = ri.dli_fname;
            char line[192];
            int k = snprintf(line, sizeof(line), "ret0=%p in=%s\n", ra, rn);
            if (k > 0) { ssize_t ig = ::write(fd, line, (size_t) k); (void) ig; }
            ::close(fd);
        }
    }

    _exit(139);
}

static void install_fault_handlers() {
    struct sigaction sa{};
    sa.sa_sigaction = fault_handler;
    sa.sa_flags     = SA_SIGINFO | SA_RESTART;   // 不加 SA_ONSTACK：栈溢出已排除
    sigemptyset(&sa.sa_mask);
    for (int i = 0; i < 7; i++) {
        ::sigaction(g_old_sig[i], &sa, &g_old_sa[i]);
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
    // 关键修复（v1.13）：这里原来带 SA_ONSTACK，是本项目最大的"左脚踩右脚"。
    // altstack 是按线程生效的，只在 JNI_OnLoad 那个线程设过；真正跑 llama_decode 的
    // "llama-gen" 线程没有 altstack，内核无法投递信号 → 直接杀进程，handler 从不执行，
    // 于是 last_crash.txt / native_stderr.txt 全空，看起来像"无声消失"。
    // 去掉 SA_ONSTACK 后，handler 用当前线程的普通栈执行，32MB 够用。
    sa.sa_flags = SA_RESTART;
    sigemptyset(&sa.sa_mask);
    int sigs[8] = {SIGSEGV, SIGABRT, SIGFPE, SIGILL, SIGBUS, SIGSYS, SIGTRAP, SIGQUIT};
    for (int i = 0; i < 8; i++) {
        struct sigaction old{};
        ::sigaction(sigs[i], &sa, &old);
    }
}

// 给"当前线程"装一份 altstack。必须在真正干活的线程里调用，
// 因为 sigaltstack 只对调用它的那个线程生效。
static void install_altstack_here() {
    stack_t ss{};
    ss.ss_sp    = g_altStack;
    ss.ss_size  = sizeof(g_altStack);
    ss.ss_flags = 0;
    ::sigaltstack(&ss, nullptr);
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
    // v3.0：恢复 stderr 落盘。ggml 的 "fatal error"、warn、assert 消息全走 fprintf(stderr)，
    // Android 上 native stderr 默认被丢弃 —— 不接住就等于闭着眼找 bug。
    // 关键：必须关掉缓冲，否则进程被 kill 时缓冲区里的内容永远写不出去。
    int fd = ::open(path.c_str(), O_WRONLY | O_CREAT | O_TRUNC, 0600);
    if (fd >= 0) {
        ::dup2(fd, STDERR_FILENO);
        if (fd != STDERR_FILENO) ::close(fd);
    }
    ::setvbuf(stderr, nullptr, _IONBF, 0);   // 无缓冲，逐字节落盘
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
    }
    // v4.1：所有级别都落盘（含 DEBUG）。
    // 动态后端选型的关键信息（每个变体的 ggml_backend_score）是 DEBUG 级，
    // 只记 ERROR 的话就看不出它到底挑了哪一份、为什么挑。
    log_line("[llama] %s", text);
}

// v1.14：接住 ggml 的致命错误回调。
// ggml_abort() 在 abort() 之前会先调用这个回调，把具体原因（文件:行号 + 消息）交出来。
// 之前没接，所以即使 ggml 自己报了错，我们也什么都看不到。
static ggml_abort_callback_t g_prev_abort_cb = nullptr;
// v4.0：后端改为动态加载（GGML_BACKEND_DL）后，必须在 llama_backend_init()
// 之前告诉 ggml 去哪里 dlopen 那些 libggml-cpu-*.so。
// Android 上 /proc/self/exe 指向 zygote，ggml 默认按它找目录必然落空，
// 所以由 Kotlin 侧传入 applicationInfo.nativeLibraryDir。
static bool g_backend_ready = false;
// CPU 后端 .so 所在目录（Kotlin 侧传 applicationInfo.nativeLibraryDir）
static char g_backendDir[512] = {0};

static void ggml_fatal_cb(const char * msg) {
    log_line("[ggml FATAL] %s", msg ? msg : "(null msg)");
    g_lastError += "[FATAL] ";
    g_lastError += (msg ? msg : "(null)");
    if (g_prev_abort_cb) g_prev_abort_cb(msg);
}

struct LlamaState {
    llama_model*   model;
    llama_context* ctx;

    // v8.0 KV 前缀缓存（"汤底复用"）：
    // system prompt 每轮完全相同，却要每轮重新 prefill —— 实测占单轮 15 秒里的 14 秒。
    // 这里把它的 KV 状态算一次存下来，之后每轮直接恢复，只需处理用户那一小段。
    std::string          sysText;    // 上次缓存的 system 前缀原文
    std::vector<uint8_t> sysKv;      // 对应的 KV 快照
    int                  sysTokLen = 0;
};

static JavaVM*   g_vm       = nullptr;
static jobject   g_cbObj    = nullptr;
static jmethodID g_cbMethod = nullptr;

JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void*) {
    g_vm = vm;
    // v2.1 关键修复：不再安装自定义信号处理器。
    // 原因：install_crash_handlers() 会覆盖 ART 自己的 SIGSEGV 处理器，
    // 而 ART 的并发 GC 用 SIGSEGV 实现读写屏障。decode 时大量分配内存触发 GC，
    // ART 一用 SIGSEGV 就被我们的 handler 截住 -> signal(SIG_DFL)+raise -> 进程直接死。
    // 而且在 ARM64+qemu 上跑同一份 llama.cpp 完全正常，证明问题就在这一层。
    // install_crash_handlers();
    install_fault_handlers();   // v3.2：只拦截 llama/ggml 线程上的故障信号
    // v4.0：backend 初始化推迟到 setBackendPath + init()，
    // 因为动态后端要先知道 .so 目录。
    llama_log_set(ggml_log_cb, nullptr);
    g_prev_abort_cb = ggml_set_abort_callback(ggml_fatal_cb);   // v1.14
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_bonsaiagent_llm_Llama_setBackendPath(JNIEnv* env, jobject, jstring jdir) {
    const char* d = env->GetStringUTFChars(jdir, nullptr);
    if (d) {
        if (strlen(d) < sizeof(g_backendDir)) snprintf(g_backendDir, sizeof(g_backendDir), "%s", d);
        LOGI("backend 目录=%s", g_backendDir);
        env->ReleaseStringUTFChars(jdir, d);
    }
}

// ---------------------------------------------------------------------------
// v7.1：UTF-8 边界处理
//
// llama_token_to_piece 返回的是"这个 token 对应的字节"，而 token 边界不一定
// 落在字符边界上 —— 一个汉字常被切成两个 token，前半截是非法 UTF-8。
// 直接丢给 env->NewStringUTF() 会让 ART 的 JNI 校验失败并 abort 整个进程
// （实测日志表现为 SIGABRT、stage=gen:1、piece_len=2 而内容是乱码）。
//
// 做法：跨 token 累积字节，只有凑成完整 UTF-8 字符才交给上层。
// 这与 llama.cpp 官方 CLI 的处理方式一致。
// ---------------------------------------------------------------------------
static std::string g_utf8Pending;

// 从 s 里取出所有完整的 UTF-8 字符，未完成的尾部留在 s 中
static std::string take_complete_utf8(std::string& s) {
    size_t i = 0, last = 0;
    while (i < s.size()) {
        unsigned char c = (unsigned char) s[i];
        int need;
        if (c < 0x80)                    need = 0;
        else if ((c & 0xE0) == 0xC0)     need = 1;
        else if ((c & 0xF0) == 0xE0)     need = 2;
        else if ((c & 0xF8) == 0xF0)     need = 3;
        else { i++; last = i; continue; }        // 非法首字节，跳过
        if (i + (size_t) need >= s.size()) break; // 还没收全，等下一个 token
        bool ok = true;
        for (int k = 1; k <= need; k++) {
            if (((unsigned char) s[i + k] & 0xC0) != 0x80) { ok = false; break; }
        }
        if (!ok) { i++; last = i; continue; }      // 继续字节不合法，丢弃首字节
        i += (size_t) need + 1;
        last = i;
    }
    std::string out = s.substr(0, last);
    s.erase(0, last);
    return out;
}

// 首次真正使用前初始化 backend（会按 GGML_BACKEND_PATH dlopen 各指令集变体）
static void ensure_backend() {
    if (g_backend_ready) return;
    llama_backend_init();

    // 关键：GGML_BACKEND_DL 模式下 llama_backend_init() 并不会自动加载后端，
    // 必须显式扫描目录 dlopen。ggml 默认按 /proc/self/exe（Android 上是 zygote）
    // 找同目录，必然落空，所以这里直接把 APK 的 native 库目录传进去。
    // 它会匹配 libggml-cpu-*.so，再按文件名里的 armv8.x 版本评分挑最优的那份。
    // v5.1：静态链接时 CPU 后端已编进主库，ggml_backend_reg_count() 一开始就非 0，
    // 不需要（也不应该）再去 dlopen。只有注册数为 0 才走动态加载兜底。
    if (ggml_backend_reg_count() == 0) {
        if (g_backendDir[0]) {
            log_line("后端数为 0，尝试动态加载，目录=%s", g_backendDir);
            ggml_backend_load_all_from_path(g_backendDir);
        } else {
            log_line("后端数为 0，退回默认搜索路径");
            ggml_backend_load_all();
        }
    }

    g_backend_ready = true;
    int nreg = (int) ggml_backend_reg_count();
    log_line("backend 初始化完成，注册后端 %d 个", nreg);
    for (int i = 0; i < nreg; i++) {
        ggml_backend_reg_t rg = ggml_backend_reg_get((size_t) i);
        if (rg) log_line("  后端[%d] = %s", i, ggml_backend_reg_name(rg));
    }
    if (nreg == 0) {
        g_lastError = "没有加载到任何 CPU 后端（动态库 dlopen 失败）";
    }
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
        // v3.0：恢复 stderr 落盘（ggml 的致命错误全走 fprintf(stderr)，Android 默认丢弃）
        redirect_stderr(ef);
        log_line("stderr 已接入 %s", ef.c_str());
    }

    ensure_backend();   // 此时日志文件已就绪，后端加载情况能落盘
    // v5.3：把 CPU 指令集能力打出来。
    // 这是能不能启用 dotprod / i8mm 加速的唯一依据（编译期决定指令集，
    // 编错了会 SIGILL）。用户把日志发来就能对症选 arch。
    {
        std::string feat;
        FILE* f = fopen("/proc/cpuinfo", "r");
        if (f) {
            char line[4096];
            while (fgets(line, sizeof(line), f)) {
                if (strncmp(line, "Features", 8) == 0) { feat = line; break; }
            }
            fclose(f);
        }
        auto has = [&](const char* k) {
            return feat.find(k) != std::string::npos;
        };
        log_line("CPU 特性: dotprod=%d i8mm=%d sve=%d sve2=%d fp16=%d "
                 "(asimddp=%d asimdhp=%d i8mm=%d)",
                 has("asimddp") ? 1 : 0,
                 has("i8mm") ? 1 : 0,
                 has("sve") ? 1 : 0,
                 has("sve2") ? 1 : 0,
                 has("asimdhp") ? 1 : 0,
                 has("asimddp") ? 1 : 0,
                 has("asimdhp") ? 1 : 0,
                 has("i8mm") ? 1 : 0);
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
    // v6.6：上限提到 3072。用户写 1000 字作文需要约 1400 token 的生成预算，
    // 2048 上下文塞进历史后只剩一千出头，长文会被硬截断（表现为"字数明显少"）。
    // FP32 KV cache 下 3072 约多占 900MB，可用内存 5GB 撑得住。
    if (want_ctx > 3072u) want_ctx = 3072u;
    if (want_ctx < 512u)  want_ctx = 512u;
    cparams.n_ctx           = want_ctx;
    // v10.41：重新尝试放大 batch。
    // v6.2/v6.3 曾把 ubatch 提到 128 后崩在 16~21 个 token 处，但当时真正的
    // 凶手是 FP16 KV 溢出（v6.5 修）和 sgemm 断言（v5.2 修）—— 那两个都解决后，
    // 这个限制就是多余的保守。
    //
    // 为什么值得试：prefill 是批量矩阵乘，ubatch 越大算力利用率越高。
    // 实测 prefill 只有 8 tok/s，远低于 CPU 该有的水平（正常应 20~40），
    // 说明批量太小、算力没喂饱。
    cparams.n_batch         = 256;
    cparams.n_ubatch        = 128;

    // v6.5 关键修复：KV cache 改用 FP32。
    // 崩溃现场：
    //   vec.cpp:369 ggml_vec_dot_f16: assertion "!isnan(sumf) && !isinf(sumf)" failed
    //   Fatal signal 6 (SIGABRT) in tid (llama-gen)
    // llama.cpp 默认把 K/V cache 存成 FP16，而 FP16 上限只有 65504。
    // 序列一长，注意力分数累积就溢出成 Inf，再算下去变 NaN，断言直接把进程杀掉。
    // 这正是为什么总在生成到 40~50 个 token 时崩：短问答碰不到，长回答必中。
    // 改成 FP32 后精度足够，代价是 KV cache 内存翻倍（2048 上下文约多 35MB），
    // 相比 5GB 可用内存完全无感。
    cparams.type_k          = GGML_TYPE_F32;
    cparams.type_v          = GGML_TYPE_F32;
    cparams.n_threads       = (uint32_t) (n_threads > 0 ? n_threads : 4);
    cparams.n_threads_batch = cparams.n_threads;
    // 主线版本该字段名/枚举可能不同，用默认值（AUTO）即可
    // cparams.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_DISABLED;

    llama_context* ctx = llama_init_from_model(model, cparams);
    if (!ctx) {
        llama_model_free(model);
        if (g_lastError.empty()) g_lastError = "上下文创建失败（内存不足，试试把上下文长度调小）";
        return fail("创建上下文");
    }

    // ★ v10.7 关键修复：线程数必须**显式设置**。
    //
    // 新版 llama.cpp 已经弃用 llama_context_params.n_threads 这个字段 ——
    // 只往 cparams 里填是没用的，必须在 init 之后调 llama_set_n_threads()。
    // 不调的话线程数退到库内默认值，于是：
    //     用户设 1 线程、设 8 线程，跑出来速度一模一样。
    // 之前把这现象误判成"内存带宽瓶颈"，其实是并行压根没开。
    const uint32_t nth = cparams.n_threads > 0 ? cparams.n_threads : 4;
    llama_set_n_threads(ctx, nth, nth);

    auto* st = new LlamaState{model, ctx};
    log_line("上下文就绪 n_ctx=%u n_batch=%u n_ubatch=%u 请求线程=%u 实际生效=%d，可用内存 %ld MB",
             cparams.n_ctx, cparams.n_batch, cparams.n_ubatch, nth,
             llama_n_threads(ctx), mem_available_mb());
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
        jlong handle, jstring jsysPrefix, jstring jprompt,
        jfloat temp, jint top_k, jfloat top_p, jfloat rep_pen, jint max_tokens) {

    auto* st = reinterpret_cast<LlamaState*>(handle);
    if (!st || !st->ctx || !st->model) return env->NewStringUTF("[模型未初始化]");

    // 这个函数跑在 Kotlin 侧的 "llama-gen" 线程，必须在本线程装 altstack，
    // 否则该线程上的 SIGSEGV 会因为拿不到备用栈而被内核直接杀进程（v1.13 修复）
    install_altstack_here();

    g_lastError.clear();
    set_stage("tokenize");
    g_utf8Pending.clear();   // 每轮重新累积，避免上一轮残留的半个字符串进来
    log_line("----- generate 开始，可用内存 %ld MB -----", mem_available_mb());

    // v6.2：再打一次 CPU 指令集能力。
    // 原来只在 init 时打，会被后续大量日志挤到列表最前面，界面只看最后 30 行，
    // 用户根本截不到。这里每次生成前打一行，保证可见。
    {
        std::string feat;
        FILE* f = fopen("/proc/cpuinfo", "r");
        if (f) {
            char line[4096];
            while (fgets(line, sizeof(line), f)) {
                if (strncmp(line, "Features", 8) == 0) { feat = line; break; }
            }
            fclose(f);
        }
        auto has = [&](const char* k) { return feat.find(k) != std::string::npos; };
        log_line("CPU: dotprod=%d i8mm=%d sve=%d fp16=%d | %s",
                 has("asimddp") ? 1 : 0, has("i8mm") ? 1 : 0,
                 has("sve") ? 1 : 0, has("asimdhp") ? 1 : 0,
                 feat.empty() ? "(读不到)" : feat.c_str());
    }

    const char* cpre = env->GetStringUTFChars(jsysPrefix, nullptr);
    std::string sysPrefix(cpre ? cpre : "");
    env->ReleaseStringUTFChars(jsysPrefix, cpre);

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
    int n_past_base = 0;   // 前缀缓存已占用的 token 数（KV 段会赋值）
    int room = n_ctx - 8;
    if (n_tok > room) {
        n_tok = room;
        if (n_tok <= 0) return env->NewStringUTF("[上下文太小]");
        tokens.resize((size_t) n_tok);
    }

    int budget = max_tokens > 0 ? max_tokens : 2048;
    int left = n_ctx - n_tok - n_past_base - 1;
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
    // v4.3 采样器链改成与官方 common/sampling 一致的顺序：
    //   惩罚 → top_k → temp → top_p → dist
    // 之前是 top_p 在 temp 前面，等于在"还没温度化"的分布上做截断，
    // 高温时截错、低温时又过窄，输出会显得又呆又不稳定。
    if (top_k > 0) llama_sampler_chain_add(smpl, llama_sampler_init_top_k(top_k));
    if (temp > 0.0001f) llama_sampler_chain_add(smpl, llama_sampler_init_temp(temp));
    if (top_p > 0.0f && top_p < 0.999f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_top_p(top_p, 1));
    }
    // v4.3：随机种子。
    // 原来用 LLAMA_DEFAULT_SEED（固定值），同样的问句永远得到逐字相同的回答，
    // 用起来像复读机。这里改成真正随机的种子。
    {
        static std::mt19937 rng{ std::random_device{}() };
        uint32_t seed = (uint32_t) (rng() ^ (uint32_t) std::chrono::steady_clock::now().time_since_epoch().count());
        llama_sampler_chain_add(smpl, llama_sampler_init_dist(seed));
    }

    // ---- v8.0：KV 前缀缓存（"汤底复用"）----
    //
    // 背景：system prompt 每轮完全相同，实测占单轮 15 秒里的 14 秒。
    // 既然算过一次，就把它的 KV 状态存下来，之后直接恢复，
    // 只处理用户那一小段 —— 这是唯一能让 8B 在手机上"秒回"的办法。
    //
    // 两种模式：
    //   sysPrefix 非空 → 缓存模式（Agent 页用）：前缀算一次，之后恢复
    //   sysPrefix 为空 → 旧行为（对话页用）：整段 prompt 一起 prefill
    set_stage("kv");
    const bool useCache = !sysPrefix.empty();
    {
        llama_memory_t mem = llama_get_memory(st->ctx);
        if (!useCache) {
            if (mem) llama_memory_clear(mem, true);
            log_line("已清空 KV cache（整段 prompt 一起处理）");
        } else if (st->sysKv.empty() || st->sysText != sysPrefix) {
            // 首次或前缀变了：算一遍并存快照
            if (mem) llama_memory_clear(mem, true);

            int needP = llama_tokenize(vocab, sysPrefix.c_str(), (int) sysPrefix.size(),
                                       nullptr, 0, true, true);
            if (needP < 0) needP = -needP;
            std::vector<llama_token> pre((size_t) needP + 8);
            int nPre = llama_tokenize(vocab, sysPrefix.c_str(), (int) sysPrefix.size(),
                                      pre.data(), (int) pre.size(), true, true);
            if (nPre <= 0) {
                log_line("!! system 前缀 tokenize 失败 %d", nPre);
            } else {
                pre.resize((size_t) nPre);
                auto tp = std::chrono::steady_clock::now();
                const int64_t nb = (int64_t) llama_n_batch(st->ctx);
                int done = 0;
                int rc = 0;
                while (done < nPre) {
                    int chunk = (int) std::min<int64_t>(nPre - done, nb);
                    llama_batch pb = llama_batch_get_one(pre.data() + done, chunk);
                    rc = llama_decode(st->ctx, pb);
                    if (rc != 0) break;
                    done += chunk;
                }
                double psec = std::chrono::duration<double>(
                        std::chrono::steady_clock::now() - tp).count();
                if (rc != 0) {
                    log_line("[失败] 前缀 prefill ret=%d", rc);
                } else {
                    size_t sz = llama_state_seq_get_size(st->ctx, 0);
                    st->sysKv.resize(sz);
                    size_t got = llama_state_seq_get_data(st->ctx, st->sysKv.data(), sz, 0);
                    st->sysText   = sysPrefix;
                    st->sysTokLen = nPre;
                    log_line("建立前缀缓存：%d token / %.2f 秒 / %.1f MB（只这一次）",
                             nPre, psec, got / 1048576.0);
                }
            }
        } else {
            // 已有快照：清空后恢复，跳过前缀计算
            if (mem) llama_memory_clear(mem, true);
            auto tr = std::chrono::steady_clock::now();
            size_t r = llama_state_seq_set_data(st->ctx, st->sysKv.data(), st->sysKv.size(), 0);
            double rsec = std::chrono::duration<double>(
                    std::chrono::steady_clock::now() - tr).count();
            if (r == 0) {
                log_line("!! 前缀快照恢复失败，本轮退回完整计算");
                st->sysKv.clear();
                if (mem) llama_memory_clear(mem, true);
            } else {
                log_line("恢复前缀缓存：%d token / %.3f 秒（省掉一次完整 prefill）",
                         st->sysTokLen, rsec);
            }
        }
        n_past_base = st->sysTokLen > 0 && !st->sysKv.empty() ? st->sysTokLen : 0;
        if (useCache && st->sysKv.empty()) n_past_base = 0;
    }

    // 前缀占了 n_past_base 个位置，前面那次 budget 计算是在"前缀为 0"的前提下做的，
    // 这里按真实占用重新校准，避免生成到一半撞上上下文上限被截断。
    {
        int left2 = n_ctx - n_tok - n_past_base - 1;
        if (budget > left2) budget = left2;
        if (budget <= 0) {
            llama_sampler_free(smpl);
            g_lastError = "上下文不够（前缀 " + std::to_string(n_past_base) +
                          " + 输入 " + std::to_string(n_tok) + " 已占满）";
            set_stage("idle");
            return env->NewStringUTF("[上下文不够]");
        }
    }

    // ---- v4.4 关键修复：分块 prefill，把 prompt 全部喂进去 ----
    // 原来的写法是 "if (use_tok > 128) use_tok = 128"，等于把超过 128 token 的部分直接扔掉。
    // 而扔掉的恰好是 prompt 末尾 —— 也就是用户的问题和 "<|im_start|>assistant" 标记。
    // 模型根本看不到问题，只能瞎编（实测编出了中学习作里的人物"李华"）。
    // 短句（<128 token）完全看不出问题，一带上历史会立刻暴露。
    // 正确做法：每块不超过 n_batch，逐块 decode，位置由 context 自动续接。
    set_stage("prefill");
    log_line("prefill 开始，n_tok=%d", n_tok);
    {
        const int64_t n_batch_max = (int64_t) llama_n_batch(st->ctx);
        int done = 0;
        int n_chunk = 0;
        auto tc0 = std::chrono::steady_clock::now();
        while (done < n_tok) {
            int chunk = (int) std::min<int64_t>(n_tok - done, n_batch_max);
            llama_batch cb = llama_batch_get_one(tokens.data() + done, chunk);
            int rc = llama_decode(st->ctx, cb);
            n_chunk++;
            if (rc != 0) {
                llama_sampler_free(smpl);
                g_lastError = "prefill 第 " + std::to_string(n_chunk) + " 块失败，ret=" + std::to_string(rc);
                log_line("[失败] prefill 第 %d 块(共 %d token) ret=%d", n_chunk, chunk, rc);
                set_stage("idle");
                return env->NewStringUTF("[prefill 失败]");
            }
            done += chunk;
        }
        double csec = std::chrono::duration<double>(std::chrono::steady_clock::now() - tc0).count();
        log_line("prefill 完成：%d token / %d 块 / %.2f 秒（%.1f tok/s）",
                 n_tok, n_chunk, csec, csec > 0 ? n_tok / csec : 0.0);
    }
    log_line("prefill 结束后可用内存 %ld MB", mem_available_mb());

    // v3.4 诊断：prefill 之后 logits 到底有没有算出来。
    // 如果这里是 NULL，说明根本没产出下一个 token 的概率分布 —— 后面采样出什么都是垃圾。
    {
        float* lg = llama_get_logits_ith(st->ctx, -1);
        if (!lg) {
            log_line("!! prefill 后 logits 为 NULL（没有输出层结果）");
        } else {
            int best = 0; float bv = lg[0];
            for (int v = 1; v < n_vocab; v++) { if (lg[v] > bv) { bv = lg[v]; best = v; } }
            char pb[64] = {0};
            int pl = llama_token_to_piece(vocab, best, pb, (int32_t) sizeof(pb) - 1, 0, false);
            log_line("prefill 后 argmax id=%d logit=%.3f piece=[%.*s]", best, bv,
                     pl > 0 ? pl : 0, pl > 0 ? pb : "");
        }
    }

    // ---- 生成 ----
    set_stage("generate");
    std::string full;
    int n_past = n_past_base + n_tok;
    char buf[512];

    auto tg0 = std::chrono::steady_clock::now();
    int n_gen = 0;

    for (int i = 0; i < budget; i++) {
        // v6.6：把当前 token 序号写进 stage。
        // 崩溃时信号处理器会带上 stage，等于告诉我们精确崩在生成的第几个 token。
        {
            char sb[32];
            snprintf(sb, sizeof(sb), "gen:%d", i);
            set_stage(sb);
        }
        if (i % 4 == 0) {
            log_line("生成中… 第 %d 个 token，可用内存 %ld MB", i, mem_available_mb());
        }
        llama_token next = llama_sampler_sample(smpl, st->ctx, -1);

        // v3.4 诊断：前 6 个 token 全部记下来，看清是"结束符"还是"空 piece"
        if (i < 6) {
            int is_eog = llama_vocab_is_eog(vocab, next);
            char db[64] = {0};
            int dl = llama_token_to_piece(vocab, next, db, (int32_t) sizeof(db) - 1, 0, false);
            log_line("token[%d] id=%d eog=%d piece_len=%d piece=[%.*s]",
                     i, next, is_eog, dl, dl > 0 ? dl : 0, dl > 0 ? db : "");
        }

        if (next < 0) { log_line("!! 采样返回负数 id=%d，中止", next); break; }
        if (llama_vocab_is_eog(vocab, next)) {
            log_line("!! 第 %d 个 token 遇到结束符 id=%d，本次共生成 %zu 字节", i, next, full.size());
            break;
        }

        int len = llama_token_to_piece(vocab, next, buf, (int32_t) sizeof(buf), 0, false);
        if (len > 0) {
            // 累积字节，只把凑完整的 UTF-8 字符交给上层 —— 见 take_complete_utf8 说明
            g_utf8Pending.append(buf, (size_t) len);
            std::string pieceChunk = take_complete_utf8(g_utf8Pending);
            if (!pieceChunk.empty()) {
                full += pieceChunk;
                if (g_cbObj && g_cbMethod) {
                    jstring jp = env->NewStringUTF(pieceChunk.c_str());
                    env->CallVoidMethod(g_cbObj, g_cbMethod, jp);
                    env->DeleteLocalRef(jp);
                }
            }
        }

        llama_batch tb = llama_batch_get_one(&next, 1);
        if (llama_decode(st->ctx, tb) != 0) {
            g_lastError = "decode 失败";
            log_line("[失败] decode 第 %d 个 token", i);
            break;
        }
        n_past++;
        n_gen++;
        if (n_past >= n_ctx - 1) break;
    }

    // v4.2：把真实速度打出来 —— 这是判断"优化到底有没有生效"的唯一硬指标。
    // 0.5B Q4_K_M 在 4 线程的合理区间是 10~20 tok/s；若只有 1~2，说明计算路径没优化。
    {
        double gsec = std::chrono::duration<double>(std::chrono::steady_clock::now() - tg0).count();
        log_line("生成速度 %.2f tok/s（%d token / %.2f 秒），n_threads=%u",
                 gsec > 0 ? n_gen / gsec : 0.0, n_gen, gsec,
                 llama_n_threads(st->ctx));
    }

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
