#ifndef NIN64_CHEEVOS_BRIDGE_H
#define NIN64_CHEEVOS_BRIDGE_H

/*
 * RetroAchievements <-> libretro bridge (jni_bridge_libretro.c). Everything
 * here runs on the emulation thread (Nin64-Emu), like the bridge itself.
 */

#include <jni.h>
#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/* Provided by the bridge: the loaded ROM as booted, and the core's RDRAM. */
const uint8_t *nin64_bridge_rom_data(size_t *size);
const uint8_t *nin64_bridge_system_ram(size_t *size);

/* Provided by cheevos_session.cpp. */
void nin64_cheevos_init_jni(JavaVM *vm, JNIEnv *env);
/* After each retro_run(). */
void nin64_cheevos_frame(void);
/* Before the game is unloaded. */
void nin64_cheevos_end_session(void);
/* After a save state is written / read successfully. */
void nin64_cheevos_after_save_state(const char *state_path);
void nin64_cheevos_after_load_state(const char *state_path);

#ifdef __cplusplus
}
#endif

#endif
