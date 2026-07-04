package com.grka.xray.net

import com.grka.xray.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

object UpdateChecker {

    const val REPO = "Soporif1c/GrKaX"

    data class UpdateInfo(
        val latestVersion: String,
        val currentVersion: String,
        val isNewer: Boolean,
        val htmlUrl: String,
        val notes: String,
    )

    sealed class Result {
        data class Success(val info: UpdateInfo) : Result()
        data class Error(val message: String) : Result()
    }

    private val json = Json { ignoreUnknownKeys = true }

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    suspend fun check(): Result = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("https://api.github.com/repos/$REPO/releases/latest")
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "GrKaX/${BuildConfig.VERSION_NAME}")
                .build()

            client.newCall(request).execute().use { resp ->
                if (resp.code == 404) {
                    return@withContext Result.Error("No releases published yet")
                }
                if (!resp.isSuccessful) {
                    return@withContext Result.Error("HTTP ${resp.code}")
                }
                val body = resp.body?.string().orEmpty()
                val obj = json.parseToJsonElement(body).jsonObject
                val tag = obj["tag_name"]?.jsonPrimitive?.content.orEmpty()
                val htmlUrl = obj["html_url"]?.jsonPrimitive?.content
                    ?: "https://github.com/$REPO/releases"
                val notes = obj["body"]?.jsonPrimitive?.content.orEmpty()

                val latest = tag.trimStart('v', 'V').trim()
                val current = BuildConfig.VERSION_NAME
                Result.Success(
                    UpdateInfo(
                        latestVersion = latest.ifEmpty { current },
                        currentVersion = current,
                        isNewer = isNewer(current, latest),
                        htmlUrl = htmlUrl,
                        notes = notes,
                    )
                )
            }
        } catch (e: Exception) {
            Result.Error(e.message ?: e.javaClass.simpleName)
        }
    }

    /** Returns true if [latest] is a strictly higher semantic version than [current]. */
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
