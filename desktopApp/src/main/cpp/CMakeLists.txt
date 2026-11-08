cmake_minimum_required(VERSION 3.24)
project(bitchord_reworked_platform LANGUAGES CXX)

# Fresh desktop build of the existing MOBILE analyzer and its JNI boundary.
# This file does not include an earlier desktop or web platform build.
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(CMAKE_MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>")
find_package(JNI REQUIRED COMPONENTS JVM)

get_filename_component(BITCHORD_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../.." ABSOLUTE)
set(MOBILE_JNI "${BITCHORD_ROOT}/app/src/main/cpp/jni")
set(ANALYZER "${BITCHORD_ROOT}/native/analyzer")

set(CMAKE_LIBRARY_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/lib")
set(CMAKE_RUNTIME_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/lib")
foreach(configuration DEBUG RELEASE RELWITHDEBINFO MINSIZEREL)
    set(CMAKE_LIBRARY_OUTPUT_DIRECTORY_${configuration} "${CMAKE_BINARY_DIR}/lib")
    set(CMAKE_RUNTIME_OUTPUT_DIRECTORY_${configuration} "${CMAKE_BINARY_DIR}/lib")
endforeach()

add_library(bitchord_analysis SHARED
    "${ANALYZER}/audio_analysis.cpp"
    "${ANALYZER}/tempo_analysis.cpp"
    "${ANALYZER}/resampler.cpp"
    "${ANALYZER}/mel_spectrogram.cpp"
    "${ANALYZER}/vocal_spectrogram.cpp"
    "${MOBILE_JNI}/analysis_jni.cpp"
    "${MOBILE_JNI}/mel_jni.cpp"
    "${MOBILE_JNI}/vocal_jni.cpp"
)
target_include_directories(bitchord_analysis PRIVATE ${JNI_INCLUDE_DIRS} "${BITCHORD_ROOT}/native")
target_compile_definitions(bitchord_analysis PRIVATE NOMINMAX)

add_library(bitchord_loopmix SHARED
    "${BITCHORD_ROOT}/native/loopmix/loop_mix_renderer.cpp"
    "${MOBILE_JNI}/loopmix_jni.cpp"
)
target_include_directories(bitchord_loopmix PRIVATE ${JNI_INCLUDE_DIRS}
    "${BITCHORD_ROOT}/native" "${BITCHORD_ROOT}/native/third_party")
target_compile_definitions(bitchord_loopmix PRIVATE NOMINMAX)

if(WIN32)
    # New HWND-based adapter for the mobile MediaSession contract.
    add_library(bitchord_platform SHARED windows_media.cpp)
    target_include_directories(bitchord_platform PRIVATE ${JNI_INCLUDE_DIRS})
    target_compile_definitions(bitchord_platform PRIVATE NOMINMAX WIN32_LEAN_AND_MEAN)
    target_link_libraries(bitchord_platform PRIVATE windowsapp ole32 dwmapi)
endif()
