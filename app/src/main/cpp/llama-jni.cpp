// ============================================================================
// llama-jni.cpp
// JNI 桥：Kotlin 侧通过它调用 PrismML fork (branch: prism) 的 llama.cpp C API。
//
// 关键修复（v1.2）：
//  1) 加载前必须调用 llama_backend_init()，否则 CPU 后端未注册，加载必失败；
//  2) 安装 ggml 日志回调，把 llama.cpp 的真实报错通过 JNI 回传给界面，
//     不再只往 stderr 打（安卓上看不见）；
//  3) 上下文给足 n_batch，8B 模型 prefill 不再因为 batch 太小而失败；
//  4) 加载失败时按「文件不存在 / 内存不足 / 格式不支持」分类返回可读原因。
// ============================================================================
#include <jni.h>
#include <string>
#include <vector>
#include <cmath>
#include <algorithm>
#include <cstdio>
#include <cstdarg>
#include <sys/stat.h>
#include <unistd.h>
#include <android/log.h>
#include "llama.h"

#define TAG "BonsaiLLM"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// ---------------------------------------------------------------------------
// 捕获 llama.cpp / ggml 的日志，交给 Kotlin 显示
// ---------------------------------------------------------------------------
static std::string g_lastError;        // 最近一条 ERROR 级日志
static const int  kMaxErrLen = 2000;

static void ggml_log_cb(enum ggml_log_level level, const char * text, void * /*user_data*/) {
    if (!text) return;
    // 打印到 logcat，方便 adb 排查
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

// 保存 model + context（llama.cpp 两者分开管理）
struct LlamaState {
    llama_model*   model;
    llama_context* ctx;
};

// ---- 流式回调：C++ 每生成一个 piece，就通知 Kotlin ----
static JavaVM*   g_vm       = nullptr;
static jobject   g_cbObj    = nullptr;   // Llama.Callback 实例（全局引用）
static jmethodID g_cbMethod = nullptr;   // onToken(String)

JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void*) {
    g_vm = vm;
    // 关键：初始化 llama/ggml 后端（注册 CPU 后端），必须在任何加载之前
    llama_backend_init();
    llama_log_set(ggml_log_cb, nullptr);
    LOGI("llama_backend_init 完成");
    return JNI_VERSION_1_6;
}

// 把 C++ 异常 / 错误信息转成 Java 字符串返回；出错返回 0
static jlong fail(JNIEnv* env, const char* stage) {
    std::string msg = g_lastError.empty() ? std::string("未知错误") : g_lastError;
    LOGE("%s 失败: %s", stage, msg.c_str());
    LOGE("---- end ----");
    return 0;
}

// ---- 初始化：加载模型 + 建上下文 ----
extern "C" JNIEXPORT jlong JNICALL
Java_com_example_bonsaiagent_llm_Llama_init(
        JNIEnv* env, jobject,
        jstring jpath, jint n_threads, jint n_ctx, jint n_gpu_layers) {

    g_lastError.clear();

    const char* path = env->GetStringUTFChars(jpath, nullptr);
    std::string spath(path);
    env->ReleaseStringUTFChars(jpath, path);

    // --- 加载前先自检：文件是否存在、大小是否正常 ---
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
    mparams.n_gpu_layers = n_gpu_layers;   // 手机无 GPU 走 CPU，传 0

    llama_model* model = llama_model_load_from_file(spath.c_str(), mparams);
    if (!model) {
        // 磁盘空间/内存不足时 llama.cpp 也会返回 null
        if (g_lastError.empty()) {
            g_lastError = "llama.cpp 未能加载该文件（格式不支持或内存不足）";
        }
        return fail(env, "加载模型");
    }
    LOGI("模型加载成功");

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx           = n_ctx > 0 ? n_ctx : 4096;
    cparams.n_batch         = 512;                 // prefill 批量，太小会失败
    cparams.n_ubatch        = 512;
    cparams.n_threads       = n_threads;
    cparams.n_threads_batch = n_threads;
    cparams.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_DISABLED; // CPU 上关掉更稳

    llama_context* ctx = llama_init_from_model(model, cparams);
    if (!ctx) {
        llama_model_free(model);
        if (g_lastError.empty()) g_lastError = "上下文创建失败（内存不足，试试把上下文长度调小）";
        return fail(env, "创建上下文");
    }

    LlamaState* st = new LlamaState{model, ctx};
    LOGI("模型已加载, n_ctx=%u threads=%d", cparams.n_ctx, n_threads);
    return reinterpret_cast<jlong>(st);
}

// ---- 取最近一次错误（给界面显示真实原因）----
extern "C" JNIEXPORT jstring JNICALL
Java_com_example_bonsaiagent_llm_Llama_lastError(JNIEnv* env, jobject) {
    if (g_lastError.empty()) return env->NewStringUTF("");
    // 精简：只取最后 400 字符，避免界面刷屏
    std::string s = g_lastError;
    if (s.size() > 400) s = "…" + s.substr(s.size() - 400);
    return env->NewStringUTF(s.c_str());
}

// ---- 注册流式回调对象 ----
extern "C" JNIEXPORT void JNICALL
Java_com_example_bonsaiagent_llm_Llama_setCallback(JNIEnv* env, jobject, jobject cb) {
    if (g_cbObj) env->DeleteGlobalRef(g_cbObj);
    g_cbObj = env->NewGlobalRef(cb);
    jclass cls = env->GetObjectClass(cb);
    g_cbMethod = env->GetMethodID(cls, "onToken", "(Ljava/lang/String;)V");
}

// ---- 采样（自己实现，不依赖 llama 版本差异）----
// 支持：temperature 缩放 + 基础 top-k + 基础 top-p + 重复惩罚
static llama_token sample_token(float* logits, int n_vocab,
                                float temp, int top_k, float top_p, float rep_pen,
                                const std::vector<llama_token>& gen) {
    std::vector<float> s(n_vocab);
    for (int i = 0; i < n_vocab; ++i) s[i] = logits[i];

    // 重复惩罚：对已经生成过的 token 降分
    if (rep_pen > 1.0001f) {
        for (llama_token t : gen) if (t >= 0 && t < n_vocab) s[t] /= rep_pen;
    }

    if (temp > 0.0001f) for (auto& v : s) v /= temp;

    // 找 top-k 阈值
    float kth = -1e30f;
    if (top_k > 0 && top_k < n_vocab) {
        std::vector<float> tmp(s);
        std::nth_element(tmp.begin(), tmp.begin() + top_k, tmp.end(),
                         [](float a, float b){ return a > b; });
        kth = tmp[top_k - 1];
    }

    // softmax 累加（只统计 >= kth 的，近似 top-k）
    double sum = 0.0;
    std::vector<double> probs(n_vocab, 0.0);
    for (int i = 0; i < n_vocab; ++i) {
        if (s[i] < kth) continue;
        double p = std::exp((double)s[i]);
        probs[i] = p;
        sum += p;
    }
    if (sum <= 0) { // 兜底贪心
        llama_token best = 0; float bv = -1e30f;
        for (int i = 0; i < n_vocab; ++i) if (s[i] > bv) { bv = s[i]; best = i; }
        return best;
    }
    for (auto& p : probs) p /= sum;

    // top-p（核采样）
    if (top_p < 0.999f) {
        std::vector<int> idx(n_vocab);
        for (int i = 0; i < n_vocab; ++i) idx[i] = i;
        std::sort(idx.begin(), idx.end(), [&](int a, int b){ return probs[a] > probs[b]; });
        double acc = 0; std::vector<double> kept(n_vocab, 0.0);
        for (int i = 0; i < n_vocab; ++i) {
            if (acc >= top_p) break;
            kept[idx[i]] = probs[idx[i]]; acc += probs[idx[i]];
        }
        double ksum = 0; for (double v : kept) ksum += v;
        if (ksum > 0) for (int i = 0; i < n_vocab; ++i) probs[i] = kept[i] / ksum;
    }

    // 按概率随机采样
    double r = ((double)rand()) / RAND_MAX;
    double cum = 0;
    for (int i = 0; i < n_vocab; ++i) {
        cum += probs[i];
        if (r <= cum) return i;
    }
    return n_vocab - 1;
}

// ---- 生成（流式）----
extern "C" JNIEXPORT jstring JNICALL
Java_com_example_bonsaiagent_llm_Llama_generate(
        JNIEnv* env, jobject,
        jlong handle, jstring jprompt,
        jfloat temp, jint top_k, jfloat top_p, jfloat rep_pen, jint max_tokens) {

    auto* st = reinterpret_cast<LlamaState*>(handle);
    if (!st || !st->ctx) return env->NewStringUTF("[模型未初始化]");

    const char* cprompt = env->GetStringUTFChars(jprompt, nullptr);
    std::string prompt(cprompt);
    env->ReleaseStringUTFChars(jprompt, cprompt);

    // prism 分支：vocab 已从 model 拆成独立类型，相关函数第一参数改为 vocab
    const struct llama_vocab * vocab = llama_model_get_vocab(st->model);
    int n_vocab = llama_vocab_n_tokens(vocab);

    // tokenize（新版第一参数为 vocab）
    // 第一次传入空缓冲，函数返回所需容量；容量不足时返回负值
    int need = llama_tokenize(vocab, prompt.c_str(), (int)prompt.size(),
                              nullptr, 0, true, true);
    if (need == 0) return env->NewStringUTF("[tokenize 失败: prompt 为空]");
    if (need < 0) need = -need;      // 负数表示所需容量
    need += 8;                        // 留点余量

    std::vector<llama_token> tokens(need);
    int got = llama_tokenize(vocab, prompt.c_str(), (int)prompt.size(),
                             tokens.data(), need, true, true);
    if (got <= 0) {
        g_lastError = "tokenize 返回 " + std::to_string(got);
        return env->NewStringUTF("[tokenize 失败]");
    }
    tokens.resize(got);
    int n_tok = got;

    // 不能超过上下文窗口，否则 decode 必失败
    int n_ctx_limit = (int) llama_n_ctx(st->ctx);
    if (n_tok > n_ctx_limit - 8) {
        n_tok = n_ctx_limit - 8;
        if (n_tok <= 0) return env->NewStringUTF("[上下文太小]");
        tokens.resize(n_tok);
        LOGI("prompt 过长，截断到 %d tokens", n_tok);
    }

    // batch 容量必须 >= prompt token 数，否则手动填充会越界
    int batch_cap = n_tok > 1024 ? n_tok : 1024;
    llama_batch batch = llama_batch_init(batch_cap, 0, 1);
    llama_seq_id seq0 = 0;   // 单序列 id = 0

    // prefill：prism 分支已无 llama_batch_add 辅助函数，手动填充 batch
    batch.n_tokens = n_tok;
    for (int i = 0; i < n_tok; i++) {
        batch.token[i]    = tokens[i];
        batch.pos[i]      = i;
        batch.n_seq_id[i] = 1;
        batch.seq_id[i]   = &seq0;
        batch.logits[i]   = 0;
    }
    batch.logits[n_tok - 1] = 1;   // 仅最后一个 token 输出 logits
    if (llama_decode(st->ctx, batch) != 0) {
        llama_batch_free(batch);
        return env->NewStringUTF("[prefill 失败]");
    }

    std::string full;
    std::vector<llama_token> gen;
    int n_past = n_tok;
    llama_token eos = llama_vocab_eos(vocab);

    for (int i = 0; i < max_tokens; i++) {
        // 取最后一个位置的 logits
        float* logits = llama_get_logits_ith(st->ctx, batch.n_tokens - 1);
        if (!logits) break;
        llama_token next = sample_token(logits, n_vocab, temp, top_k, top_p, rep_pen, gen);
        if (next == eos) break;

        // piece -> 字符串
        char buf[512];
        int len = llama_token_to_piece(vocab, next, buf, sizeof(buf), 0, true);
        if (len > 0) {
            std::string piece(buf, len);
            // ChatML 结束符：Qwen2.5 / Qwen3(Bonsai) 生成完会吐 <|im_end|>，遇到就停
            if (piece.find("<|im_end|>") != std::string::npos ||
                piece.find("<|endoftext|>") != std::string::npos ||
                piece.find("<|eot_id|>") != std::string::npos) {
                break;
            }
            full += piece;
            gen.push_back(next);
            // 流式回调
            if (g_cbObj && g_cbMethod) {
                jstring jp = env->NewStringUTF(piece.c_str());
                env->CallVoidMethod(g_cbObj, g_cbMethod, jp);
                env->DeleteLocalRef(jp);
            }
        }

        // 把新 token 喂回去（继续手动填充 batch）
        batch.n_tokens   = 1;
        batch.token[0]    = next;
        batch.pos[0]      = n_past;
        batch.n_seq_id[0] = 1;
        batch.seq_id[0]   = &seq0;
        batch.logits[0]   = 1;
        if (llama_decode(st->ctx, batch) != 0) break;
        n_past++;
    }

    llama_batch_free(batch);
    return env->NewStringUTF(full.c_str());
}

// ---- 释放 ----
extern "C" JNIEXPORT void JNICALL
Java_com_example_bonsaiagent_llm_Llama_freeNative(JNIEnv*, jobject, jlong handle) {
    auto* st = reinterpret_cast<LlamaState*>(handle);
    if (!st) return;
    if (st->ctx)   llama_free(st->ctx);
    if (st->model) llama_model_free(st->model);
    delete st;
    LOGI("已释放模型");
}
