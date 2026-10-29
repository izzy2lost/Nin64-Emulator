package com.izzy2lost.nin64

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * RetroAchievements' HTTP, called from native code (cpp/cheevos/cheevos_session.cpp) on the achievement worker
 * thread: Android has no libcurl. Blocking by design.
 */
object CheevosHttp {
    private const val MAX_BODY = 8 shl 20

    /** Four big-endian status bytes, then the body; status 0 means no answer, with the reason as the body. */
    @JvmStatic
    fun perform(url: String, body: String, contentType: String, userAgent: String): ByteArray {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 15_000
                conn.readTimeout = 30_000
                conn.setRequestProperty("User-Agent", userAgent)
                if (body.isNotEmpty()) {
                    conn.requestMethod = "POST"
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", contentType.ifEmpty { "application/x-www-form-urlencoded" })
                    conn.outputStream.use { it.write(body.toByteArray()) }
                }
                val status = conn.responseCode
                val stream = if (status >= 400) conn.errorStream else conn.inputStream
                val out = ByteArrayOutputStream()
                stream?.use { s ->
                    val buf = ByteArray(16 * 1024)
                    while (true) {
                        val n = s.read(buf)
                        if (n < 0) break
                        if (out.size() + n > MAX_BODY) throw IOException("response too large")
                        out.write(buf, 0, n)
                    }
                }
                frame(status, out.toByteArray())
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            frame(0, (e.message ?: e.javaClass.simpleName).toByteArray())
        }
    }

    private fun frame(status: Int, body: ByteArray): ByteArray {
        val out = ByteArray(4 + body.size)
        out[0] = (status ushr 24).toByte(); out[1] = (status ushr 16).toByte()
        out[2] = (status ushr 8).toByte(); out[3] = status.toByte()
        body.copyInto(out, 4)
        return out
    }
}
