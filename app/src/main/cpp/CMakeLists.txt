cmake_minimum_required(VERSION 3.22.1)

project(nin64_core LANGUAGES C CXX)

set(CMAKE_C_STANDARD 99)
set(CMAKE_C_STANDARD_REQUIRED OFF)
set(CMAKE_C_EXTENSIONS ON)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(LIBRETRO_ROOT "${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/mupen64plus-libretro-nx")

find_library(log-lib log)
find_library(android-lib android)
find_library(dl-lib dl)
find_library(egl-lib EGL)
find_library(glesv3-lib GLESv3)
find_package(oboe REQUIRED CONFIG)

add_library(
    nin64_core
    SHARED
    "${CMAKE_CURRENT_LIST_DIR}/libretro_bridge/jni_bridge_libretro.c"
    "${CMAKE_CURRENT_LIST_DIR}/libretro_bridge/audio_player.cpp"
)

target_include_directories(
    nin64_core
    PRIVATE
    "${LIBRETRO_ROOT}/libretro-common/include"
)

target_compile_definitions(
    nin64_core
    PRIVATE
    NIN64_LIBRETRO_CORE_NAME="libmupen64plus_next_libretro.so"
)

target_compile_options(
    nin64_core
    PRIVATE
    -O2
    -Wall
    -Wextra
    -Wno-unused-parameter
)

target_link_libraries(
    nin64_core
    PRIVATE
    ${android-lib}
    ${log-lib}
    ${dl-lib}
    ${egl-lib}
    ${glesv3-lib}
    oboe::oboe
    m
)
