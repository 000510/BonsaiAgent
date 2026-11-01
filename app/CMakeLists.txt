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

set(LLAMA_DIR ${CMAKE_SOURCE_DIR}/llamacpp)

# 只编 llama.cpp 核心库（libllama / libggml），关掉示例/测试/服务端，避免缺依赖报错
set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER   OFF CACHE BOOL "" FORCE)

# 编译 llama.cpp 核心（fork 自带 CMakeLists，会产出 llama / ggml 等 target）
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
)
