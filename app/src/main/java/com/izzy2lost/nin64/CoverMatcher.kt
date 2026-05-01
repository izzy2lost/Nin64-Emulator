package com.izzy2lost.nin64

import android.content.Context
import android.net.Uri
import java.io.File
import java.net.URL

/**
 * Caches the cover-art index from the GitHub repo (one .png filename per line) and
 * fuzzy-matches a ROM filename to a cover. The index is downloaded once per day,
 * while resolved images are stored in app storage for offline reuse.
 *
 * Match order:
 *   1. Exact normalized match (strip region/version tags, lowercase, alphanumeric only)
 *   2. Prefix/substring (handles subtitles added or removed)
 *   3. Greedy LCS similarity ≥ 0.75 (catches minor spelling differences)
 */
internal object CoverMatcher {

    private const val PREFS = "cover_index"
    private const val KEY_DATA = "data"
    private const val KEY_TS = "ts"
    private const val TTL_MS = 24L * 3600_000
    private const val COVER_CACHE_DIR = "cover_art"

    @Volatile private var normToFile: Map<String, String> = emptyMap()
    @Volatile private var ready = false
    private val inFlightDownloads = mutableSetOf<String>()

    fun init(context: Context, indexUrl: String, onReady: (() -> Unit)? = null) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val cached = prefs.getString(KEY_DATA, null)
        val age = System.currentTimeMillis() - prefs.getLong(KEY_TS, 0)

        if (cached != null) {
            normToFile = buildIndex(cached)
            ready = true
            onReady?.invoke()
        }

        if (cached == null || age > TTL_MS) {
            Thread {
                try {
                    val text = java.net.URL(indexUrl).readText()
                    if (text.isNotBlank()) {
                        prefs.edit()
                            .putString(KEY_DATA, text)
                            .putLong(KEY_TS, System.currentTimeMillis())
                            .apply()
                        normToFile = buildIndex(text)
                        ready = true
                        onReady?.invoke()
                    }
                } catch (_: Exception) {
                }
            }.start()
        }
    }

    /** Best-matching cover filename for the ROM, or null if none. */
    fun resolve(romFileName: String): String? {
        return resolve(listOf(romFileName))
    }

    /** Best-matching cover filename for any known ROM name, or null if none. */
    fun resolve(names: Iterable<String?>): String? {
        if (!ready) return null
        val normNames = names
            .mapNotNull { name -> name?.let(::stripKnownExtension)?.let(::normalize) }
            .filter { it.isNotEmpty() }
            .distinct()
        if (normNames.isEmpty()) return null

        for (normRom in normNames) {
            normToFile[normRom]?.let { return it }
        }

        for (normRom in normNames) {
            val prefixHits = normToFile.entries.filter { (k, _) ->
                k.startsWith(normRom) || normRom.startsWith(k)
            }
            if (prefixHits.isNotEmpty()) {
                return prefixHits.minBy { (k, _) -> kotlin.math.abs(k.length - normRom.length) }.value
            }
        }

        return normNames
            .asSequence()
            .flatMap { normRom ->
                normToFile.entries.asSequence().map { (k, v) -> v to similarity(normRom, k) }
            }
            .filter { (_, score) -> score >= 0.75f }
            .maxByOrNull { (_, s) -> s }
            ?.first
    }

    fun coverSource(
        context: Context,
        coverFileName: String,
        baseUrl: String,
        onCached: (() -> Unit)? = null,
    ): Any {
        val cachedFile = cachedCoverFile(context, coverFileName)
        if (cachedFile != null) return cachedFile

        val url = remoteCoverUrl(baseUrl, coverFileName)
        cacheCoverAsync(context.applicationContext, coverFileName, url, onCached)
        return url
    }

    private fun cachedCoverFile(context: Context, coverFileName: String): File? {
        val file = coverFileFor(context, coverFileName)
        return file.takeIf { it.isFile && it.length() > 0L }
    }

    private fun cacheCoverAsync(
        context: Context,
        coverFileName: String,
        url: String,
        onCached: (() -> Unit)?,
    ) {
        synchronized(inFlightDownloads) {
            if (!inFlightDownloads.add(coverFileName)) return
        }

        Thread {
            try {
                val target = coverFileFor(context, coverFileName)
                if (target.isFile && target.length() > 0L) return@Thread

                target.parentFile?.mkdirs()
                val temp = File.createTempFile("cover-", ".tmp", target.parentFile)
                try {
                    val connection = URL(url).openConnection().apply {
                        connectTimeout = 8_000
                        readTimeout = 15_000
                    }
                    connection.getInputStream().use { input ->
                        temp.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    if (temp.length() > 0L) {
                        if (target.exists()) target.delete()
                        if (!temp.renameTo(target)) {
                            temp.copyTo(target, overwrite = true)
                            temp.delete()
                        }
                        onCached?.invoke()
                    }
                } finally {
                    temp.delete()
                }
            } catch (_: Exception) {
            } finally {
                synchronized(inFlightDownloads) {
                    inFlightDownloads.remove(coverFileName)
                }
            }
        }.apply {
            name = "Nin64-CoverCache"
            start()
        }
    }

    private fun buildIndex(text: String): Map<String, String> =
        text.lines()
            .filter { it.endsWith(".png") }
            .associateBy { normalize(stripKnownExtension(it)) }

    private fun stripKnownExtension(name: String): String {
        val extension = name.substringAfterLast('.', missingDelimiterValue = "")
        return if (extension.lowercase() in KNOWN_EXTENSIONS) {
            name.substringBeforeLast('.')
        } else {
            name
        }
    }

    private fun normalize(name: String): String =
        name
            .replace(Regex("""\s*\([^)]*\)"""), "")
            .replace(Regex("""\s*\[[^\]]*\]"""), "")
            .lowercase()
            .replace(Regex("[^a-z0-9]"), "")

    private fun similarity(a: String, b: String): Float {
        if (a == b) return 1f
        if (a.isEmpty() || b.isEmpty()) return 0f
        var i = 0; var j = 0; var common = 0
        while (i < a.length && j < b.length) {
            when {
                a[i] == b[j] -> { common++; i++; j++ }
                a.length - i > b.length - j -> i++
                else -> j++
            }
        }
        return 2f * common / (a.length + b.length)
    }

    private fun coverFileFor(context: Context, coverFileName: String): File {
        return File(coverCacheDirectory(context), safeCacheName(coverFileName))
    }

    private fun coverCacheDirectory(context: Context): File =
        File(context.filesDir, COVER_CACHE_DIR).apply { mkdirs() }

    private fun remoteCoverUrl(baseUrl: String, coverFileName: String): String =
        "$baseUrl/${Uri.encode(coverFileName)}"

    private fun safeCacheName(coverFileName: String): String {
        val safeName = coverFileName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return safeName.ifBlank { "cover.png" }
    }

    private val KNOWN_EXTENSIONS = setOf("z64", "n64", "v64", "rom", "bin", "png")
}
