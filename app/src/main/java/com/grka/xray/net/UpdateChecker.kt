package com.grka.xray.net

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import com.grka.xray.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

object UpdateChecker {

    const val REPO = "Soporif1c/GrKaX"

    data class UpdateInfo(
        val latestVersion: String,
        val currentVersion: String,
        val isNewer: Boolean,
        val htmlUrl: String,
        val notes: String,
        val apkUrl: String?,
    )

    sealed class Result {
        data class Success(val info: UpdateInfo) : Result()
        data class Error(val message: String) : Result()
    }

    private val json = Json { ignoreUnknownKeys = true }

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    suspend fun check(): Result = withContext(Dispatchers.IO) {
        try {
            // Use the releases LIST (not /latest) so pre-releases are included.
            val request = Request.Builder()
                .url("https://api.github.com/repos/$REPO/releases?per_page=30")
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "GrKaX/${BuildConfig.VERSION_NAME}")
                .build()

            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext Result.Error("HTTP ${resp.code}")

                val body = resp.body?.string().orEmpty()
                val releases = json.parseToJsonElement(body).jsonArray.mapNotNull { it as? JsonObject }
                val obj = releases.firstOrNull { it["draft"]?.jsonPrimitive?.booleanOrNull != true }
                    ?: return@withContext Result.Error("No releases published yet")
                val tag = obj["tag_name"]?.jsonPrimitive?.content.orEmpty()
                val htmlUrl = obj["html_url"]?.jsonPrimitive?.content ?: "https://github.com/$REPO/releases"
                val notes = obj["body"]?.jsonPrimitive?.content.orEmpty()
                val apkUrl = pickApk(obj["assets"] as? JsonArray)

                val latest = tag.trimStart('v', 'V').trim()
                val current = BuildConfig.VERSION_NAME
                Result.Success(
                    UpdateInfo(
                        latestVersion = latest.ifEmpty { current },
                        currentVersion = current,
                        isNewer = isNewer(current, latest),
                        htmlUrl = htmlUrl,
                        notes = notes,
                        apkUrl = apkUrl,
                    )
                )
            }
        } catch (e: Exception) {
            Result.Error(e.message ?: e.javaClass.simpleName)
        }
    }

    /** Picks the release APK matching this device's ABI, else the universal one. */
    private fun pickApk(assets: JsonArray?): String? {
        if (assets == null) return null
        val apks = assets.mapNotNull { it as? kotlinx.serialization.json.JsonObject }
            .mapNotNull { a ->
                val name = a["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val url = a["browser_download_url"]?.jsonPrimitive?.content ?: return@mapNotNull null
                if (name.endsWith(".apk")) name to url else null
            }
        if (apks.isEmpty()) return null
        for (abi in Build.SUPPORTED_ABIS) {
            apks.firstOrNull { it.first.contains(abi) }?.let { return it.second }
        }
        return apks.firstOrNull { it.first.contains("universal") }?.second ?: apks.first().second
    }

    /** Downloads [url] to cacheDir/updates, reporting 0..1 progress. */
    suspend fun downloadApk(context: Context, url: String, onProgress: (Float) -> Unit): File? =
        withContext(Dispatchers.IO) {
            try {
                val dir = File(context.cacheDir, "updates").apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() }
                val file = File(dir, "grkax-update.apk")

                val request = Request.Builder().url(url)
                    .header("User-Agent", "GrKaX/${BuildConfig.VERSION_NAME}").build()
                client.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext null
                    val bodyStream = resp.body?.byteStream() ?: return@withContext null
                    val total = resp.body?.contentLength() ?: -1L
                    var read = 0L
                    file.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = bodyStream.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            read += n
                            if (total > 0) onProgress((read.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }
                }
                file
            } catch (e: Exception) {
                null
            }
        }

    fun installApk(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    private fun isNewer(current: String, latest: String): Boolean {
        if (latest.isBlank()) return false
        val c = current.split('.', '-').mapNotNull { it.toIntOrNull() }
        val l = latest.split('.', '-').mapNotNull { it.toIntOrNull() }
        val size = maxOf(c.size, l.size)
        for (i in 0 until size) {
            val cv = c.getOrElse(i) { 0 }
            val lv = l.getOrElse(i) { 0 }
            if (lv > cv) return true
            if (lv < cv) return false
        }
        return false
    }
}
