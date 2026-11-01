cmake_minimum_required(VERSION 3.22.1)
project(ai_tamil_whisper LANGUAGES C CXX)

set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(WHISPER_FFMPEG OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)

include(FetchContent)
FetchContent_Declare(
    whisper
    GIT_REPOSITORY https://github.com/ggml-org/whisper.cpp.git
    GIT_TAG v1.7.6
    GIT_SHALLOW TRUE
)
FetchContent_MakeAvailable(whisper)

add_library(ai_tamil_whisper SHARED whisper-jni.cpp)
find_library(ANDROID_LOG_LIB log)
target_link_libraries(ai_tamil_whisper PRIVATE whisper ${ANDROID_LOG_LIB})
