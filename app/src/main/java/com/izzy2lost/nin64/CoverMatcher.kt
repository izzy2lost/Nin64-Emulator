package com.izzy2lost.nin64

import android.content.Context

/**
 * Caches the cover-art index from the GitHub repo (one .png filename per line) and
 * fuzzy-matches a ROM filename to a cover. The index is downloaded once per day.
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

    @Volatile private var normToFile: Map<String, String> = emptyMap()
    @Volatile private var ready = false

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

    private val KNOWN_EXTENSIONS = setOf("z64", "n64", "v64", "rom", "bin", "png")
}
