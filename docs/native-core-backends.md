# Native Core Backends

Nin64 now has a clean migration seam between the Android app shell and the native emulator core.

## Layout

- `app/src/main/cpp/CMakeLists.txt`
  - Builds a single JNI library named `nin64_core`.
  - Accepts `-DNIN64_CORE_BACKEND=legacy` or `-DNIN64_CORE_BACKEND=libretro`.
- `app/src/main/cpp/jni_bridge.c`
  - Existing UltraHLE/legacy JNI implementation.
- `app/src/main/cpp/libretro_bridge/jni_bridge_libretro.c`
  - GLES3/EGL libretro bridge for Mupen64Plus-Next.
- `third_party/mupen64plus-libretro-nx`
  - Vendored source tree from `libretro/mupen64plus-libretro-nx`.

## Default Behavior

The Gradle build now defaults to the libretro/GLES3 backend:

```bash
./gradlew :app:assembleDebug
```

The legacy core is still available when needed:

```bash
./gradlew :app:assembleDebug -Pnin64CoreBackend=legacy
```

## Building the Vendored Libretro Core

Build and package the arm64 libretro core into the app with:

```bash
./gradlew :app:buildMupen64PlusNextArm64
```

That runs:

```bash
scripts/build_mupen64plus_next_android.sh
```

The script builds the vendored core with `ndk-build`, forces the GLES3 path, and copies the output to:

```text
app/src/main/jniLibs/arm64-v8a/libmupen64plus_next_libretro.so
```

## Switching the App to the Libretro Bridge

Build the app against the new JNI bridge with:

```bash
./gradlew :app:assembleDebug -Pnin64CoreBackend=libretro
```

At the moment, the libretro bridge does these useful things:

- loads the packaged `libmupen64plus_next_libretro.so`
- loads ROMs through `retro_load_game()` and advances them through `retro_run()`
- negotiates a true GLES3 hardware-render context with the libretro core
- owns a native `ANativeWindow` / EGL surface on the emulation thread
- presents GLideN64 frames directly to the `SurfaceView` instead of copying software pixels
- reports libretro core metadata through `NativeBridge.init()` / `smokeTest()`
- keeps the existing Kotlin `SurfaceView` shell while replacing the old blit loop underneath it

## What Is Still Missing

The libretro bridge is now usable as a real GLES3 frontend, but there is still plenty of work left:

1. expose richer core options instead of relying on bridge defaults
2. add proper audio output instead of the current sink callback
3. test on real devices and harden context-loss / suspend-resume behavior
4. decide whether we keep the app frontend or move to a full libretro frontend
