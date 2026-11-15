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
#include <sched.h>
#include <sched.h>
#include <cstdlib>
#include <dirent.h>
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

// ---------------------------------------------------------------------------
// v10.71：CPU 绑核
//
// 天玑 9300+ 是非对称大核布局（全大核，但仍有快慢两簇）：
//   cpu0-3 = Cortex-A720 @ 2.0GHz
//   cpu4-7 = Cortex-X4   @ 2.85 / 3.4GHz
//
// 4 线程是这台机器上的最优值（1/6/8 都更慢，已实测），但 Android 的 EAS
// 调度器不保证把这 4 个线程放在 X4 上 —— 它优先省电，负载不满时常把人往
// A720 上塞。跑在 2.0GHz 的核心上单核差 40% 以上，decode 直接掉一截。
//
// 这里读 cpufreq_max 自己判断哪几个核最快，把当前线程的 affinity 钉过去；
// llama.cpp 的 worker 是这条线程 fork 的，会继承 affinity mask。
//
// 注意：Android 的 cgroup/cpuset 可能覆盖 affinity（后台会被限核），
// 所以只报结果，不当成必然成功。
// ---------------------------------------------------------------------------
static void pin_to_fastest_cpus(int n) {
    if (n <= 0) return;
    long ncpu = sysconf(_SC_NPROCESSORS_ONLN);
    if (ncpu <= 0 || ncpu > 64) {
        log_line("[绑核] 核数异常 %ld，跳过", ncpu);
        return;
    }

    struct Core { long khz; int id; };
    std::vector<Core> cores;
    for (int i = 0; i < (int) ncpu; ++i) {
        char path[160];
        snprintf(path, sizeof(path),
                 "/sys/devices/system/cpu/cpu%d/cpufreq/cpuinfo_max_freq", i);
        FILE* f = fopen(path, "r");
        if (!f) continue;
        long khz = 0;
        if (fscanf(f, "%ld", &khz) != 1) khz = 0;
        fclose(f);
        cores.push_back({khz, i});
    }
    if (cores.empty()) {
        log_line("[绑核] 读不到 cpufreq 表（无权限或无此节点），跳过");
        return;
    }

    std::sort(cores.begin(), cores.end(), [](const Core& a, const Core& b) {
        if (a.khz != b.khz) return a.khz > b.khz;
        return a.id < b.id;
    });

    cpu_set_t set;
    CPU_ZERO(&set);
    std::string picked;
    for (int i = 0; i < (int) cores.size() && i < n; ++i) {
        CPU_SET(cores[i].id, &set);
        char buf[48];
        snprintf(buf, sizeof(buf), "%s%d@%.2fG", picked.empty() ? "" : ",",
                 cores[i].id, (double) cores[i].khz / 1000000.0);
        picked += buf;
    }

    if (sched_setaffinity(0, sizeof(set), &set) != 0) {
        log_line("[绑核] 失败 errno=%d（可能被 cgroup 限核），退回系统调度", errno);
        return;
    }

    cpu_set_t got;
    CPU_ZERO(&got);
    if (sched_getaffinity(0, sizeof(got), &got) == 0) {
        std::string actual;
        for (int i = 0; i < (int) ncpu; ++i) {
            if (CPU_ISSET(i, &got)) {
                if (!actual.empty()) actual += ",";
                actual += std::to_string(i);
            }
        }
        log_line("[绑核] 成功 目标=%s 生效=%s", picked.c_str(), actual.c_str());
    } else {
        log_line("[绑核] 成功 目标=%s", picked.c_str());
    }
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

// ============================================================================
// v10.72：投机解码（speculative decoding）
//
// 手机端推理是**内存带宽瓶颈**：生成一个 token 要用好几 GB/s 的带宽把权重读一遍。
// 投机解码不改模型、不改量化，而是「一次读权重，验多个 token」：
//   1) 小的草稿模型（0.6B）先自回归猜 γ 个 token —— 它几乎不占带宽；
//   2) 主模型（4B）把这些候选**一次性** batch 前向，只读一遍权重；
//   3) 逐个比对主模型自己的采样结果，猜对的全部采纳，第一个不对的用主模型的值替换。
//
// 关键点：每个 token 都是**主模型按自己的分布采样**出来的，所以输出质量与
// 不开投机时等价（不是拿草稿模型的输出凑数）。
//
// 前提：草稿模型和主模型必须共用同一套词表（Qwen3 全系 151936，天然满足），
// 否则 token id 不能互用。initDraft 里会校验，不一致直接拒绝。
// ============================================================================
struct DraftCtx {
    llama_model*   model = nullptr;
    llama_context* ctx   = nullptr;
    llama_sampler* smpl  = nullptr;

    // draft 侧已吃进的完整 token 序列。每块开始前与主模型对齐：
    // 求公共前缀 → 截断 KV → 补算新增。主模型拒绝候选后，这里会自动回退。
    std::vector<llama_token> seq;
    int  n_past    = 0;
    int  max_draft = 6;      // γ：每块猜几个
    long n_prop    = 0;      // 累计候选数
    long n_acc     = 0;      // 累计被采纳数
};

struct LlamaState {
    llama_model*   model;
    llama_context* ctx;

    // v10.72：草稿模型（可为空，为空时走原来的单 token 路径）
    DraftCtx* draft = nullptr;

    // v8.0 KV 前缀缓存（"汤底复用"）：
    // system prompt 每轮完全相同，却要每轮重新 prefill —— 实测占单轮 15 秒里的 14 秒。
    // 这里把它的 KV 状态算一次存下来，之后每轮直接恢复，只需处理用户那一小段。
    std::string          sysText;    // 旧方案遗留，不再使用
    std::vector<uint8_t> sysKv;      // 旧方案遗留，不再使用
    int                  sysTokLen = 0;

    // v10.14 整段序列续接：记住上一轮的完整 token 序列，
    // 下轮求最长公共前缀，KV 截断到该点后只算新增部分。
    // 不拷贝任何 KV —— 内存零增加。
    std::vector<llama_token> lastSeq;
    int                      lastNPast = 0;
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

// ---------------------------------------------------------------------------
// v10.72 投机解码：草稿侧工具
// ---------------------------------------------------------------------------

// 建一条与主模型口径一致的采样器链（惩罚 → top_k → temp → top_p → dist）。
// 口径必须一致，否则草稿猜出来的分布和主模型差太远，接受率会掉到个位数。
static llama_sampler* make_sampler(int n_vocab, float temp, int top_k, float top_p,
                                   float rep_pen, uint32_t seed) {
    llama_sampler* s = llama_sampler_chain_init(llama_sampler_chain_default_params());
    if (!s) return nullptr;
    if (rep_pen > 1.0001f) {
        llama_sampler_chain_add(s, llama_sampler_init_penalties(n_vocab, 64, rep_pen, 0.0f, 0.0f));
    }
    if (top_k > 0) llama_sampler_chain_add(s, llama_sampler_init_top_k(top_k));
    if (temp > 0.0001f) llama_sampler_chain_add(s, llama_sampler_init_temp(temp));
    if (top_p > 0.0f && top_p < 0.999f) {
        llama_sampler_chain_add(s, llama_sampler_init_top_p(top_p, 1));
    }
    llama_sampler_chain_add(s, llama_sampler_init_dist(seed));
    return s;
}

// 主模型单 token 前向（显式 pos）。
// 原代码用的是 llama_batch_get_one()，位置靠 ctx 内部 n_past 推断 ——
// 投机时必须能精确回退 KV，内部计数不可信，一律显式给 pos。
static bool decode_one(llama_context* ctx, llama_token tok, int pos) {
    if (!ctx || tok < 0) return false;
    llama_batch b = llama_batch_init(1, 0, 1);
    b.token[0]     = tok;
    b.pos[0]       = (llama_pos) pos;
    b.n_seq_id[0]  = 1;
    b.seq_id[0][0] = 0;
    b.logits[0]    = 1;
    b.n_tokens     = 1;
    int rc = llama_decode(ctx, b);
    llama_batch_free(b);
    return rc == 0;
}

// 把草稿模型的 KV 对齐到 target 序列：公共前缀之后的全部丢掉重算。
// 主模型一旦拒绝某个候选，这里就会自动把草稿侧多走的那几步回退掉。
static bool draft_sync(DraftCtx* d, const std::vector<llama_token>& target) {
    if (!d || !d->ctx) return false;
    size_t L = 0;
    const size_t lim = std::min(target.size(), d->seq.size());
    while (L < lim && target[L] == d->seq[L]) L++;

    llama_memory_t mem = llama_get_memory(d->ctx);
    if (mem) llama_memory_seq_rm(mem, 0, (llama_pos) L, -1);

    int i = (int) L;
    const int total = (int) target.size();
    while (i < total) {
        int chunk = std::min(total - i, 64);
        llama_batch b = llama_batch_init(chunk, 0, 1);
        for (int k = 0; k < chunk; k++) {
            b.token[k]     = target[(size_t) (i + k)];
            b.pos[k]       = (llama_pos) (i + k);
            b.n_seq_id[k]  = 1;
            b.seq_id[k][0] = 0;
            b.logits[k]    = ((i + k) == total - 1) ? 1 : 0;
        }
        b.n_tokens = chunk;
        int rc = llama_decode(d->ctx, b);
        llama_batch_free(b);
        if (rc != 0) return false;
        i += chunk;
    }
    d->seq    = target;
    d->n_past = total;
    return true;
}

// 草稿模型采样一个 token 并推进（pos 由调用方指定，与主模型严格同位置）
static llama_token draft_step(DraftCtx* d, int pos) {
    if (!d || !d->ctx || !d->smpl) return -1;
    llama_token t = llama_sampler_sample(d->smpl, d->ctx, -1);
    if (t < 0) return -1;
    llama_batch b = llama_batch_init(1, 0, 1);
    b.token[0]     = t;
    b.pos[0]       = (llama_pos) pos;
    b.n_seq_id[0]  = 1;
    b.seq_id[0][0] = 0;
    b.logits[0]    = 1;
    b.n_tokens     = 1;
    int rc = llama_decode(d->ctx, b);
    llama_batch_free(b);
    if (rc != 0) return -1;
    d->seq.push_back(t);
    d->n_past = pos + 1;
    return t;
}

static void draft_free(DraftCtx* d) {
    if (!d) return;
    if (d->smpl)  llama_sampler_free(d->smpl);
    if (d->ctx)   llama_free(d->ctx);
    if (d->model) llama_model_free(d->model);
    delete d;
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
        // v10.76：日志统一放 files/logs/，别再散在 files 根目录跟模型目录打架
        std::string logDir = dir + "/logs";
        ::mkdir(logDir.c_str(), 0700);
        std::string lf = logDir + "/run_log.txt";
        std::string cf = logDir + "/last_crash.txt";
        std::string ef = logDir + "/native_stderr.txt";
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

    // v10.71：绑到最快的那几个核。
    // 必须在 llama model / context 创建之前调用 —— worker 线程是之后才 fork 的，
    // 只有先设好 affinity，它们才会继承这个 mask。
    pin_to_fastest_cpus(n_threads > 0 ? n_threads : 4);
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

    // v10.19：mmap 相关字段已从这个版本的 llama_model_params 里移除
    // （编译报错 "no member named 'use_mmap'"）。
    // 也就是说 v10.17 那次"关 mmap"的改动不但无效，连字段都不存在了。
    // 新版 llama.cpp 内部自己管理权重的加载方式，外部无法切换。

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
    // v10.18：ubatch 128 → 512。
    // 实测对照：llama.cpp 里 pp1024 从 ubatch=128 到 512 能提升约 74%。
    // 我们的 prefill 只有 8 tok/s（正常该是 decode 的 4~5 倍），说明批量没吃满。
    // 前提是模型有 GEMM 路径 —— Q4_K_M 有（repack + i8mm），三值 Q2_0 没有。
    // 所以这个改动对 Qwen3-4B 有收益，对老的 8B 三值没有。
    cparams.n_batch         = 512;
    cparams.n_ubatch        = 512;

    // v6.5 关键修复：KV cache 改用 FP32。
    // 崩溃现场：
    //   vec.cpp:369 ggml_vec_dot_f16: assertion "!isnan(sumf) && !isinf(sumf)" failed
    //   Fatal signal 6 (SIGABRT) in tid (llama-gen)
    // llama.cpp 默认把 K/V cache 存成 FP16，而 FP16 上限只有 65504。
    // 序列一长，注意力分数累积就溢出成 Inf，再算下去变 NaN，断言直接把进程杀掉。
    // 这正是为什么总在生成到 40~50 个 token 时崩：短问答碰不到，长回答必中。
    // 改成 FP32 后精度足够，代价是 KV cache 内存翻倍（2048 上下文约多 35MB），
    // 相比 5GB 可用内存完全无感。
    // v10.14：KV cache 从 FP32 改成 Q8_0。
    // FP32 是被逼的 —— 当年 FP16 会因为浮点溢出成 Inf/NaN 触发 vec_dot 断言崩溃。
    // Q8_0 是整数+缩放，压根没有这个失效模式，内存还省一半（880MB → 440MB），
    // 上下文长的时候读得少、生成也快一点。
    // 万一出问题，把这两行换回 GGML_TYPE_F32 即可。
    cparams.type_k          = GGML_TYPE_Q8_0;
    cparams.type_v          = GGML_TYPE_Q8_0;
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

// ---- v10.72 加载草稿模型 ----
// 传空串或 "none" 表示不启用投机解码（走原来的单 token 路径）。
// 返回 false 不代表主模型不可用 —— 只是这一路加速没开成，日志里有原因。
extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_bonsaiagent_llm_Llama_initDraft(
        JNIEnv* env, jobject, jlong handle, jstring jpath, jint n_threads, jint max_draft) {

    auto* st = reinterpret_cast<LlamaState*>(handle);
    if (!st || !st->ctx || !st->model) {
        g_lastError = "主模型未加载，无法挂草稿模型";
        return JNI_FALSE;
    }

    if (st->draft) { draft_free(st->draft); st->draft = nullptr; }

    const char* path = env->GetStringUTFChars(jpath, nullptr);
    std::string spath(path ? path : "");
    env->ReleaseStringUTFChars(jpath, path);
    if (spath.empty() || spath == "none") {
        log_line("草稿模型：未启用（走单 token 路径）");
        return JNI_TRUE;
    }

    struct stat fst{};
    if (::stat(spath.c_str(), &fst) != 0 || fst.st_size < 1024 * 1024) {
        g_lastError = "草稿模型不存在或文件不完整: " + spath;
        return JNI_FALSE;
    }

    // v10.73：门槛从 700MB 提到 2.5GB。
    // 日志 058 的教训：草稿挂上后系统可用只剩 2278MB，Android 立刻把 mmap 的
    // 主模型权重从页缓存里踢掉 —— 于是每生成一个 token 都要从闪存重读 2.3GB，
    // prefill 从 30+ 掉到 10.8 tok/s，整体比不开还慢 6 倍。
    // 宁可不开加速，也不能让主模型变慢。
    long mem = mem_available_mb();
    if (mem > 0 && mem < 2500) {
        char b[220];
        snprintf(b, sizeof(b),
                 "可用内存只剩 %ld MB（开加速至少要 2500 MB）——先关掉后台应用再试。\n"
                 "原因：内存不够时系统会把主模型权重踢出缓存，开了反而更慢。", mem);
        g_lastError = b;
        return JNI_FALSE;
    }

    llama_model_params dp = llama_model_default_params();
    dp.n_gpu_layers = 0;
    llama_model* dm = llama_model_load_from_file(spath.c_str(), dp);
    if (!dm) {
        g_lastError = "草稿模型加载失败（不是有效的 gguf？）";
        return JNI_FALSE;
    }

    // 词表必须完全一致，否则 token id 不能互用，投机出来的全是乱码
    const llama_vocab* v1 = llama_model_get_vocab(st->model);
    const llama_vocab* v2 = llama_model_get_vocab(dm);
    const int nv1 = v1 ? llama_vocab_n_tokens(v1) : 0;
    const int nv2 = v2 ? llama_vocab_n_tokens(v2) : 0;
    if (nv1 != nv2) {
        g_lastError = "词表不一致（主 " + std::to_string(nv1) + " / 草稿 " +
                      std::to_string(nv2) + "），这两个模型不能配对";
        llama_model_free(dm);
        return JNI_FALSE;
    }

    llama_context_params dc = llama_context_default_params();
    dc.n_ctx           = (uint32_t) llama_n_ctx(st->ctx);
    dc.n_batch         = 64;
    dc.n_ubatch        = 64;
    dc.type_k          = GGML_TYPE_Q8_0;
    dc.type_v          = GGML_TYPE_Q8_0;
    const int nth      = n_threads > 0 ? (n_threads > 4 ? 4 : n_threads) : 4;
    dc.n_threads       = (uint32_t) nth;
    dc.n_threads_batch = (uint32_t) nth;

    llama_context* dctx = llama_init_from_model(dm, dc);
    if (!dctx) {
        g_lastError = "草稿上下文创建失败（内存不足，先关后台应用）";
        llama_model_free(dm);
        return JNI_FALSE;
    }
    llama_set_n_threads(dctx, (uint32_t) nth, (uint32_t) nth);

    auto* d = new DraftCtx();
    d->model        = dm;
    d->ctx          = dctx;
    d->max_draft    = max_draft > 0 ? (max_draft > 12 ? 12 : max_draft) : 6;
    st->draft       = d;

    log_line("草稿模型就绪：%s", spath.c_str());
    log_line("  投机参数 γ=%d 词表=%d n_ctx=%u 线程=%d，可用内存 %ld MB",
             d->max_draft, nv1, dc.n_ctx, nth, mem_available_mb());
    return JNI_TRUE;
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

    // 绑大核：必须在生成线程里绑（这个函数就运行在 llama-gen 线程上）
    // v10.10：绑核已移除 —— 实测把全部线程钉到 4 个大核反而更慢，
    // 46 个线程（计算 + UI + GC + 渲染）全挤在 4 个核上互相抢，
    // 生成从 4.48 掉到 1.74 tok/s。负优化，撤掉。

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

    // v10.14：把 sysPrefix 和 prompt 拼成一个完整序列统一 tokenize ——
    // 续接判断必须是"完整序列的公共前缀"，拆开处理对不上位置。
    std::string fullPrompt = sysPrefix + prompt;
    int need = llama_tokenize(vocab, fullPrompt.c_str(), (int) fullPrompt.size(),
                              nullptr, 0, true, true);
    if (need == 0) return env->NewStringUTF("");
    if (need < 0) need = -need;

    std::vector<llama_token> tokens((size_t) need + 8);
    int n_tok = llama_tokenize(vocab, fullPrompt.c_str(), (int) fullPrompt.size(),
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

    // ---- v10.14：整段序列续接 ----
    //
    // 旧方案（v8.0）只缓存 sysPrefix 的 KV 快照，而且要拷一份（几百 MB）。
    // 但实测真正贵的是**历史**：第二轮把上一轮的问答整个重新 prefill，
    // prefill 从 8 秒涨到 15 秒，聊十轮就直接撞上下文上限。
    //
    // 新方案：把 sysPrefix + prompt 当成一个完整序列，
    // 和上一轮的序列求最长公共前缀 L，把 KV 截断到 L，只算 L 之后的新增部分。
    // 不拷贝 KV（就地复用），内存零增加；历史越长省得越多。
    set_stage("kv");
    n_past_base = 0;
    int kvReuse = 0;
    {
        llama_memory_t mem = llama_get_memory(st->ctx);

        size_t L = 0;
        if (!st->lastSeq.empty() && st->lastNPast > 0) {
            const size_t lim = std::min(tokens.size(), st->lastSeq.size());
            while (L < lim && tokens[L] == st->lastSeq[L]) L++;
        }

        if (L == 0 || mem == nullptr) {
            if (mem) llama_memory_clear(mem, true);
            log_line("KV 无可用前缀，全量 prefill %d token", n_tok);
        } else {
            // 截掉 L 之后的 KV，只保留可复用的那段
            llama_memory_seq_rm(mem, 0, (llama_pos) L, -1);
            log_line("KV 续接：复用 %zu token，只需再算 %d token（省下 %d）",
                     L, n_tok - (int) L, (int) L);
        }
        kvReuse = (int) L;   // 前缀已算进 tokens 里，n_past_base 保持 0
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
    log_line("prefill 开始，n_tok=%d（其中 %d 个已在 KV 里）", n_tok, kvReuse);
    {
        const int64_t n_batch_max = (int64_t) llama_n_batch(st->ctx);
        int done = kvReuse;
        int n_chunk = 0;
        auto tc0 = std::chrono::steady_clock::now();
        while (done < n_tok) {
            int chunk = (int) std::min<int64_t>(n_tok - done, n_batch_max);
            // v10.14：显式设置 pos。
            // llama_batch_get_one 不带位置，靠 context 内部 n_past 推断；
            // 而我们在上面刚截断过 KV，一旦推断错就会从 0 覆盖开头，输出全乱。
            // 这里逐个 token 写明 pos = done + i，绝对可靠。
            llama_batch cb = llama_batch_init(chunk, 0, 1);
            for (int k = 0; k < chunk; k++) {
                cb.token[k]    = tokens[(size_t)(done + k)];
                cb.pos[k]      = (llama_pos)(done + k);
                cb.n_seq_id[k] = 1;
                cb.seq_id[k][0] = 0;
                cb.logits[k]   = ((done + k) == n_tok - 1) ? 1 : 0;
            }
            cb.n_tokens = chunk;
            int rc = llama_decode(st->ctx, cb);
            llama_batch_free(cb);
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
        int realNew = n_tok - kvReuse;
        log_line("prefill 完成：新增 %d token / %d 块 / %.2f 秒（%.1f tok/s）",
                 realNew, n_chunk, csec, csec > 0 ? realNew / csec : 0.0);
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

    // ========================= v10.72 投机解码 =========================
    // 草稿模型每轮重建采样器（种子随机），否则每轮都会猜成一样的词。
    DraftCtx* draft = st->draft;
    bool useDraft = (draft && draft->ctx && draft->model && draft->max_draft > 0);
    if (useDraft) {
        if (draft->smpl) { llama_sampler_free(draft->smpl); draft->smpl = nullptr; }
        static std::mt19937 drng{ std::random_device{}() };
        uint32_t dseed = (uint32_t) (drng() ^ (uint32_t) std::chrono::steady_clock::now()
                                     .time_since_epoch().count());
        draft->smpl = make_sampler(n_vocab, temp, top_k, top_p, rep_pen, dseed);
        if (!draft->smpl) {
            useDraft = false;
        } else {
            // 草稿侧 KV 对齐到 prompt（第二轮起前缀基本全命中，只补几个 token）
            std::vector<llama_token> pre(tokens.begin(), tokens.end());
            if (!draft_sync(draft, pre)) {
                log_line("草稿 KV 对齐失败，本次退回单 token 路径");
                useDraft = false;
            }
        }
    }
    if (useDraft) {
        log_line("投机解码：开启 γ=%d（草稿模型已就绪）", draft->max_draft);
    } else {
        log_line("投机解码：关闭（没挂草稿模型或初始化失败）");
    }

    auto tg0 = std::chrono::steady_clock::now();
    int n_gen = 0;
    std::vector<llama_token> genToks;   // v10.14：记下生成内容，供下一轮续接判断

    // 输出一个 token：转 UTF-8、喂回调、记进 genToks。返回 1 表示要中断。
    auto emit = [&](llama_token next, int idx) -> int {
        if (next < 0) { log_line("!! 采样返回负数 id=%d，中止", next); return 1; }
        if (llama_vocab_is_eog(vocab, next)) {
            log_line("!! 第 %d 个 token 遇到结束符 id=%d，本次共生成 %zu 字节", idx, next, full.size());
            return 1;
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
        genToks.push_back(next);
        return 0;
    };

    llama_token pending = -1;               // 已采样、还没送进 KV 的 token（下一块的头一个）
    std::vector<llama_token> cand;          // 本块草稿候选
    std::vector<llama_token> dtarget;       // 草稿侧对齐目标（复用容器，少分配）

    // v10.74 自适应 γ：
    // 手机上每验证一个位置，主模型就要多算一次 15 万词表的输出层（约 20ms），
    // γ=8 时这一项能吃掉 35% 的时间。所以 γ 不是越大越好 —— 要让它在
    // 「猜得中」和「别白算」之间自己找平衡，而不是固定值。
    int gammaNow = useDraft ? draft->max_draft : 0;
    // v10.75：连续几轮一个都没猜中。γ 缩到 2 以后如果还是零采纳，
    // 说明这段内容草稿模型完全摸不着头脑 —— 继续投机只会拖慢主模型，
    // 直接关掉本轮的投机，回到 100ms/token 的老路径。
    // 下一次生成会重新开（useDraft 是局部变量）。
    int zeroStreak = 0;

    while (n_gen < budget) {
        // v6.6：把当前 token 序号写进 stage，崩溃时能精确定位
        {
            char sb[32];
            snprintf(sb, sizeof(sb), "gen:%d", n_gen);
            set_stage(sb);
        }
        if (n_gen % 4 == 0) {
            log_line("生成中… 第 %d 个 token，可用内存 %ld MB", n_gen, mem_available_mb());
        }

        // ===== v10.73：没挂草稿时，完全走 v10.70 的老路径 =====
        // 上一版把「无投机」也统一改成了 batch + 显式 pos 的写法，逻辑等价，
        // 但为了排除「是不是我改写循环导致变慢」这个变量，这里原样还原：
        // 单次采样 → llama_batch_get_one → decode，一步不多。
        if (!useDraft) {
            // v10.75：从投机中途切回老路径时，可能还攥着一个「已采样、没解码」的
            // token（pending），必须先把它落地，否则 logits 和 KV 会错位一位。
            if (pending >= 0) {
                if (emit(pending, n_gen) != 0) break;
                n_gen++;
                if (!decode_one(st->ctx, pending, n_past)) {
                    g_lastError = "decode 失败";
                    break;
                }
                n_past++;
                pending = -1;
                if (n_gen >= budget || n_past >= n_ctx - 1) break;
            }
            llama_token next = llama_sampler_sample(smpl, st->ctx, -1);
            if (next < 0) { log_line("!! 采样返回负数 id=%d，中止", next); break; }
            if (emit(next, n_gen) != 0) break;
            n_gen++;
            llama_batch tb = llama_batch_get_one(&next, 1);
            if (llama_decode(st->ctx, tb) != 0) {
                g_lastError = "decode 失败";
                log_line("[失败] decode 第 %d 个 token", n_gen - 1);
                break;
            }
            n_past++;
            if (n_past >= n_ctx - 1) break;
            continue;
        }

        // ① 本块第一个 token：上一块遗留下来的，或现场采样
        llama_token t0;
        if (pending >= 0) { t0 = pending; pending = -1; }
        else               t0 = llama_sampler_sample(smpl, st->ctx, -1);

        if (t0 < 0) { log_line("!! 采样返回负数 id=%d，中止", t0); break; }
        if (llama_vocab_is_eog(vocab, t0)) {
            log_line("!! 第 %d 个 token 是结束符 id=%d，本次共生成 %zu 字节",
                     n_gen, t0, full.size());
            break;
        }

        // ② 草稿模型猜 γ 个（t0 之后）
        cand.clear();
        const int room = budget - n_gen - 1;
        if (useDraft && room > 0) {
            dtarget.assign(tokens.begin(), tokens.end());
            dtarget.insert(dtarget.end(), genToks.begin(), genToks.end());
            dtarget.push_back(t0);
            if (!draft_sync(draft, dtarget)) {
                useDraft = false;
                log_line("草稿 KV 同步失败，本次改走单 token 路径");
            } else {
                // 上下文余量也要算进去：t0 占一个位置，候选不能把 n_ctx 撑爆
                const int space = n_ctx - 1 - (n_past + 1);
                const int want = std::min(std::min(room, gammaNow), std::max(0, space));
                for (int g = 0; g < want; g++) {
                    llama_token c = draft_step(draft, n_past + 1 + g);
                    if (c < 0 || llama_vocab_is_eog(vocab, c)) break;
                    cand.push_back(c);
                }
            }
        }

        // ③ 主模型一次 batch 前向：t0 + 全部候选（只读一遍权重 —— 这就是提速的来源）
        const int bn = 1 + (int) cand.size();
        llama_batch bb = llama_batch_init(bn, 0, 1);
        for (int k = 0; k < bn; k++) {
            bb.token[k]     = (k == 0) ? t0 : cand[(size_t) (k - 1)];
            bb.pos[k]       = (llama_pos) (n_past + k);
            bb.n_seq_id[k]  = 1;
            bb.seq_id[k][0] = 0;
            bb.logits[k]    = 1;
        }
        bb.n_tokens = bn;
        const int rcD = llama_decode(st->ctx, bb);
        llama_batch_free(bb);
        if (rcD != 0) {
            g_lastError = "decode 失败";
            log_line("[失败] batch decode（bn=%d）", bn);
            break;
        }

        // ④ 逐个比对：主模型自己采样出来的值 == 草稿猜的，就采纳
        int accepted = 0;
        llama_token finalTok = -1;
        for (int j = 1; j <= (int) cand.size(); j++) {
            llama_token v = llama_sampler_sample(smpl, st->ctx, j - 1);
            if (v == cand[(size_t) (j - 1)]) accepted++;
            else { finalTok = v; break; }
        }
        if (accepted == (int) cand.size()) {
            // 全中：用最后一个位置的 logits 额外采一个，等于白赚
            finalTok = llama_sampler_sample(smpl, st->ctx, bn - 1);
        }

        // ⑤ KV 回退：只保留 t0 和被采纳的那几个，多算的位置全部删掉
        const int keep_end = n_past + 1 + accepted;
        if (keep_end < n_past + bn) {
            llama_memory_t m = llama_get_memory(st->ctx);
            const bool okRm = (m == nullptr) ? true
                              : llama_memory_seq_rm(m, 0, (llama_pos) keep_end, -1);
            if (!okRm) {
                log_line("!! KV 回退失败（keep_end=%d），停止本次生成以免输出错乱", keep_end);
                break;
            }
        }
        n_past = keep_end;

        // ⑥ 按顺序吐出来（t0 一定是主模型采样的，保证开头正确）
        bool stop = false;
        if (emit(t0, n_gen) != 0) stop = true;
        else {
            n_gen++;
            for (int j = 0; j < accepted; j++) {
                if (emit(cand[(size_t) j], n_gen) != 0) { stop = true; break; }
                n_gen++;
                if (n_gen >= budget) break;
            }
        }
        if (useDraft && !cand.empty()) {
            draft->n_prop += (long) cand.size();
            draft->n_acc  += (long) accepted;
            // 自适应：一半都没猜中就缩，全中才敢放长。
            // 下界 2 —— 只剩 1 个候选时投机毫无意义，开销照付。
            if ((int) cand.size() >= 2) {
                if (accepted * 2 < (int) cand.size()) {
                    if (gammaNow > 2) gammaNow--;
                } else if (accepted == (int) cand.size()) {
                    if (gammaNow < draft->max_draft) gammaNow++;
                }
            }
            // 硬止损：连续 2 块零采纳，且 γ 已经缩到底 → 本轮不再投机。
            // 实测（2026-11-15）：自由生成时接受率会掉到 0，γ=2 全错也有
            // 220ms/token（4.5 tok/s），比不开投机的 10 tok/s 还慢一倍。
            if (accepted == 0) {
                zeroStreak++;
            } else {
                zeroStreak = 0;
            }
            if (zeroStreak >= 2 && gammaNow <= 2) {
                useDraft = false;
                log_line("连续 %d 轮零采纳（接受率太低）→ 本次生成关闭投机，回到单 token 路径",
                         zeroStreak);
            }
        }
        if (stop) break;

        // ⑦ 下一个 token 留着下一块用（它还没进 KV，所以先不吐）
        pending = finalTok;
        if (pending < 0) break;
        if (n_gen >= budget) break;
        if (n_past >= n_ctx - 1) break;
    }

    // v4.2：把真实速度打出来 —— 这是判断"优化到底有没有生效"的唯一硬指标。
    // 0.5B Q4_K_M 在 4 线程的合理区间是 10~20 tok/s；若只有 1~2，说明计算路径没优化。
    {
        double gsec = std::chrono::duration<double>(std::chrono::steady_clock::now() - tg0).count();
        log_line("生成速度 %.2f tok/s（%d token / %.2f 秒），n_threads=%u",
                 gsec > 0 ? n_gen / gsec : 0.0, n_gen, gsec,
                 llama_n_threads(st->ctx));
        // v10.72：投机是否真的在干活，只看这一个数 —— 接受率低于 35% 就说明
        // 草稿模型跟主模型脾气差太多，提速会被自己的开销吃掉。
        if (draft && draft->n_prop > 0) {
            log_line("投机统计：候选 %ld 个，采纳 %ld 个（接受率 %.0f%%），γ 上限 %d → 收敛到 %d",
                     draft->n_prop, draft->n_acc,
                     100.0 * (double) draft->n_acc / (double) draft->n_prop,
                     draft->max_draft, gammaNow);
            if (!useDraft) {
                log_line("   → 本轮中途已关闭投机（连续零采纳，止损）");
            }
        }
    }

    llama_sampler_free(smpl);
    set_stage("idle");

    // v10.14：把这轮的完整序列存下来（prompt + 生成的 token），下一轮据此续接
    {
        st->lastSeq.clear();
        st->lastSeq.reserve(tokens.size() + genToks.size());
        st->lastSeq.insert(st->lastSeq.end(), tokens.begin(), tokens.end());
        st->lastSeq.insert(st->lastSeq.end(), genToks.begin(), genToks.end());
        st->lastNPast = (int) st->lastSeq.size();
        log_line("已记录本轮序列 %zu token，供下一轮续接", st->lastSeq.size());
    }

    log_line("生成结束：%zu 字节 / %d tokens，可用内存 %ld MB",
             full.size(), n_gen, mem_available_mb());
    return env->NewStringUTF(full.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_bonsaiagent_llm_Llama_freeNative(JNIEnv*, jobject, jlong handle) {
    auto* st = reinterpret_cast<LlamaState*>(handle);
    if (!st) return;
    if (st->draft) draft_free(st->draft);        // v10.72
    if (st->ctx)   llama_free(st->ctx);
    if (st->model) llama_model_free(st->model);
    delete st;
    log_line("已释放模型");
}

// ---------------------------------------------------------------------------
// v10.27：NPU 库可达性验证
// 直接把路径丢给 dlopen，返回真实的 dlerror 文本 —— 失败原因（namespace 拦截 /
// 依赖缺失 / ELF 不兼容）只能这样拿到，Kotlin 层看不到细节。
// 用 RTLD_LAZY 避免未定义符号在 load 阶段就炸掉进程。
// ---------------------------------------------------------------------------
extern "C" JNIEXPORT jstring JNICALL
Java_com_example_bonsaiagent_llm_Llama_dlopenProbe(JNIEnv* env, jobject, jstring jpath) {
    const char* p = env->GetStringUTFChars(jpath, nullptr);
    std::string path = p ? p : "";
    env->ReleaseStringUTFChars(jpath, p);

    std::string out;
    struct stat st{};
    if (::stat(path.c_str(), &st) != 0) {
        out = "stat 失败 errno=" + std::to_string(errno);
    } else {
        out = "size=" + std::to_string((long long) st.st_size);
        dlerror();
        void* h = ::dlopen(path.c_str(), RTLD_LAZY | RTLD_LOCAL);
        const char* e = dlerror();
        if (h) {
            out += " | dlopen=OK";
            ::dlclose(h);
        } else {
            out += " | dlopen 失败: ";
            out += (e ? e : "(无 dlerror)");
        }
    }
    return env->NewStringUTF(out.c_str());
}
