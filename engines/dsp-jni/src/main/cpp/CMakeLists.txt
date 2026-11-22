cmake_minimum_required(VERSION 3.22.1)
project("freespeak_dsp" LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(freespeak_dsp SHARED
    dsp_jni.cpp
)

find_library(log-lib log)

target_compile_options(freespeak_dsp PRIVATE
    -O3
    -ffast-math
    -fvisibility=hidden
)

target_link_libraries(freespeak_dsp
    ${log-lib}
)
