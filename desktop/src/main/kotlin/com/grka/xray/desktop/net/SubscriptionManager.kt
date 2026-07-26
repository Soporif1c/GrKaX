package com.grka.xray.desktop.net

import com.grka.xray.desktop.AppVersion
import com.grka.xray.desktop.config.JsonSubscriptionParser
import com.grka.xray.desktop.config.LinkParser
import com.grka.xray.desktop.data.Store
import com.grka.xray.desktop.data.Subscription
import com.grka.xray.desktop.util.Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Locale

object SubscriptionManager {

    data class UpdateResult(val ok: Boolean, val count: Int = 0, val error: String? = null)

    suspend fun update(sub: Subscription): UpdateResult = withContext(Dispatchers.IO) {
        try {
            // Identify as ourselves by default. Panels that serve different
            // formats per client can be satisfied by setting a per-subscription
            // User-Agent (e.g. "Happ/3.13.0"); a bare "Happ" is expanded to a
            // full Happ UA so a "user-agent contains happ" rule matches.
            val uaRaw = sub.userAgent?.takeIf { it.isNotBlank() }
            val ua = when {
                uaRaw == null -> "GrKaX/${AppVersion.name}"
                uaRaw.equals("happ", ignoreCase = true) -> "Happ/3.13.0"
                else -> uaRaw
            }

            val builder = HttpRequest.newBuilder()
                .uri(URI.create(sub.url))
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", ua)
                .header("Accept", "application/json, text/plain, */*")
                // Device headers Remnawave "Response Rules" match on. The OS
                // value is what decides which template the panel returns, so a
                // macOS rule on the panel side is what you want here.
                .header("x-device-os", "macOS")
                .header("x-ver-os", System.getProperty("os.version") ?: "")
                .header("x-device-model", "Mac")
                .header("x-device-locale", Locale.getDefault().toLanguageTag())
                .GET()
            if (Store.hwidEnabled) {
                builder.header("x-hwid", Utils.hwid)
            }

            val response = Http.client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in 200..299) {
                return@withContext UpdateResult(false, error = "HTTP ${response.statusCode()}")
            }
            val body = response.body()
            if (body.isNullOrBlank()) {
                return@withContext UpdateResult(false, error = "Пустой ответ")
            }

            // Prefer an xray-json body (Remnawave); fall back to link lists.
            var routingJson: String? = null
            val profiles = if (JsonSubscriptionParser.looksLikeJson(body)) {
                val parsed = JsonSubscriptionParser.parse(body)
                if (parsed != null) {
                    routingJson = parsed.routingJson
                    parsed.profiles
                } else {
                    LinkParser.parseBatch(body)
                }
            } else {
                LinkParser.parseBatch(body)
            }

            if (profiles.isEmpty()) {
                return@withContext UpdateResult(false, error = "В ответе нет серверов")
            }
            for (p in profiles) p.subId = sub.id

            sub.routingJson = routingJson
            sub.rawBody = body.take(200_000) // keep for inspection (bounded)
            Store.replaceSubscriptionProfiles(sub.id, profiles)

            response.headers().firstValue("subscription-userinfo").ifPresent { applyUserInfo(sub, it) }
            response.headers().firstValue("profile-title").ifPresent { title ->
                if (sub.name.isBlank()) sub.name = decodeProfileTitle(title)
            }
            sub.lastUpdate = System.currentTimeMillis()
            Store.saveSubscription(sub)
            UpdateResult(true, profiles.size)
        } catch (e: Exception) {
            UpdateResult(false, error = e.message ?: e.javaClass.simpleName)
        }
    }

    suspend fun updateAll(): List<Pair<Subscription, UpdateResult>> =
        Store.subsFlow.value.map { sub -> sub to update(sub) }

    /** Parses "upload=123; download=456; total=789; expire=1700000000". */
    private fun applyUserInfo(sub: Subscription, header: String) {
        for (part in header.split(";")) {
            val kv = part.trim().split("=")
            if (kv.size != 2) continue
            val value = kv[1].trim().toDoubleOrNull()?.toLong() ?: continue
            when (kv[0].trim().lowercase()) {
                "upload" -> sub.upload = value
                "download" -> sub.download = value
                "total" -> sub.total = value
                "expire" -> sub.expire = value
            }
        }
    }

    private fun decodeProfileTitle(title: String): String {
        val prefix = "base64:"
        return if (title.startsWith(prefix)) {
            Utils.tryDecodeBase64(title.removePrefix(prefix)) ?: title
        } else {
            title
        }
    }
}
