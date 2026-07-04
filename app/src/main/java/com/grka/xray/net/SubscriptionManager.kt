package com.grka.xray.net

import android.content.Context
import android.os.Build
import com.grka.xray.BuildConfig
import com.grka.xray.config.LinkParser
import com.grka.xray.data.Store
import com.grka.xray.data.Subscription
import com.grka.xray.util.Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
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
            val requestBuilder = Request.Builder()
                .url(sub.url)
                .header("User-Agent", "GrKaX/${BuildConfig.VERSION_NAME}")
            if (Store.hwidEnabled) {
                // Device headers required by panels (e.g. Remnawave) that
                // enforce a per-subscription device limit.
                requestBuilder.header("x-hwid", Utils.hwid(context))
                requestBuilder.header("x-device-os", "Android")
                requestBuilder.header("x-ver-os", Build.VERSION.RELEASE ?: "")
                requestBuilder.header("x-device-model", Build.MODEL ?: "")
            }

            client.newCall(requestBuilder.build()).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext UpdateResult(false, error = "HTTP ${resp.code}")
                }
                val body = resp.body!!.string()
                if (body.isBlank()) {
                    return@withContext UpdateResult(false, error = "Empty response")
                }

                val profiles = LinkParser.parseBatch(body)
                if (profiles.isEmpty()) {
                    return@withContext UpdateResult(false, error = "No servers in response")
                }
                for (p in profiles) {
                    p.subId = sub.id
                }
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
