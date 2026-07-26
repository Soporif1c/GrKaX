package com.grka.xray.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.grka.xray.R
import com.grka.xray.config.LinkParser
import com.grka.xray.data.Store
import com.grka.xray.data.Subscription
import com.grka.xray.net.SubscriptionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URLDecoder

/**
 * Handles import deep links so an "Add to GrKa X" button on a subscription page
 * (or a tapped proxy link) opens the app and imports it. Supports:
 *   grkax://install-sub?url=<url>   ·   grkax://import/<url-or-links>
 *   vless:// vmess:// trojan:// ss://   (single server)
 *   and common third-party schemes (v2rayng, v2raytun, hiddify, …) whose
 *   "install-sub"/"import" links carry a url= param or a trailing url.
 */
class ImportActivity : ComponentActivity() {

    companion object {
        // Process-scoped: this activity finishes immediately, so the fetch must
        // not hold a reference to it (and must not be cancelled with it).
        private val importScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handle(applicationContext, intent?.data)
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        )
        finish()
    }

    private fun handle(context: Context, data: Uri?) {
        if (data == null) return
        val raw = data.toString()
        val scheme = data.scheme?.lowercase()

        // Direct proxy links → import as a server.
        if (scheme in setOf("vless", "vmess", "trojan", "ss")) {
            importLinks(context, raw)
            return
        }

        val target = extractTarget(data, raw)
        if (target.isNullOrBlank()) {
            toast(context, context.getString(R.string.import_none))
            return
        }
        if (target.startsWith("http://", true) || target.startsWith("https://", true)) {
            addSubscription(context, target)
        } else {
            // Could be a link list or base64 payload carried by the scheme.
            importLinks(context, target)
        }
    }

    /** Pulls the subscription URL out of an import deep link. */
    private fun extractTarget(uri: Uri, raw: String): String? {
        // getQueryParameter throws on opaque URIs (e.g. "grkax:install-sub?url=…"),
        // so guard it and fall back to manual parsing below.
        runCatching { uri.getQueryParameter("url") }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }

        // Manual "url=" lookup covers opaque URIs and odd encodings.
        Regex("[?&]url=([^&]+)").find(raw)?.groupValues?.getOrNull(1)
            ?.takeIf { it.isNotBlank() }
            ?.let { return decode(it) }

        for (marker in listOf("://import/", "://install-sub/", "://install-config/", "://add/",
                              ":import/", ":install-sub/")) {
            val idx = raw.indexOf(marker)
            if (idx >= 0) {
                val rest = raw.substring(idx + marker.length).substringBefore('?')
                if (rest.isNotBlank()) return decode(rest)
            }
        }
        return null
    }

    private fun addSubscription(context: Context, url: String) {
        val sub = Subscription(name = hostName(url), url = url)
        Store.saveSubscription(sub)
        importScope.launch {
            val result = SubscriptionManager.update(context, sub)
            val msg = if (result.ok) context.getString(R.string.import_sub_added, result.count)
            else context.getString(R.string.import_sub_saved)
            withContext(Dispatchers.Main) { toast(context, msg) }
        }
    }

    private fun importLinks(context: Context, text: String) {
        val parsed = LinkParser.parseBatch(text)
        if (parsed.isEmpty()) {
            toast(context, context.getString(R.string.import_none))
        } else {
            parsed.forEach { Store.saveProfile(it) }
            toast(context, context.getString(R.string.import_ok, parsed.size))
        }
    }

    private fun hostName(url: String): String =
        runCatching { Uri.parse(url).host ?: "Sub" }.getOrDefault("Sub")

    private fun decode(s: String): String = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)

    private fun toast(context: Context, message: String) =
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
}
