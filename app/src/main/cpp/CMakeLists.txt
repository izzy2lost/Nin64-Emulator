cmake_minimum_required(VERSION 3.22.1)

project(nin64_core LANGUAGES C CXX)

set(CMAKE_C_STANDARD 99)
set(CMAKE_C_STANDARD_REQUIRED OFF)
set(CMAKE_C_EXTENSIONS ON)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(LIBRETRO_ROOT "${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/mupen64plus-libretro-nx")
set(NIN64_VERSION "0.0" CACHE STRING "App version reported to RetroAchievements")

# RetroAchievements (rcheevos, vendored). Whole runtime directory: its translation
# units reference each other freely. Not built: disc/encrypted/zip hashing (N64 ROMs
# reach it as one buffer), rc_client_raintegration.c (Windows) and rc_libretro.c.
set(RC_DIR "${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/rcheevos")
file(GLOB RC_RUNTIME_SRC CONFIGURE_DEPENDS "${RC_DIR}/src/rcheevos/*.c")
file(GLOB RC_API_SRC CONFIGURE_DEPENDS "${RC_DIR}/src/rapi/*.c")
set(RC_SRC
    ${RC_RUNTIME_SRC}
    ${RC_API_SRC}
    "${RC_DIR}/src/rc_client.c"
    "${RC_DIR}/src/rhash/hash.c"
    "${RC_DIR}/src/rhash/hash_rom.c"
    "${RC_DIR}/src/rhash/md5.c"
    "${RC_DIR}/src/rc_util.c"
    "${RC_DIR}/src/rc_compat.c"
    "${RC_DIR}/src/rc_version.c"
)
set_source_files_properties(${RC_SRC} PROPERTIES
    COMPILE_DEFINITIONS "RC_HASH_NO_DISC;RC_HASH_NO_ENCRYPTED;RC_HASH_NO_ZIP;RC_DISABLE_LUA"
    INCLUDE_DIRECTORIES "${RC_DIR}/src;${RC_DIR}/src/rhash;${RC_DIR}/src/rapi"
    COMPILE_OPTIONS "-w"
)
# rc_version.h (the version string for the User-Agent) is not a public header.
set_source_files_properties("${CMAKE_CURRENT_LIST_DIR}/cheevos/cheevos_client.cpp" PROPERTIES
    INCLUDE_DIRECTORIES "${RC_DIR}/src"
)

find_library(log-lib log)
find_library(android-lib android)
find_library(dl-lib dl)
find_library(egl-lib EGL)
find_library(glesv3-lib GLESv3)
find_library(z-lib z)
find_package(oboe REQUIRED CONFIG)

add_library(
    nin64_core
    SHARED
    "${CMAKE_CURRENT_LIST_DIR}/libretro_bridge/jni_bridge_libretro.c"
    "${CMAKE_CURRENT_LIST_DIR}/libretro_bridge/audio_player.cpp"
    "${CMAKE_CURRENT_LIST_DIR}/cheevos/cheevos_client.cpp"
    "${CMAKE_CURRENT_LIST_DIR}/cheevos/cheevos_session.cpp"
    ${RC_SRC}
)

target_include_directories(
    nin64_core
    PRIVATE
    "${LIBRETRO_ROOT}/libretro-common/include"
    "${CMAKE_CURRENT_LIST_DIR}/cheevos"
    "${RC_DIR}/include"
)

target_compile_definitions(
    nin64_core
    PRIVATE
    NIN64_LIBRETRO_CORE_NAME="libmupen64plus_next_libretro.so"
    # Sent to RetroAchievements in the User-Agent; set from the app's versionName.
    NIN64_VERSION="${NIN64_VERSION}"
)

target_compile_options(
    nin64_core
    PRIVATE
    -Wall
    -Wextra
    -Wno-unused-parameter
    $<$<OR:$<CONFIG:Release>,$<CONFIG:RelWithDebInfo>,$<CONFIG:MinSizeRel>>:-O3>
    $<$<OR:$<CONFIG:Release>,$<CONFIG:RelWithDebInfo>,$<CONFIG:MinSizeRel>>:-DNDEBUG>
)

target_link_options(
    nin64_core
    PRIVATE
    "-Wl,-z,max-page-size=16384"
    "-Wl,-z,common-page-size=16384"
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
    ${z-lib}
    m
)
