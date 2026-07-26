package com.grka.xray.desktop.net

import com.grka.xray.desktop.AppConfig
import com.grka.xray.desktop.AppVersion
import com.grka.xray.desktop.util.Platform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** Checks GitHub releases for a newer build and fetches the matching .dmg. */
object UpdateChecker {

    private val json = Json { ignoreUnknownKeys = true }

    data class Release(
        val version: String,
        val notes: String,
        val downloadUrl: String?,
        val pageUrl: String,
    )

    suspend fun latest(): Release? = withContext(Dispatchers.IO) {
        runCatching {
            val request = HttpRequest.newBuilder(URI.create("${AppConfig.RELEASES_API}?per_page=10"))
                .timeout(Duration.ofSeconds(20))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "GrKaX/${AppVersion.name}")
                .GET()
                .build()
            val response = Http.client.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in 200..299) return@runCatching null

            val releases = json.parseToJsonElement(response.body()) as? JsonArray ?: return@runCatching null
            // Releases are newest-first; drafts are useless to us, pre-releases
            // are not — the whole project ships as 0.x pre-releases.
            val release = releases.filterIsInstance<JsonObject>()
                .firstOrNull { it["draft"]?.jsonPrimitive?.booleanOrNull != true }
                ?: return@runCatching null

            val tag = release["tag_name"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val assets = (release["assets"] as? JsonArray)?.filterIsInstance<JsonObject>().orEmpty()
            val arch = if (Platform.isArm64) "arm64" else "x64"
            val dmg = assets.firstOrNull { asset ->
                val name = asset["name"]?.jsonPrimitive?.contentOrNull.orEmpty().lowercase()
                name.endsWith(".dmg") && name.contains(arch)
            } ?: assets.firstOrNull {
                it["name"]?.jsonPrimitive?.contentOrNull.orEmpty().endsWith(".dmg", true)
            }

            Release(
                version = tag.removePrefix("mac-v").removePrefix("v"),
                notes = release["body"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                downloadUrl = dmg?.get("browser_download_url")?.jsonPrimitive?.contentOrNull,
                pageUrl = release["html_url"]?.jsonPrimitive?.contentOrNull ?: AppConfig.RELEASES_PAGE,
            )
        }.getOrNull()
    }

    /** True when [candidate] sorts after the running version. */
    fun isNewer(candidate: String, current: String = AppVersion.name): Boolean {
        fun parts(v: String) = v.trim().split(".", "-").mapNotNull { it.toIntOrNull() }
        val a = parts(candidate)
        val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** Downloads the .dmg to ~/Downloads and returns it. */
    suspend fun download(url: String): File? = withContext(Dispatchers.IO) {
        runCatching {
            val name = url.substringAfterLast('/').ifBlank { "GrKaX.dmg" }
            val target = File(System.getProperty("user.home"), "Downloads").let { dir ->
                if (dir.isDirectory) File(dir, name) else File(Platform.dataDir, name)
            }
            val request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(10))
                .header("User-Agent", "GrKaX/${AppVersion.name}")
                .GET()
                .build()
            // ofFile, not ofFileDownload: the latter needs a Content-Disposition
            // header, which release asset redirects do not always carry.
            val response = Http.client.send(request, HttpResponse.BodyHandlers.ofFile(target.toPath()))
            if (response.statusCode() !in 200..299) null else response.body().toFile()
        }.getOrNull()
    }
}
