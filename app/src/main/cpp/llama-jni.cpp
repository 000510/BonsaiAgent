// ============================================================================
// llama-jni.cpp
// JNI 桥：Kotlin 侧通过它调用 PrismML fork (branch: prism) 的 llama.cpp C API。
//
// 说明：本文件针对「现代 llama.cpp C API」编写，函数名与 PrismML 的 prism 分支
// （基于 llama.cpp）保持一致。若 PrismML 用的是更老/更细的 API，集中在
// 【API 适配点】注释处微调即可，JNI 签名不变。
// ============================================================================
#include <jni.h>
#include <string>
#include <vector>
#include <cmath>
#include <android/log.h>
#include "llama.h"

#define TAG "BonsaiLLM"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// 保存 model + context（llama.cpp 两者分开管理）
struct LlamaState {
    llama_model*  model;
    llama_context* ctx;
};

// ---- 流式回调：C++ 每生成一个 piece，就通知 Kotlin ----
static JavaVM*      g_vm = nullptr;
static jobject      g_cbObj = nullptr;      // Llama.Callback 实例（全局引用）
static jmethodID    g_cbMethod = nullptr;   // onToken(String)

JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void*) {
    g_vm = vm;
    return JNI_VERSION_1_6;
}

// ---- 初始化：加载模型 + 建上下文 ----
extern "C" JNIEXPORT jlong JNICALL
Java_com_example_bonsaiagent_llm_Llama_init(
        JNIEnv* env, jobject,
        jstring jpath, jint n_threads, jint n_ctx, jint n_gpu_layers) {

    const char* path = env->GetStringUTFChars(jpath, nullptr);
    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = n_gpu_layers;          // 手机无 GPU 走 CPU，传 0
    llama_model* model = llama_load_model_from_file(path, mparams);
    env->ReleaseStringUTFChars(jpath, path);
    if (!model) { LOGE("模型加载失败: %s", path); return 0; }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx    = n_ctx;
    cparams.n_threads = n_threads;
    llama_context* ctx = llama_new_context_with_model(model, cparams);
    if (!ctx) { llama_free_model(model); LOGE("上下文创建失败"); return 0; }

    LlamaState* st = new LlamaState{model, ctx};
    LOGI("模型已加载, n_ctx=%d threads=%d", n_ctx, n_threads);
    return reinterpret_cast<jlong>(st);
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
    for (llama_token t : gen) if (t >= 0 && t < n_vocab) s[t] /= rep_pen;

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

    // top-p（核采样）：按概率从大到小累加，截断
    // 简化：若 top_p<1，只保留累计到 top_p 之前的最大若干，其余清零后重归一
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
    int n_tok = llama_tokenize(vocab, prompt.c_str(), (int)prompt.size(),
                               nullptr, 0, true, true);
    if (n_tok <= 0) return env->NewStringUTF("[tokenize 失败]");
    std::vector<llama_token> tokens(n_tok);
    llama_tokenize(vocab, prompt.c_str(), (int)prompt.size(),
                   tokens.data(), n_tok, true, true);

    llama_batch batch = llama_batch_init(1024, 0, 1);
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

    for (int i = 0; i < max_tokens; i++) {
        // 取最后一个位置的 logits
        float* logits = llama_get_logits_ith(st->ctx, batch.n_tokens - 1);
        llama_token next = sample_token(logits, n_vocab, temp, top_k, top_p, rep_pen, gen);
        if (next == llama_vocab_eos(vocab)) break;

        // piece -> 字符串
        char buf[512];
        int len = llama_token_to_piece(vocab, next, buf, sizeof(buf), 0, true);
        if (len > 0) {
            std::string piece(buf, len);
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
    if (st->ctx)  llama_free(st->ctx);
    if (st->model) llama_free_model(st->model);
    delete st;
    LOGI("已释放模型");
}
