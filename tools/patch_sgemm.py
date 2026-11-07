#!/usr/bin/env python3
"""
修补 ggml-cpu/llamafile/sgemm.cpp 的参数断言。

背景
----
Ternary-Bonsai 这类三值模型在 mul_mat 时会给出 ldb < k 的形状，
而 llamafile_sgemm 开头有几行 assert：

    assert(m >= 0); assert(n >= 0); assert(k >= 0);
    assert(lda >= k); assert(ldb >= k); assert(ldc >= m);

Debug 编译（没有 NDEBUG）下这些 assert 会直接 abort 整个进程 —— 就是之前
日志里那条 "sgemm.cpp:4165 断言失败: ldb >= k"。

简单关掉 llamafile（GGML_LLAMAFILE=OFF）能绕开崩溃，但代价很大：
批量矩阵乘退回逐位点积，prefill 从"快好几倍"变成"比逐字生成还慢"
（实测 8B 模型 93 个 token 要 36 秒）。

正确做法是让它在形状不支持时【返回 false】。该函数的返回值语义本来就是
"true 表示我处理了这个矩阵乘"，调用方 ggml_compute_forward_mul_mat 会
据此退回通用实现。这样既能保住其他形状的加速，又不会崩。
"""

import sys

TARGET = "ggml/src/ggml-cpu/llamafile/sgemm.cpp"

OLD = """    assert(m >= 0);
    assert(n >= 0);
    assert(k >= 0);
    assert(lda >= k);
    assert(ldb >= k);
    assert(ldc >= m);"""

NEW = """    // patched: 形状不满足时优雅回退，而不是 assert 崩掉进程。
    // 返回 false 会让调用方改用通用 GEMM 路径。
    if (m < 0 || n < 0 || k < 0 || lda < k || ldb < k || ldc < m) {
        return false;
    }"""


def main() -> int:
    path = sys.argv[1] if len(sys.argv) > 1 else TARGET
    with open(path, encoding="utf-8") as f:
        src = f.read()

    if NEW.split("\n")[2].strip() in src:
        print("[patch_sgemm] 已是补丁后的版本，跳过")
        return 0

    if OLD not in src:
        print("[patch_sgemm] 未找到目标断言，可能上游已改动；不做修改")
        return 0

    with open(path, "w", encoding="utf-8") as f:
        f.write(src.replace(OLD, NEW, 1))
    print("[patch_sgemm] 已把 6 条 assert 改为条件回退")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
