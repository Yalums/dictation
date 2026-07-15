# main/cpp/CMakeLists.txt — Android JNI shared library for Inkling.
#
# Produces libinkling_jni.so; loaded from Kotlin via System.loadLibrary("inkling_jni").

cmake_minimum_required(VERSION 3.18.1)
project(inkling_jni LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Platform-agnostic C++ core lives in the inkling project.
# Using absolute path because the two projects are on different directory trees.
set(INKLING_CORE_DIR "Y:/A5 X2/inkling-claude-review-plugin-demo-eBKZv/cpp")

add_subdirectory(
    ${INKLING_CORE_DIR}
    ${CMAKE_CURRENT_BINARY_DIR}/inkling_core
)

add_library(inkling_jni SHARED
    jni_bridge.cpp
)

target_include_directories(inkling_jni
    PRIVATE ${INKLING_CORE_DIR}/inkling/include
)

target_link_libraries(inkling_jni
    PRIVATE inkling_core
    PRIVATE log
)

# MuPDF ships libmupdf-third.a which bundles its own FreeType / HarfBuzz / libjpeg.
# inkling_core links the FetchContent FreeType / HarfBuzz / turbojpeg too, so the
# same symbols (FT_*, hb_*, jpeg_*) appear twice in this single .so. The two copies
# are the same upstream libraries and ABI-compatible, so letting the linker take the
# first definition is safe. Without this the link fails with "multiple definition".
target_link_options(inkling_jni PRIVATE
    "-Wl,--allow-multiple-definition"
)
