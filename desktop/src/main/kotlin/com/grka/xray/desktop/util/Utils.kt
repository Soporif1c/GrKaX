package com.grka.xray.desktop.util

import java.net.URLDecoder
import java.util.Base64
import java.util.Locale
import java.util.UUID

object Utils {

    /** Tries the base64 flavours subscriptions show up in (std/url-safe, padded or not). */
    fun tryDecodeBase64(text: String): String? {
        val cleaned = text.trim().replace("\n", "").replace("\r", "").replace(" ", "")
        if (cleaned.isEmpty()) return null
        val padded = cleaned.padEnd((cleaned.length + 3) / 4 * 4, '=')
        for (decoder in listOf(Base64.getDecoder(), Base64.getUrlDecoder(), Base64.getMimeDecoder())) {
            for (candidate in listOf(cleaned, padded)) {
                try {
                    val bytes = decoder.decode(candidate)
                    if (bytes.isNotEmpty()) return String(bytes, Charsets.UTF_8)
                } catch (e: Exception) {
                    // try the next variant
                }
            }
        }
        return null
    }

    fun urlDecode(text: String): String = try {
        URLDecoder.decode(text, "UTF-8")
    } catch (e: Exception) {
        text
    }

    fun isIpAddress(value: String): Boolean {
        val v = value.trim()
        if (v.isEmpty()) return false
        val parts = v.split(".")
        if (parts.size == 4 && parts.all { it.toIntOrNull() in 0..255 }) return true
        if (v.contains(":") && v.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' }) return true
        return false
    }

    /**
     * Stable machine identifier sent as `x-hwid` to panels like Remnawave.
     * On macOS the hardware UUID is the natural anchor; elsewhere we fall back
     * to user + hostname so the value still survives restarts.
     */
    val hwid: String by lazy {
        val seed = machineSeed()
        UUID.nameUUIDFromBytes("grkax:$seed".toByteArray(Charsets.UTF_8)).toString()
    }

    private fun machineSeed(): String {
        if (Platform.isMac) {
            runCatching {
                val out = Shell.capture("/usr/sbin/ioreg", "-rd1", "-c", "IOPlatformExpertDevice")
                Regex("\"IOPlatformUUID\"\\s*=\\s*\"([^\"]+)\"").find(out)?.groupValues?.get(1)
            }.getOrNull()?.takeIf { it.isNotBlank() }?.let { return it }
        }
        val user = System.getProperty("user.name") ?: "user"
        val host = runCatching { java.net.InetAddress.getLocalHost().hostName }.getOrNull() ?: "host"
        return "$user@$host"
    }

    fun formatBytes(bytes: Long): String {
        if (bytes < 0) return "—"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var unit = 0
        while (value >= 1024 && unit < units.size - 1) {
            value /= 1024
            unit++
        }
        return if (unit == 0) "$bytes ${units[0]}" else String.format(Locale.US, "%.1f %s", value, units[unit])
    }

    fun formatSpeed(bytesPerSecond: Long): String = formatBytes(bytesPerSecond) + "/s"

    fun formatDuration(millis: Long): String {
        if (millis <= 0) return "00:00"
        val totalSeconds = millis / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }
    }

    fun formatDate(epochSeconds: Long): String {
        if (epochSeconds <= 0) return "—"
        val date = java.time.Instant.ofEpochSecond(epochSeconds)
            .atZone(java.time.ZoneId.systemDefault())
            .toLocalDate()
        return date.toString()
    }
}
