cmake_minimum_required(VERSION 3.22.1)
project(freespeak_canary_gguf LANGUAGES C CXX ASM)

include(FetchContent)
if(POLICY CMP0135)
    cmake_policy(SET CMP0135 NEW)
endif()
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(GGML_CPU_REPACK ON CACHE BOOL "" FORCE)

# Canary needs the newer ggml API. Keep it private to this JNI library so its
# symbols cannot collide with the older ggml bundled by whisper.cpp.
FetchContent_Declare(ggml
    URL https://codeload.github.com/CrispStrobe/ggml/tar.gz/7c0a9284329b168cf29642b5c6b4b47abf6094b2
    URL_HASH SHA256=12ed0160b0731615888d3886841c3f43549ef58bea72e1fe21628bc0626fef2c)
FetchContent_Declare(crispasr
    URL https://codeload.github.com/CrispStrobe/CrispASR/tar.gz/ad22a042c64e54598f5457854ba8ba20f7d96a6b
    URL_HASH SHA256=48218a7233ccf9d7849d8a94a487af3a156fe5d97a4bd7351665917f7c58645e
    SOURCE_SUBDIR unused)
FetchContent_MakeAvailable(ggml crispasr)

foreach(target IN ITEMS ggml-base ggml-cpu)
    target_compile_options(${target} PRIVATE -O3)
endforeach()

add_library(freespeak_canary_gguf SHARED canary_jni.cpp
    ${crispasr_SOURCE_DIR}/src/canary.cpp
    ${crispasr_SOURCE_DIR}/src/crispasr_imatrix.cpp
    ${crispasr_SOURCE_DIR}/src/core/mel.cpp
    ${crispasr_SOURCE_DIR}/src/core/gguf_loader.cpp)
target_include_directories(freespeak_canary_gguf PRIVATE
    ${crispasr_SOURCE_DIR}/src ${crispasr_SOURCE_DIR}/include ${ggml_SOURCE_DIR}/src)
target_compile_features(freespeak_canary_gguf PRIVATE cxx_std_17)
target_compile_options(freespeak_canary_gguf PRIVATE -O3 -fvisibility=hidden -fvisibility-inlines-hidden)
target_link_options(freespeak_canary_gguf PRIVATE -Wl,--exclude-libs,ALL -Wl,--no-undefined)
target_link_libraries(freespeak_canary_gguf PRIVATE ggml ${CMAKE_DL_LIBS})
