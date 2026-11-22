cmake_minimum_required(VERSION 3.22.1)
project("freespeak_qwen3asr" LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(freespeak_qwen3asr SHARED
    qwen3_asr_jni.cpp
)

find_library(log-lib log)

target_compile_options(freespeak_qwen3asr PRIVATE
    -O3
    -fvisibility=hidden
)

target_link_libraries(freespeak_qwen3asr
    ${log-lib}
)
