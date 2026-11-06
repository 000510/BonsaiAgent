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

# 关键提速项：为同一份 ARM64 代码编出多个指令集变体（armv8.0 / dotprod / i8mm / sve），
# 运行时按当前 CPU 实际能力挑最快的那个。
# 之前强制 armv8-a 基线是为了排除 SIGILL，但后来反汇编确认 .so 里根本没有
# dotprod/i8mm/LSE/SVE 指令 —— 说明这类设备支持，白白的性能没用上。
# 量化点积用上 dotprod / i8mm 通常能快 2~4 倍。
# 注意：GGML_CPU_ARM_ARCH 与 GGML_CPU_ALL_VARIANTS 互斥，只能取其一。
set(GGML_CPU_ALL_VARIANTS ON CACHE BOOL "" FORCE)

# 编译 llama.cpp 核心（fork 自带 CMakeLists，会产出 llama / ggml 等 target）
#
# v3.1 关键：必须让 llama/ggml 以【静态库】形式链进 libbonsai_llm.so。
# 原因：-Wl,--wrap=abort 只对本链接单元内的未定义符号引用生效。
# 若 ggml 编成独立的 libggml-cpu.so，那么 ggml 内部的 abort() 调用发生在
# 那个 .so 自己的链接单元里，我们的 __wrap_abort 永远接不到 —— 白改。
set(BUILD_SHARED_LIBS     OFF CACHE BOOL "" FORCE)
set(GGML_SHARED           OFF CACHE BOOL "" FORCE)
set(GGML_STATIC           ON  CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SHARED    OFF CACHE BOOL "" FORCE)
set(LLAMA_STATIC          ON  CACHE BOOL "" FORCE)

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
