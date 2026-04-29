package com.izzy2lost.nin64

import android.content.Context
import org.json.JSONArray
import java.util.Locale

data class CheatGame(
    val crcKey: String,
    val title: String,
    val cheats: List<CheatEntry>,
)

data class CheatEntry(
    val id: String,
    val name: String,
    val description: String?,
    val codeLines: List<CheatCodeLine>,
) {
    val options: List<CheatOption>
        get() = codeLines.firstOrNull { it.options.isNotEmpty() }?.options.orEmpty()

    fun resolvedCodeLine(selectedOptionValue: String?): String =
        codeLines.joinToString(" ") { codeLine ->
            codeLine.resolvedText(selectedOptionValue)
        }
}

data class CheatCodeLine(
    val address: String,
    val value: String,
    val options: List<CheatOption> = emptyList(),
) {
    fun resolvedText(selectedOptionValue: String?): String {
        val resolvedValue = if (options.isEmpty()) {
            value
        } else {
            val selected = selectedOptionValue?.uppercase(Locale.US)
            options.firstOrNull { it.value == selected }?.value ?: options.first().value
        }
        return "$address $resolvedValue"
    }
}

data class CheatOption(
    val value: String,
    val label: String,
)

object CheatDatabase {
    private const val CHEAT_FILE = "mupencheat.txt"
    private val codePattern = Regex("^([0-9A-Fa-f?]{8})\\s+([0-9A-Fa-f?]{4})(.*)$")
    private val optionPattern = Regex("\\b([0-9A-Fa-f]{4})\\s*:\\s*\"([^\"]*)\"")

    fun findByCrc(context: Context, crc: String?): CheatGame? {
        val targetKey = normalizeCrc(crc) ?: return null
        context.assets.open(CHEAT_FILE).bufferedReader().use { reader ->
            var inTargetGame = false
            var gameTitle = ""
            var cheatName: String? = null
            val cheatDescriptions = mutableListOf<String>()
            val cheatCodes = mutableListOf<String>()
            val cheats = mutableListOf<CheatEntry>()

            fun flushCheat() {
                val name = cheatName ?: return
                val parsedCodes = cheatCodes.mapNotNull(::parseCodeLine)
                if (parsedCodes.isNotEmpty()) {
                    cheats += CheatEntry(
                        id = "$targetKey:${cheats.size}",
                        name = name.replace('\\', '/'),
                        description = cheatDescriptions.joinToString("\n").takeIf { it.isNotBlank() },
                        codeLines = parsedCodes,
                    )
                }
                cheatName = null
                cheatDescriptions.clear()
                cheatCodes.clear()
            }

            while (true) {
                val line = reader.readLine()?.trim() ?: break
                if (line.startsWith("crc ", ignoreCase = true)) {
                    if (inTargetGame) {
                        flushCheat()
                        break
                    }
                    inTargetGame = crcKeyFromLine(line) == targetKey
                    gameTitle = ""
                    cheatName = null
                    cheatDescriptions.clear()
                    cheatCodes.clear()
                    continue
                }

                if (!inTargetGame || line.isBlank() || line.startsWith("//")) continue

                when {
                    line.startsWith("gn ", ignoreCase = true) -> {
                        gameTitle = line.drop(3).trim()
                    }
                    line.startsWith("cn ", ignoreCase = true) -> {
                        flushCheat()
                        cheatName = line.drop(3).trim()
                    }
                    line.startsWith("cd ", ignoreCase = true) -> {
                        cheatDescriptions += line.drop(3).trim()
                    }
                    cheatName != null -> {
                        cheatCodes += line
                    }
                }
            }

            if (inTargetGame) {
                flushCheat()
                return CheatGame(
                    crcKey = targetKey,
                    title = gameTitle.ifBlank { targetKey },
                    cheats = cheats,
                )
            }
        }
        return null
    }

    internal fun normalizeCrc(raw: String?): String? {
        val hex = raw
            ?.filter { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
            ?.uppercase(Locale.US)
            ?: return null
        if (hex.length < 16) return null
        return "${hex.substring(0, 8)}-${hex.substring(8, 16)}"
    }

    internal fun normalizeCodeLine(raw: String): String? {
        return parseCodeLine(raw)?.resolvedText(null)
    }

    internal fun parseCodeLine(raw: String): CheatCodeLine? {
        val match = codePattern.find(raw.trim()) ?: return null
        val address = match.groupValues[1]
        var value = match.groupValues[2]
        val remainder = match.groupValues[3]

        if ('?' in address) return null
        val options = if ('?' in value) {
            optionPattern.findAll(remainder).map { option ->
                CheatOption(
                    value = option.groupValues[1].uppercase(Locale.US),
                    label = option.groupValues[2],
                )
            }.toList()
        } else {
            emptyList()
        }

        if ('?' in value) {
            if (options.isEmpty()) return null
            value = "????"
        }

        return CheatCodeLine(
            address = address.uppercase(Locale.US),
            value = value.uppercase(Locale.US),
            options = options,
        )
    }

    private fun crcKeyFromLine(line: String): String? =
        normalizeCrc(line.removePrefix("crc").trim().substringBefore(' '))
}

object CheatRepository {
    private const val PREFS_NAME = "nin64_prefs"

    fun loadEnabledCheatIds(context: Context, romKey: String): Set<String> {
        val json = prefs(context).getString(perGameCheatsKey(romKey), null) ?: return emptySet()
        return runCatching {
            val array = JSONArray(json)
            buildSet {
                for (index in 0 until array.length()) {
                    array.optString(index).takeIf { it.isNotBlank() }?.let(::add)
                }
            }
        }.getOrDefault(emptySet())
    }

    fun saveEnabledCheatIds(context: Context, romKey: String, cheatIds: Set<String>) {
        val array = JSONArray()
        cheatIds.sorted().forEach(array::put)
        prefs(context).edit()
            .putString(perGameCheatsKey(romKey), array.toString())
            .apply()
    }

    fun loadSelectedOptions(context: Context, romKey: String): Map<String, String> {
        val json = prefs(context).getString(perGameCheatOptionsKey(romKey), null) ?: return emptyMap()
        return runCatching {
            val root = org.json.JSONObject(json)
            buildMap {
                root.keys().forEach { key ->
                    val value = root.optString(key).takeIf { it.isNotBlank() }
                    if (value != null) put(key, value)
                }
            }
        }.getOrDefault(emptyMap())
    }

    fun saveSelectedOption(context: Context, romKey: String, cheatId: String, optionValue: String) {
        val selectedOptions = loadSelectedOptions(context, romKey).toMutableMap()
        selectedOptions[cheatId] = optionValue.uppercase(Locale.US)
        val root = org.json.JSONObject()
        selectedOptions.toSortedMap().forEach { (key, value) -> root.put(key, value) }
        prefs(context).edit()
            .putString(perGameCheatOptionsKey(romKey), root.toString())
            .apply()
    }

    fun enabledCheatCodeLines(context: Context, romKey: String?, crc: String?): List<String> {
        if (romKey.isNullOrBlank()) return emptyList()
        val enabledIds = loadEnabledCheatIds(context, romKey)
        if (enabledIds.isEmpty()) return emptyList()
        val selectedOptions = loadSelectedOptions(context, romKey)
        val game = CheatDatabase.findByCrc(context, crc) ?: return emptyList()
        return game.cheats
            .filter { it.id in enabledIds }
            .map { it.resolvedCodeLine(selectedOptions[it.id]) }
    }

    private fun perGameCheatsKey(romKey: String): String =
        "per_game.$romKey.cheats"

    private fun perGameCheatOptionsKey(romKey: String): String =
        "per_game.$romKey.cheat_options"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
