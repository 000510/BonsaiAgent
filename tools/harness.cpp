// ARM64 复现程序：和 App 里的 JNI 调用序列完全一致，
// 在 CI 里交叉编译成 aarch64 后用 qemu 跑，用来复现手机上的静默崩溃。
#include "llama.h"
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

int main(int argc, char ** argv) {
    const char * path   = argc > 1 ? argv[1] : "/tmp/q.gguf";
    const char * prompt = argc > 2 ? argv[2] : "你好";

    fprintf(stderr, "[1] backend init\n");
    llama_backend_init();

    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;

    fprintf(stderr, "[2] load model: %s\n", path);
    llama_model * model = llama_model_load_from_file(path, mp);
    if (!model) { fprintf(stderr, "LOAD FAILED\n"); return 1; }
    fprintf(stderr, "[3] model ok\n");

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = 2048;
    cp.n_batch = 128;
    cp.n_ubatch = 64;
    cp.n_threads = 1;
    cp.n_threads_batch = 1;

    fprintf(stderr, "[4] init ctx n_ctx=%u n_batch=%u n_ubatch=%u threads=%d\n",
            cp.n_ctx, cp.n_batch, cp.n_ubatch, cp.n_threads);
    llama_context * ctx = llama_init_from_model(model, cp);
    if (!ctx) { fprintf(stderr, "CTX FAILED\n"); return 1; }
    fprintf(stderr, "[5] ctx ok\n");

    const llama_vocab * vocab = llama_model_get_vocab(model);

    int need = llama_tokenize(vocab, prompt, (int) strlen(prompt), nullptr, 0, true, true);
    if (need < 0) need = -need;
    std::vector<llama_token> toks(need);
    int n = llama_tokenize(vocab, prompt, (int) strlen(prompt), toks.data(), need, true, true);
    if (n < 0) { fprintf(stderr, "TOKENIZE FAILED %d\n", n); return 1; }
    toks.resize(n);
    fprintf(stderr, "[6] prompt %d tokens\n", n);

    fprintf(stderr, "[7] prefill llama_batch_get_one(%d)\n", n);
    llama_batch batch = llama_batch_get_one(toks.data(), n);
    int rc = llama_decode(ctx, batch);
    fprintf(stderr, "[8] prefill decode ret = %d\n", rc);
    if (rc != 0) return 1;

    llama_sampler * smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(smpl, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(0.95f, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(0.3f));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(1234));

    std::string out;
    char buf[512];
    for (int i = 0; i < 32; i++) {
        llama_token next = llama_sampler_sample(smpl, ctx, -1);
        if (llama_vocab_is_eog(vocab, next)) break;
        int len = llama_token_to_piece(vocab, next, buf, sizeof(buf), 0, false);
        if (len > 0) out.append(buf, len);
        llama_batch tb = llama_batch_get_one(&next, 1);
        if (llama_decode(ctx, tb) != 0) { fprintf(stderr, "[!] decode fail at %d\n", i); break; }
    }
    fprintf(stderr, "[9] OUTPUT: %s\n", out.c_str());
    fprintf(stderr, "[10] done\n");

    llama_sampler_free(smpl);
    llama_free(ctx);
    llama_model_free(model);
    llama_backend_free();
    return 0;
}
