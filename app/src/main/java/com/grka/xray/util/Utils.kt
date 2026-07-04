package com.grka.xray.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.provider.Settings
import android.util.Base64
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.net.URLDecoder
import java.util.Locale
import java.util.UUID

object Utils {

    fun tryDecodeBase64(text: String): String? {
        val cleaned = text.trim().replace("\n", "").replace("\r", "")
        for (flags in intArrayOf(Base64.DEFAULT, Base64.URL_SAFE, Base64.NO_PADDING, Base64.URL_SAFE or Base64.NO_PADDING)) {
            try {
                val bytes = Base64.decode(cleaned, flags)
                if (bytes.isNotEmpty()) return String(bytes, Charsets.UTF_8)
            } catch (e: Exception) {
                // try next variant
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
        // IPv4
        val parts = v.split(".")
        if (parts.size == 4 && parts.all { it.toIntOrNull() in 0..255 }) return true
        // IPv6 (loose check)
        if (v.contains(":") && v.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' }) return true
        return false
    }

    /** Stable device identifier sent as x-hwid to panels like Remnawave. */
    fun hwid(context: Context): String {
        val androidId = try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "grkax"
        } catch (e: Exception) {
            "grkax"
        }
        return UUID.nameUUIDFromBytes("grkax:$androidId".toByteArray(Charsets.UTF_8)).toString()
    }

    fun qrBitmap(text: String, size: Int = 800): Bitmap? = try {
        val hints = mapOf(EncodeHintType.MARGIN to 1)
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
        val pixels = IntArray(size * size)
        for (y in 0 until size) {
            for (x in 0 until size) {
                pixels[y * size + x] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
            }
        }
        Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    } catch (e: Exception) {
        null
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
        return if (unit == 0) {
            "${bytes} ${units[0]}"
        } else {
            String.format(Locale.US, "%.1f %s", value, units[unit])
        }
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
}
