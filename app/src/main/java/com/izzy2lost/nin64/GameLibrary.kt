package com.izzy2lost.nin64

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

internal data class RomEntry(
    val displayName: String,
    val fileName: String,
    val fallbackName: String,
    val headerTitle: String,
    val md5: String? = null,
    val crc: String? = null,
    val databaseName: String? = null,
    val localFile: File? = null,
    val documentUri: Uri? = null,
    val zipEntryName: String? = null,
    /** The cover this game matched last time, shown before the cover index has loaded. */
    val coverFile: String? = null,
) {
    fun coverCandidates(): List<String> =
        listOfNotNull(databaseName, displayName, cleanDisplayName(), fileName, fallbackName).distinct()

    fun preferenceKey(): String = md5 ?: crc ?: fileName

    fun cleanDisplayName(): String =
        cleanRomTitle(displayName).ifBlank { displayName }

    fun listLabel(): String = cleanDisplayName()
}

private fun cleanRomTitle(title: String): String {
    val firstParen = title.indexOf('(').takeIf { it >= 0 } ?: title.length
    val firstBracket = title.indexOf('[').takeIf { it >= 0 } ?: title.length
    val markerIndex = minOf(firstParen, firstBracket)
    return title
        .take(markerIndex)
        .trim()
        .trimEnd('-', '.', '_')
        .trim()
}

/** A file in the games folder, with the size and date that tell whether it changed. */
internal data class LibraryFile(
    val uri: Uri,
    val name: String,
    val size: Long,
    val modified: Long,
)

/**
 * The library as last scanned, so it shows at once on launch and a rescan only reads
 * the files that are new or changed. Files that turned out not to be games (a zip with
 * no ROM inside) are remembered too, so they are not reopened on every scan.
 */
internal class LibraryCache(private val file: File) {

    class Scanned(val size: Long, val modified: Long, val entry: RomEntry?) {
        fun matches(libraryFile: LibraryFile): Boolean =
            size == libraryFile.size && modified == libraryFile.modified
    }

    /** The scan of [folder] keyed by document URI, in library order; empty if there is none. */
    fun load(folder: Uri): LinkedHashMap<String, Scanned> {
        val scanned = LinkedHashMap<String, Scanned>()
        val root = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return scanned
        if (root.optString("folder") != folder.toString()) return scanned

        val files = root.optJSONArray("files") ?: return scanned
        for (i in 0 until files.length()) {
            val item = files.getJSONObject(i)
            val uri = item.getString("uri")
            val entry = item.optJSONObject("game")?.let { game -> game.toRomEntry(Uri.parse(uri)) }
            scanned[uri] = Scanned(item.getLong("size"), item.getLong("modified"), entry)
        }
        return scanned
    }

    fun save(folder: Uri, scanned: List<Pair<LibraryFile, RomEntry?>>) {
        val files = JSONArray()
        for ((libraryFile, entry) in scanned) {
            files.put(
                JSONObject()
                    .put("uri", libraryFile.uri.toString())
                    .put("size", libraryFile.size)
                    .put("modified", libraryFile.modified)
                    .put("game", entry?.toJson() ?: JSONObject.NULL)
            )
        }
        val root = JSONObject().put("folder", folder.toString()).put("files", files)

        val temp = File(file.path + ".tmp")
        temp.writeText(root.toString())
        if (!temp.renameTo(file)) temp.delete()
    }

    private fun RomEntry.toJson(): JSONObject =
        JSONObject()
            .put("displayName", displayName)
            .put("fileName", fileName)
            .put("fallbackName", fallbackName)
            .put("headerTitle", headerTitle)
            .putOpt("md5", md5)
            .putOpt("crc", crc)
            .putOpt("databaseName", databaseName)
            .putOpt("zipEntryName", zipEntryName)
            .putOpt("coverFile", coverFile)

    private fun JSONObject.toRomEntry(documentUri: Uri): RomEntry =
        RomEntry(
            displayName = getString("displayName"),
            fileName = getString("fileName"),
            fallbackName = getString("fallbackName"),
            headerTitle = getString("headerTitle"),
            md5 = optStringOrNull("md5"),
            crc = optStringOrNull("crc"),
            databaseName = optStringOrNull("databaseName"),
            documentUri = documentUri,
            zipEntryName = optStringOrNull("zipEntryName"),
            coverFile = optStringOrNull("coverFile"),
        )

    private fun JSONObject.optStringOrNull(name: String): String? =
        if (isNull(name)) null else optString(name)
}
