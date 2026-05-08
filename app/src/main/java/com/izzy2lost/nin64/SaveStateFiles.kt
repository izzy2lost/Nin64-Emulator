package com.izzy2lost.nin64

import java.io.File

internal object SaveStateFiles {
    const val SAVE_SLOT_COUNT = 10

    fun stateFile(
        rootPath: String,
        fallbackRoot: File,
        romPreferenceKey: String?,
        romPath: String,
        slot: Int,
    ): File = File(stateDirectory(rootPath, fallbackRoot), "${stateBaseName(romPreferenceKey, romPath)}_slot${slot}.state")

    fun thumbnailFile(
        rootPath: String,
        fallbackRoot: File,
        romPreferenceKey: String?,
        romPath: String,
        slot: Int,
    ): File = File(stateDirectory(rootPath, fallbackRoot), "${stateBaseName(romPreferenceKey, romPath)}_slot${slot}.png")

    fun stateBaseName(romPreferenceKey: String?, romPath: String): String {
        val raw = romPreferenceKey ?: File(romPath).nameWithoutExtension.ifBlank { "game" }
        val safe = raw.replace(Regex("[^A-Za-z0-9._-]"), "_").trim('_').take(96)
        return safe.ifBlank { "game" }
    }

    private fun stateDirectory(rootPath: String, fallbackRoot: File): File =
        File(rootPath.ifBlank { fallbackRoot.absolutePath }, "Mupen64plus/states").apply { mkdirs() }
}
