package com.izzy2lost.nin64

import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale

internal data class RomIdentity(
    val headerTitle: String,
    val md5: String?,
    val crc: String?,
)

internal object RomIdentityReader {

    fun read(input: InputStream, fallbackName: String): RomIdentity {
        val rom = input.readBytes()
        normalizeRomBytes(rom)

        return RomIdentity(
            headerTitle = readHeaderTitle(rom, fallbackName),
            md5 = rom.takeIf { it.isNotEmpty() }?.let(::md5Hex),
            crc = readCrc(rom),
        )
    }

    private fun normalizeRomBytes(rom: ByteArray) {
        if (rom.size < 4) {
            return
        }

        val b0 = rom[0]
        val b1 = rom[1]
        val b2 = rom[2]
        val b3 = rom[3]

        if (b0 == 0x37.toByte() && b1 == 0x80.toByte() &&
            b2 == 0x40.toByte() && b3 == 0x12.toByte()
        ) {
            for (index in 0 until rom.size - 1 step 2) {
                val tmp = rom[index]
                rom[index] = rom[index + 1]
                rom[index + 1] = tmp
            }
        } else if (b0 == 0x40.toByte() && b1 == 0x12.toByte() &&
            b2 == 0x37.toByte() && b3 == 0x80.toByte()
        ) {
            for (index in 0 until rom.size - 3 step 4) {
                val tmp0 = rom[index]
                val tmp1 = rom[index + 1]
                rom[index] = rom[index + 3]
                rom[index + 1] = rom[index + 2]
                rom[index + 2] = tmp1
                rom[index + 3] = tmp0
            }
        }
    }

    private fun readHeaderTitle(rom: ByteArray, fallbackName: String): String {
        if (rom.size < 0x34) {
            return fallbackName
        }

        val title = rom
            .copyOfRange(0x20, 0x34)
            .toString(StandardCharsets.US_ASCII)
            .replace(Regex("\\s+"), " ")
            .trim { it <= ' ' || it == '\u0000' }

        return title.ifBlank { fallbackName }
    }

    private fun readCrc(rom: ByteArray): String? {
        if (rom.size < 0x18) {
            return null
        }

        return String.format(
            Locale.US,
            "%08X %08X",
            readUInt32(rom, 0x10),
            readUInt32(rom, 0x14),
        )
    }

    private fun readUInt32(bytes: ByteArray, offset: Int): Long {
        return ((bytes[offset].toLong() and 0xffL) shl 24) or
            ((bytes[offset + 1].toLong() and 0xffL) shl 16) or
            ((bytes[offset + 2].toLong() and 0xffL) shl 8) or
            (bytes[offset + 3].toLong() and 0xffL)
    }

    private fun md5Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("MD5").digest(bytes)
        return digest.joinToString(separator = "") { byte ->
            "%02X".format(Locale.US, byte.toInt() and 0xff)
        }
    }
}
