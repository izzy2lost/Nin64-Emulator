cmake_minimum_required(VERSION 3.22.1)

project(nin64_core LANGUAGES C CXX)

set(CMAKE_C_STANDARD 99)
set(CMAKE_C_STANDARD_REQUIRED OFF)
set(CMAKE_C_EXTENSIONS ON)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(NIN64_CORE_BACKEND "legacy" CACHE STRING "Native backend to build: legacy or libretro")
set_property(CACHE NIN64_CORE_BACKEND PROPERTY STRINGS legacy libretro)

set(LEGACY_ROOT "${CMAKE_CURRENT_LIST_DIR}/../../../../")
set(PORT_COMPAT "${CMAKE_CURRENT_LIST_DIR}/compat/port_compat.h")
set(LIBRETRO_ROOT "${LEGACY_ROOT}/third_party/mupen64plus-libretro-nx")

find_library(log-lib log)
find_library(android-lib android)
find_library(dl-lib dl)
find_library(egl-lib EGL)
find_library(glesv3-lib GLESv3)
find_package(oboe REQUIRED CONFIG)

if(NOT NIN64_CORE_BACKEND STREQUAL "legacy" AND NOT NIN64_CORE_BACKEND STREQUAL "libretro")
    message(FATAL_ERROR "Unsupported NIN64_CORE_BACKEND='${NIN64_CORE_BACKEND}'. Use 'legacy' or 'libretro'.")
endif()

if(NIN64_CORE_BACKEND STREQUAL "legacy")
    set(NIN64_CORE_SOURCES
        "${LEGACY_ROOT}/BOOT.C"
        "${LEGACY_ROOT}/CART.C"
        "${LEGACY_ROOT}/CPU.C"
        "${LEGACY_ROOT}/CPUC.C"
        "${LEGACY_ROOT}/DISASM.C"
        "${LEGACY_ROOT}/DLIST.C"
        "${LEGACY_ROOT}/HW.C"
        "${LEGACY_ROOT}/INIFILE.C"
        "${LEGACY_ROOT}/LOG.C"
        "${LEGACY_ROOT}/MEM.C"
        "${LEGACY_ROOT}/OS.C"
        "${LEGACY_ROOT}/PATCH.C"
        "${LEGACY_ROOT}/RDP.C"
        "${LEGACY_ROOT}/SLIST.C"
        "${LEGACY_ROOT}/SYM.C"
        "${LEGACY_ROOT}/SYMFIND.C"
        "${LEGACY_ROOT}/SYNC.C"
        "${LEGACY_ROOT}/TIMER.C"
        "${LEGACY_ROOT}/ZLIST.C"
        "${CMAKE_CURRENT_LIST_DIR}/android_port.c"
        "${CMAKE_CURRENT_LIST_DIR}/jni_bridge.c"
        "${CMAKE_CURRENT_LIST_DIR}/x_soft.c"
    )

    add_library(nin64_core SHARED ${NIN64_CORE_SOURCES})

    set_source_files_properties(
        "${LEGACY_ROOT}/BOOT.C"
        "${LEGACY_ROOT}/CART.C"
        "${LEGACY_ROOT}/CPU.C"
        "${LEGACY_ROOT}/CPUC.C"
        "${LEGACY_ROOT}/DISASM.C"
        "${LEGACY_ROOT}/DLIST.C"
        "${LEGACY_ROOT}/HW.C"
        "${LEGACY_ROOT}/INIFILE.C"
        "${LEGACY_ROOT}/LOG.C"
        "${LEGACY_ROOT}/MEM.C"
        "${LEGACY_ROOT}/OS.C"
        "${LEGACY_ROOT}/PATCH.C"
        "${LEGACY_ROOT}/RDP.C"
        "${LEGACY_ROOT}/SLIST.C"
        "${LEGACY_ROOT}/SYM.C"
        "${LEGACY_ROOT}/SYMFIND.C"
        "${LEGACY_ROOT}/SYNC.C"
        "${LEGACY_ROOT}/TIMER.C"
        "${LEGACY_ROOT}/ZLIST.C"
        PROPERTIES
        LANGUAGE C
    )

    target_include_directories(
        nin64_core
        PRIVATE
        "${CMAKE_CURRENT_LIST_DIR}/compat"
        "${LEGACY_ROOT}"
    )

    target_compile_options(
        nin64_core
        PRIVATE
        -O3
        -include
        "${PORT_COMPAT}"
        -fcommon
        -fms-extensions
        -Wno-deprecated-non-prototype
        -Wno-format-security
        -Wno-implicit-function-declaration
        -Wno-incompatible-pointer-types
        -Wno-int-conversion
        -Wno-int-to-pointer-cast
        -Wno-macro-redefined
        -Wno-pointer-to-int-cast
        -Wno-return-type
        -Wno-unused-but-set-variable
        -Wno-unused-function
        -Wno-unused-variable
    )
else()
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
endif()

target_link_libraries(
    nin64_core
    PRIVATE
    ${android-lib}
    ${log-lib}
    ${dl-lib}
    m
)

if(NIN64_CORE_BACKEND STREQUAL "libretro")
    target_link_libraries(
        nin64_core
        PRIVATE
        ${egl-lib}
        ${glesv3-lib}
        oboe::oboe
    )
endif()
