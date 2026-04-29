package com.izzy2lost.nin64

import android.content.Context
import java.io.File
import java.io.Reader
import java.util.Locale

internal object RomDatabase {

    private const val DATABASE_FILE = "mupen64plus.ini"

    @Volatile private var md5ToGoodName: Map<String, String> = emptyMap()
    @Volatile private var crcToGoodName: Map<String, String> = emptyMap()

    fun init(context: Context, runtimeIni: File?) {
        val entries = runCatching {
            if (runtimeIni != null && runtimeIni.canRead()) {
                runtimeIni.reader().use(::parse)
            } else {
                context.assets.open(DATABASE_FILE).bufferedReader().use(::parse)
            }
        }.getOrElse {
            emptyMap()
        }

        val resolved = resolveRefMd5s(entries)
        md5ToGoodName = resolved.mapValuesNotNull { it.value.goodName }
        crcToGoodName = resolved.values
            .mapNotNull { entry -> entry.crc?.let { crc -> crc to entry.goodName } }
            .groupBy({ it.first }, { it.second })
            .mapValuesNotNull { (_, names) -> names.filterNotNull().distinct().singleOrNull() }
    }

    fun goodNameFor(identity: RomIdentity): String? {
        identity.md5
            ?.uppercase(Locale.US)
            ?.let { md5ToGoodName[it] }
            ?.let { return it }

        return identity.crc
            ?.uppercase(Locale.US)
            ?.let { crcToGoodName[it] }
    }

    private fun parse(reader: Reader): Map<String, Entry> {
        val entries = linkedMapOf<String, Entry>()
        var current: Entry? = null

        reader.forEachLine { rawLine ->
            val line = rawLine.trim()
            when {
                line.isEmpty() || line.startsWith(";") -> Unit
                line.startsWith("[") && line.endsWith("]") -> {
                    val md5 = line.substring(1, line.length - 1).uppercase(Locale.US)
                    current = Entry(md5).also { entries[md5] = it }
                }
                else -> {
                    val entry = current ?: return@forEachLine
                    val separator = line.indexOf('=')
                    if (separator <= 0) {
                        return@forEachLine
                    }

                    val key = line.substring(0, separator)
                    val value = line.substring(separator + 1).trim()
                    when (key) {
                        "GoodName" -> entry.goodName = value
                        "CRC" -> entry.crc = value.uppercase(Locale.US)
                        "RefMD5" -> entry.refMd5 = value.uppercase(Locale.US)
                    }
                }
            }
        }

        return entries
    }

    private fun resolveRefMd5s(entries: Map<String, Entry>): Map<String, Entry> {
        repeat(entries.size) {
            var changed = false
            for (entry in entries.values) {
                val ref = entry.refMd5?.let(entries::get) ?: continue
                if (entry.goodName == null && ref.goodName != null) {
                    entry.goodName = ref.goodName
                    changed = true
                }
                if (entry.crc == null && ref.crc != null) {
                    entry.crc = ref.crc
                    changed = true
                }
            }
            if (!changed) {
                return entries
            }
        }

        return entries
    }

    private data class Entry(
        val md5: String,
        var goodName: String? = null,
        var crc: String? = null,
        var refMd5: String? = null,
    )
}

private inline fun <K, V, R : Any> Map<K, V>.mapValuesNotNull(transform: (Map.Entry<K, V>) -> R?): Map<K, R> {
    val destination = LinkedHashMap<K, R>()
    for (entry in entries) {
        val value = transform(entry)
        if (value != null) {
            destination[entry.key] = value
        }
    }
    return destination
}
