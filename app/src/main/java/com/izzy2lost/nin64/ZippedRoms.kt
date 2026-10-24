package com.izzy2lost.nin64

import android.content.Context
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * Zipped ROMs are unpacked once and the copy is kept, so a game only pays for
 * decompression the first time it boots. The oldest copies are dropped once the
 * cache grows past [CACHE_MAX_BYTES].
 */
internal object ZippedRoms {
    private const val CACHE_MAX_BYTES = 2048L shl 20
    private const val PART_SUFFIX = ".part"

    fun isZip(name: String?): Boolean =
        name?.lowercase(Locale.ROOT)?.endsWith(".zip") == true

    /** Positions [zip] at the first ROM inside it; null when the archive holds none. */
    fun seekRom(zip: ZipInputStream, isRom: (String) -> Boolean): ZipEntry? =
        generateSequence { zip.nextEntry }.firstOrNull { !it.isDirectory && isRom(it.name) }

    fun cacheDirectory(context: Context): File = File(context.cacheDir, "unpacked")

    fun unpack(context: Context, key: String, entryName: String, open: () -> InputStream?): File {
        val dir = File(cacheDirectory(context), safeName(key))
        val target = File(dir, safeName(entryName.substringAfterLast('/')))
        if (target.isFile) {
            target.setLastModified(System.currentTimeMillis())
            return target
        }

        dir.mkdirs()
        val part = File(dir, target.name + PART_SUFFIX)
        val found = open()?.use { input ->
            ZipInputStream(input.buffered()).use { zip ->
                generateSequence { zip.nextEntry }.firstOrNull { it.name == entryName } ?: return@use false
                part.outputStream().use { output -> zip.copyTo(output) }
                true
            }
        } ?: false
        if (!found || !part.renameTo(target)) {
            part.delete()
            throw IOException("Could not unpack $entryName from its zip")
        }

        trim(context, keep = dir)
        return target
    }

    /** Deletes every unpacked copy and returns the bytes freed. */
    fun clear(context: Context): Long =
        cacheDirectory(context).walkBottomUp()
            .filter { it.isFile }
            .sumOf { file -> file.length().also { file.delete() } }

    private fun trim(context: Context, keep: File) {
        val games = cacheDirectory(context).listFiles { file -> file.isDirectory }.orEmpty()
            .map { dir -> dir to dir.walkBottomUp().filter { it.isFile } }
            .map { (dir, files) -> Triple(dir, files.sumOf { it.length() }, files.maxOfOrNull { it.lastModified() } ?: 0L) }
            .sortedBy { (_, _, lastUsed) -> lastUsed }
        var total = games.sumOf { (_, size, _) -> size }
        for ((dir, size, _) in games) {
            if (total <= CACHE_MAX_BYTES) break
            if (dir == keep) continue
            dir.deleteRecursively()
            total -= size
        }
    }

    private fun safeName(name: String): String =
        name.replace(Regex("[^A-Za-z0-9._() -]"), "_").trim('.', ' ').ifBlank { "game" }
}
