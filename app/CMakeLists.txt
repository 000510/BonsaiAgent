cmake_minimum_required(VERSION 3.22.1)
project("bonsai_llm")

# ============================================================================
# 重要：PrismML fork 的 llama.cpp（分支 prism）必须先 clone 到 cpp/llamacpp
#   cd app/src/main/cpp
#   git clone --branch prism https://github.com/PrismML-Eng/llama.cpp llamacpp
#   cd llamacpp && git submodule update --init --recursive   # 若 fork 用 submodule
# 然后 Android Studio 会自动用 NDK 编译 llama.cpp 核心 + 下面的 JNI 封装。
# 如果 PrismML 的 target 名不是 llama/ggml（新版拆成了 ggml-base/ggml-cpu 等），
# 把下面 target_link_libraries 里的名字改成 fork 实际产出的库名即可。
# ============================================================================

set(LLAMA_DIR ${CMAKE_SOURCE_DIR}/src/main/cpp/llamacpp)

# v4.2 兜底：无条件给所有 target 追加 -O3。
# 原因：CMake 的编译命令行顺序是 CMAKE_<LANG>_FLAGS → CMAKE_<LANG>_FLAGS_<CONFIG>
# → add_compile_options → target 选项。AGP 若在某处塞了 -O0，后面这个 -O3 能盖掉它。
# 推理速度几乎完全取决于这里 —— 实测漏掉优化会慢十几倍。
add_compile_options(-O3 -fno-math-errno)

# ---------------------------------------------------------------------------
# v4.0：撤销所有排查用的降级设置 —— 真凶是 UI 的 item key（已修），这些不再需要。
#
# 之前为了"排除优化放大 UB"把编译优化强制成 -O0。实测 0.5B 模型单线程要
# 几十秒一个 token —— 其中很大一块就是 -O0 造成的（比 -O2 慢 5~10 倍）。
# 现在统一用 -O3；Debug 构建也带上，避免调试版慢得没法用。
# ---------------------------------------------------------------------------
set(CMAKE_C_FLAGS_RELEASE   "-O3 -g" CACHE STRING "" FORCE)
set(CMAKE_CXX_FLAGS_RELEASE "-O3 -g" CACHE STRING "" FORCE)
set(CMAKE_C_FLAGS_DEBUG     "-O3 -g" CACHE STRING "" FORCE)
set(CMAKE_CXX_FLAGS_DEBUG   "-O3 -g" CACHE STRING "" FORCE)
set(CMAKE_C_FLAGS_RELWITHDEBINFO   "-O3 -g" CACHE STRING "" FORCE)
set(CMAKE_CXX_FLAGS_RELWITHDEBINFO "-O3 -g" CACHE STRING "" FORCE)

# 只编 llama.cpp 核心库（libllama / libggml），关掉示例/测试/服务端，避免缺依赖报错
set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER   OFF CACHE BOOL "" FORCE)

# ---------------------------------------------------------------------------
# v4.0：恢复性能相关设置
# ---------------------------------------------------------------------------
# OpenMP 仍关闭：llama.cpp 自己用 std::thread 线程池，不需要 OpenMP。
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
# GGML_NATIVE 在交叉编译（NDK）下必须 OFF，否则会按编译机的 CPU 生成指令。
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)

# ALL_VARIANTS（多指令集变体）依赖 BACKEND_DL，静态模式下必须关闭。
# 静态版用默认基线（armv8-a，已含 NEON，三值 kernel 能用），
# 代价是拿不到 dotprod/i8mm 的额外加速，先保证能跑。
set(GGML_CPU_ALL_VARIANTS OFF CACHE BOOL "" FORCE)

# 编译 llama.cpp 核心（fork 自带 CMakeLists，会产出 llama / ggml 等 target）
#
# v5.1：退回静态链接。
# 原因：动态后端（BACKEND_DL）在 prism 分支 + 三值模型这条路径上会崩：
#   ggml_backend_tensor_get_async 里调了空函数指针（addr=0xc 的 SIGSEGV），
#   栈是 ggml_abort ← ggml_backend_tensor_get_async ← llama_context::decode。
#   模型能加载、graph 能建（961 节点），一进 decode 取张量就踩空。
# 静态链接把这层间接调用全部编掉，同时还让 -Wl,--wrap=abort 重新生效
# （跨 .so 时 --wrap 是无效的，这正是之前抓不到 ggml_abort 消息的原因）。
set(BUILD_SHARED_LIBS  OFF CACHE BOOL "" FORCE)
set(GGML_SHARED        OFF CACHE BOOL "" FORCE)
set(GGML_STATIC        ON  CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SHARED OFF CACHE BOOL "" FORCE)
set(LLAMA_STATIC       ON  CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL    OFF CACHE BOOL "" FORCE)

add_subdirectory(${LLAMA_DIR} llamacpp_build)

# 我们的 JNI 桥（源文件在 src/main/cpp 下）
add_library(bonsai_llm SHARED ${CMAKE_SOURCE_DIR}/src/main/cpp/llama-jni.cpp)

find_library(log-lib log)

target_include_directories(bonsai_llm PRIVATE
    ${LLAMA_DIR}
    ${LLAMA_DIR}/include
    ${LLAMA_DIR}/common
)

target_link_libraries(bonsai_llm
    llama
    ggml
    ${log-lib}
    android
    z
    dl
    m
)

# ---------------------------------------------------------------------------
# v3.0 诊断：把静态库(ggml)里对 abort/assert/__stack_chk_fail/__fortify_fail
# 的引用全部重定向到 llama-jni.cpp 里的 __wrap_xxx 实现，
# 这样"程序自尽"的最后一步会被我们记录下来再退出 —— 死因不再丢失。
# ---------------------------------------------------------------------------
target_link_options(bonsai_llm PRIVATE
    -Wl,--wrap=abort
    -Wl,--wrap=__assert2
    -Wl,--wrap=__assert
    -Wl,--wrap=__stack_chk_fail
    -Wl,--wrap=__fortify_fail
)
