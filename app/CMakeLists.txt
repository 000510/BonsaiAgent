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

# v1.18 排查：Release 配置也强制 -O0，验证"ARM 下优化把 UB 放大导致崩溃"这个假设。
# 直接改 _FLAGS_RELEASE 才有效，光设 CMAKE_BUILD_TYPE 会被 AGP 覆盖。
set(CMAKE_C_FLAGS_RELEASE   "-O0 -g" CACHE STRING "" FORCE)
set(CMAKE_CXX_FLAGS_RELEASE "-O0 -g" CACHE STRING "" FORCE)

# 只编 llama.cpp 核心库（libllama / libggml），关掉示例/测试/服务端，避免缺依赖报错
set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER   OFF CACHE BOOL "" FORCE)

# ↓↓↓ 排查安卓端 llama_decode 静默崩溃时加的降级开关（放在 add_subdirectory 之前才生效）↓↓↓
# 关掉 OpenMP：避免 omp 运行时在某些 SoC 上初始化即崩
set(GGML_OPENMP           OFF CACHE BOOL "" FORCE)
# 关掉按本机 CPU 自动挑指令集：避免选到 SoC 不支持的 NEON/i8mm 变体
set(GGML_NATIVE           OFF CACHE BOOL "" FORCE)
# 强制最保守的 ARMv8.0 基线：不启用 dotprod / i8mm / fp16 等扩展。
# 这些扩展在部分 SoC 上缺失，运行到对应指令会直接 SIGILL（进程无声消失）。
# 注意：GGML_CPU_ARM_ARCH 与 GGML_CPU_ALL_VARIANTS 互斥，所以不再设后者。
set(GGML_CPU_ARM_ARCH "armv8-a" CACHE STRING "" FORCE)
# ↑↑↑ 排查用，若无效可整段删掉 ↑↑↑

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
