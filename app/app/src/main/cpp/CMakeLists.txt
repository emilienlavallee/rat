cmake_minimum_required(VERSION 3.22.1)
project(RAT_cv)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# NCNN_ROOT is passed in from build.gradle.kts and points at the unpacked
# arm64-v8a slice of the ncnn Android Vulkan prebuilt.
set(ncnn_DIR "${NCNN_ROOT}/lib/cmake/ncnn" CACHE PATH "" FORCE)
find_package(ncnn REQUIRED)

# Comes from the org.opencv:opencv AAR via prefab (package OpenCV, module opencv_java5).
find_package(OpenCV REQUIRED CONFIG)

add_library(RAT_cv SHARED detector.cpp)

target_link_libraries(RAT_cv PRIVATE ncnn OpenCV::opencv_java5)
