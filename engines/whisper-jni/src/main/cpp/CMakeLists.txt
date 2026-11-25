cmake_minimum_required(VERSION 3.22.1)
project("freespeak_whisper" LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)

add_subdirectory(whisper_core)

# Gradle's cppFlags do not apply to ggml's C kernels. Keep those kernels
# optimized in Debug builds too, while retaining debug symbols and assertions.
foreach(ggml_target IN ITEMS ggml-base ggml-cpu)
    target_compile_options(${ggml_target} PRIVATE $<$<AND:$<CONFIG:Debug>,$<COMPILE_LANGUAGE:C>>:-O3>)
endforeach()

if(ANDROID_ABI STREQUAL "arm64-v8a" AND TARGET ggml-cpu)
    target_compile_options(ggml-cpu PRIVATE -march=armv8.2-a+fp16)
endif()

add_library(freespeak_whisper SHARED
    whisper_jni.cpp
)

find_library(log-lib log)
find_library(android-lib android)

target_include_directories(freespeak_whisper PRIVATE
    whisper_core/include
    whisper_core/ggml/include
)

target_compile_options(freespeak_whisper PRIVATE
    -O3
    -fvisibility=hidden
)

target_link_libraries(freespeak_whisper PRIVATE
    whisper
    ggml
    ${log-lib}
    ${android-lib}
)
