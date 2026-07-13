package com.grka.xray.net

import android.content.Context
import android.os.Build
import com.grka.xray.BuildConfig
import com.grka.xray.config.JsonSubscriptionParser
import com.grka.xray.config.LinkParser
import com.grka.xray.data.Store
import com.grka.xray.data.Subscription
import com.grka.xray.util.Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Locale
import java.util.concurrent.TimeUnit

object SubscriptionManager {

    data class UpdateResult(val ok: Boolean, val count: Int = 0, val error: String? = null)

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    suspend fun update(context: Context, sub: Subscription): UpdateResult = withContext(Dispatchers.IO) {
        try {
            // Some panels (Remnawave) return different formats per client. A
            // custom UA (e.g. "Happ") lets the user request the xray-json with
            // routing. A bare "Happ" is expanded to a realistic Happ UA so the
            // panel's "user-agent contains happ" rule matches reliably.
            val uaRaw = sub.userAgent?.takeIf { it.isNotBlank() }
            val ua = when {
                uaRaw == null -> "GrKaX/${BuildConfig.VERSION_NAME}"
                uaRaw.contains("happ", ignoreCase = true) && !uaRaw.contains("/") -> "Happ/3.13.0"
                else -> uaRaw
            }
            val requestBuilder = Request.Builder()
                .url(sub.url)
                .header("User-Agent", ua)
                .header("Accept", "application/json, text/plain, */*")
                // Device headers, matching what Happ sends. Remnawave "Response
                // Rules" match on these (e.g. UA contains "happ" AND x-device-os
                // contains "android") to return xray-json with the routing template.
                .header("x-device-os", "Android")
                .header("x-ver-os", Build.VERSION.RELEASE ?: "")
                .header("x-device-model", Build.MODEL ?: "")
                .header("x-device-locale", Locale.getDefault().toLanguageTag())
            if (Store.hwidEnabled) {
                // Unique device id — required by panels enforcing a per-sub
                // device limit; kept behind the toggle for privacy.
                requestBuilder.header("x-hwid", Utils.hwid(context))
            }

            client.newCall(requestBuilder.build()).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext UpdateResult(false, error = "HTTP ${resp.code}")
                }
                val body = resp.body!!.string()
                if (body.isBlank()) {
                    return@withContext UpdateResult(false, error = "Empty response")
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
                    return@withContext UpdateResult(false, error = "No servers in response")
                }
                for (p in profiles) {
                    p.subId = sub.id
                }
                sub.routingJson = routingJson
                sub.rawBody = body.take(200_000) // keep for inspection (bounded)
                Store.replaceSubscriptionProfiles(sub.id, profiles)

                resp.header("subscription-userinfo")?.let { applyUserInfo(sub, it) }
                resp.header("profile-title")?.let { title ->
                    if (sub.name.isBlank()) {
                        sub.name = decodeProfileTitle(title)
                    }
                }
                sub.lastUpdate = System.currentTimeMillis()
                Store.saveSubscription(sub)
                UpdateResult(true, profiles.size)
            }
        } catch (e: Exception) {
            UpdateResult(false, error = e.message ?: e.javaClass.simpleName)
        }
    }

    suspend fun updateAll(context: Context): List<Pair<Subscription, UpdateResult>> {
        val results = mutableListOf<Pair<Subscription, UpdateResult>>()
        for (sub in Store.subsFlow.value) {
            results.add(sub to update(context, sub))
        }
        return results
    }

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
