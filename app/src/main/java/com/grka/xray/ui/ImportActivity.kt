package com.grka.xray.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
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
import java.net.URLDecoder

/**
 * Handles import deep links so a "Add to GrKa X" button on a subscription page
 * (or a tapped proxy link) opens the app and imports it. Supports:
 *   grkax://install-sub?url=<url>   ·   grkax://import/<url-or-links>
 *   vless:// vmess:// trojan:// ss://   (single server)
 *   and common third-party schemes (v2rayng, v2raytun, hiddify, …) whose
 *   "install-sub"/"import" links carry a url= param or a trailing url.
 */
class ImportActivity : ComponentActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handle(intent?.data)
        // Bring the user to the main screen.
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        )
        finish()
    }

    private fun handle(data: Uri?) {
        if (data == null) return
        val raw = data.toString()
        val scheme = data.scheme?.lowercase()

        // Direct proxy links → import as a server.
        if (scheme in setOf("vless", "vmess", "trojan", "ss")) {
            importLinks(raw)
            return
        }

        val target = extractTarget(data, raw)
        if (target.isNullOrBlank()) {
            toast(getString(R.string.import_none))
            return
        }
        if (target.startsWith("http://") || target.startsWith("https://")) {
            addSubscription(target)
        } else {
            // Could be a link list or base64 payload pasted into the scheme.
            importLinks(target)
        }
    }

    /** Pulls the subscription URL out of an import deep link. */
    private fun extractTarget(uri: Uri, raw: String): String? {
        uri.getQueryParameter("url")?.takeIf { it.isNotBlank() }?.let { return it }
        for (marker in listOf("://import/", "://install-sub/", "://install-config/", "://add/")) {
            val idx = raw.indexOf(marker)
            if (idx >= 0) {
                val rest = raw.substring(idx + marker.length).substringBefore('?')
                if (rest.isNotBlank()) return decode(rest)
            }
        }
        return null
    }

    private fun addSubscription(url: String) {
        val sub = Subscription(name = hostName(url), url = url)
        Store.saveSubscription(sub)
        val app: Context = applicationContext
        scope.launch {
            val result = SubscriptionManager.update(app, sub)
            val msg = if (result.ok) getString(R.string.import_sub_added, result.count)
            else getString(R.string.import_sub_saved)
            toast(msg)
        }
    }

    private fun importLinks(text: String) {
        val parsed = LinkParser.parseBatch(text)
        if (parsed.isEmpty()) {
            toast(getString(R.string.import_none))
        } else {
            parsed.forEach { Store.saveProfile(it) }
            toast(getString(R.string.import_ok, parsed.size))
        }
    }

    private fun hostName(url: String): String =
        runCatching { Uri.parse(url).host ?: "Sub" }.getOrDefault("Sub")

    private fun decode(s: String): String = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)

    private fun toast(message: String) =
        android.widget.Toast.makeText(applicationContext, message, android.widget.Toast.LENGTH_LONG).show()
}
