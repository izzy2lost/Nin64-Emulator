# Nin64

A Nintendo 64 emulator for Android, powered by [Mupen64Plus-Next](https://github.com/libretro/mupen64plus-libretro-nx) via the libretro API.

<a href="https://play.google.com/store/apps/details?id=com.izzy2lost.nin64&pcampaignid=web_share">
  <img src="https://play.google.com/intl/en_us/badges/static/images/badges/en_badge_web_generic.png" alt="Get it on Google Play" height="80"/>
</a>

## Features

- **Mupen64Plus-Next libretro core** — hardware-accelerated N64 emulation via GLES3
- **GLideN64 renderer** — accurate RDP/RSP emulation with direct frame presentation
- **ARM64 & x86_64 support** — runs on modern Android phones and Google Games PC
- **Resolution scaling up to 8x** — render games at up to 8x native resolution for a sharper image
- **Texture pack support** — load custom high-resolution texture packs
- **Cheat manager** — browse and toggle per-game cheat codes (GameShark/Action Replay format)
- **Configurable touch controls** — reposition, resize, and remap the on-screen controller layout
- **Physical controller remapping** — fully remap USB and Bluetooth controller buttons and sticks
- **Game library browser** — scan and launch ROMs from local storage
- **Save states** — save and restore game progress at any point
- **Local Wi-Fi multiplayer** — up to 4 players: host a game from its options, other devices on the same Wi-Fi find it and join the lobby, and everyone runs in lockstep with a sync check
- **RetroAchievements** — sign in to earn achievements (casual mode), with unlock popups and an in-game achievement list
- **In-game saving** — SRAM / FlashRAM / EEPROM / MemPak persistence
- **Google Oboe audio** — low-latency audio backend
- **Free with optional ad removal** — play for free with ads, or purchase ad removal as an in-app purchase

### Coming Soon

- Cloud save sync
- Online multiplayer

## Requirements

- Android 8.0+ (API 26 or newer)
- ARM64 (arm64-v8a) or x86_64 device
- OpenGL ES 3.0 capable GPU
- Your own legally obtained N64 ROMs

## Building

```bash
# Build the native Mupen64Plus-Next core first
bash scripts/build_mupen64plus_next_android.sh

# Then build the APK via Gradle
./gradlew assembleRelease
```

Requires Android NDK `30.0.14904198` and CMake `3.30.3`.

## Architecture

Nin64 bridges a Kotlin/Android shell to the native libretro emulation core via JNI:

```text
Kotlin UI / Game Library
        ↓ JNI
  libretro frontend (C)
        ↓
 Mupen64Plus-Next core
  (Mupen64Plus + GLideN64)
        ↓
  EGL / GLES3 surface
```

See [docs/native-core-backends.md](docs/native-core-backends.md) for details.

## License

Nin64 is licensed under the [GNU General Public License v2.0](LICENSE), matching the emulation core it wraps:

- **GNU General Public License v2.0** ([gnu.org](https://www.gnu.org/licenses/old-licenses/gpl-2.0.en.html)) (Mupen64Plus / Mupen64Plus-Next)
- Some components under compatible open-source licenses

No Nintendo ROM files or BIOS content are included. Nin64 is not affiliated with Nintendo.

## Acknowledgements

- [Mupen64Plus](https://github.com/mupen64plus) — the original N64 emulation core
- [Mupen64Plus-Next / libretro](https://github.com/libretro/mupen64plus-libretro-nx) — modern libretro port
- [GLideN64](https://github.com/gonetz/GLideN64) — the graphics plugin
