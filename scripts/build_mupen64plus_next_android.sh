#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
CORE_ROOT="${REPO_ROOT}/third_party/mupen64plus-libretro-nx"
APP_JNI_LIBS_DIR="${REPO_ROOT}/app/src/main/jniLibs/arm64-v8a"
OUTPUT_LIB="${APP_JNI_LIBS_DIR}/libmupen64plus_next_libretro.so"

if [[ ! -d "${CORE_ROOT}" ]]; then
    echo "Vendored core not found at ${CORE_ROOT}" >&2
    exit 1
fi

if [[ -f "${OUTPUT_LIB}" ]]; then
    echo "Core already built at ${OUTPUT_LIB}, skipping build."
    exit 0
fi

if [[ -n "${ANDROID_NDK_HOME:-}" && -x "${ANDROID_NDK_HOME}/ndk-build" ]]; then
    NDK_BUILD="${ANDROID_NDK_HOME}/ndk-build"
elif [[ -n "${ANDROID_NDK_ROOT:-}" && -x "${ANDROID_NDK_ROOT}/ndk-build" ]]; then
    NDK_BUILD="${ANDROID_NDK_ROOT}/ndk-build"
elif [[ -x "${HOME}/Android/Sdk/ndk/30.0.14904198/ndk-build" ]]; then
    NDK_BUILD="${HOME}/Android/Sdk/ndk/30.0.14904198/ndk-build"
else
    echo "Unable to locate ndk-build. Set ANDROID_NDK_HOME or ANDROID_NDK_ROOT." >&2
    exit 1
fi

echo "Using ndk-build at ${NDK_BUILD}"
echo "Building Mupen64Plus-Next for arm64-v8a with GLES3 enabled..."

"${NDK_BUILD}" \
    -C "${CORE_ROOT}/libretro/jni" \
    APP_ABI=arm64-v8a \
    APP_PLATFORM=android-26 \
    GLES3=1 \
    -j"$(nproc)"

mkdir -p "${APP_JNI_LIBS_DIR}"
cp "${CORE_ROOT}/libretro/libs/arm64-v8a/libretro.so" "${OUTPUT_LIB}"

echo "Copied packaged core to ${OUTPUT_LIB}"
