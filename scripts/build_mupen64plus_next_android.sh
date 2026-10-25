#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
CORE_ROOT="${REPO_ROOT}/third_party/mupen64plus-libretro-nx"
CORE_ROOT_REL="${CORE_ROOT#"${REPO_ROOT}/"}"
CORE_JNI_DIR="${CORE_ROOT}/libretro/jni"
STAMP_DIR="${REPO_ROOT}/app/build/native-core-stamps"
FORCE_REBUILD="${NIN64_FORCE_CORE_REBUILD:-0}"
NIN64_ENABLE_THINLTO="${NIN64_ENABLE_THINLTO:-1}"
ABIS=(arm64-v8a x86_64)

if [[ "${NIN64_ENABLE_THINLTO}" != "0" && "${NIN64_ENABLE_THINLTO}" != "1" ]]; then
    echo "NIN64_ENABLE_THINLTO must be 0 or 1." >&2
    exit 1
fi

if [[ ! -d "${CORE_ROOT}" ]]; then
    echo "Vendored core not found at ${CORE_ROOT}" >&2
    exit 1
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

NDK_ROOT_DIR="$(cd "$(dirname "${NDK_BUILD}")" && pwd)"
NDK_SOURCE_PROPERTIES="${NDK_ROOT_DIR}/source.properties"
COMMON_NDK_ARGS=(
    APP_PLATFORM=android-26
    APP_OPTIM=release
    NDK_DEBUG=0
    GLES3=1
)

thinlto_for_abi() {
    case "$1" in
        x86|x86_64)
            printf '0\n'
            ;;
        *)
            printf '%s\n' "${NIN64_ENABLE_THINLTO}"
            ;;
    esac
}

compute_build_fingerprint() {
    local abi="$1"
    local abi_thinlto
    abi_thinlto="$(thinlto_for_abi "${abi}")"

    {
        printf 'abi=%s\n' "${abi}"
        printf 'ndk_build=%s\n' "${NDK_BUILD}"
        printf 'thinlto_requested=%s\n' "${NIN64_ENABLE_THINLTO}"
        printf 'thinlto_effective=%s\n' "${abi_thinlto}"
        printf 'common_args=%s\n' "${COMMON_NDK_ARGS[*]}"
        sha256sum "${SCRIPT_DIR}/build_mupen64plus_next_android.sh"
        if [[ -f "${NDK_SOURCE_PROPERTIES}" ]]; then
            sha256sum "${NDK_SOURCE_PROPERTIES}"
        fi
        git -C "${REPO_ROOT}" ls-files -z --cached --others --exclude-standard -- "${CORE_ROOT_REL}" |
            sort -z |
            while IFS= read -r -d '' source_file; do
                sha256sum "${REPO_ROOT}/${source_file}"
            done
    } | sha256sum | awk '{print $1}'
}

echo "Using ndk-build at ${NDK_BUILD}"
echo "Building Mupen64Plus-Next for ${ABIS[*]} with GLES3 enabled, ThinLTO=${NIN64_ENABLE_THINLTO}..."
mkdir -p "${STAMP_DIR}"

for ABI in "${ABIS[@]}"; do
    APP_JNI_LIBS_DIR="${REPO_ROOT}/app/src/main/jniLibs/${ABI}"
    OUTPUT_LIB="${APP_JNI_LIBS_DIR}/libmupen64plus_next_libretro.so"
    BUILT_LIB="${CORE_ROOT}/libretro/libs/${ABI}/libretro.so"
    STAMP_FILE="${STAMP_DIR}/${ABI}.sha256"
    ABI_THINLTO="$(thinlto_for_abi "${ABI}")"
    CURRENT_FINGERPRINT="$(compute_build_fingerprint "${ABI}")"
    PREVIOUS_FINGERPRINT="$(cat "${STAMP_FILE}" 2>/dev/null || true)"

    echo "${ABI}: effective ThinLTO=${ABI_THINLTO}"

    if [[ -f "${OUTPUT_LIB}" && "${PREVIOUS_FINGERPRINT}" == "${CURRENT_FINGERPRINT}" && "${FORCE_REBUILD}" != "1" ]]; then
        echo "Core already built at ${OUTPUT_LIB} with current inputs, skipping ${ABI} build."
        continue
    fi

    if [[ "${FORCE_REBUILD}" == "1" || "${PREVIOUS_FINGERPRINT}" != "${CURRENT_FINGERPRINT}" ]]; then
        echo "Cleaning existing Mupen64Plus-Next objects for ${ABI}..."
        "${NDK_BUILD}" \
            -C "${CORE_JNI_DIR}" \
            APP_ABI="${ABI}" \
            "${COMMON_NDK_ARGS[@]}" \
            NIN64_ENABLE_THINLTO="${ABI_THINLTO}" \
            clean
    fi

    "${NDK_BUILD}" \
        -C "${CORE_JNI_DIR}" \
        APP_ABI="${ABI}" \
        "${COMMON_NDK_ARGS[@]}" \
        NIN64_ENABLE_THINLTO="${ABI_THINLTO}" \
        -j"$(nproc)"

    if [[ ! -f "${BUILT_LIB}" ]]; then
        echo "Expected built core not found at ${BUILT_LIB}" >&2
        exit 1
    fi

    mkdir -p "${APP_JNI_LIBS_DIR}"
    cp "${BUILT_LIB}" "${OUTPUT_LIB}"
    printf '%s\n' "${CURRENT_FINGERPRINT}" > "${STAMP_FILE}"
    echo "Copied packaged ${ABI} core to ${OUTPUT_LIB}"
done
