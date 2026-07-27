package com.grka.xray.desktop.platform

import com.grka.xray.desktop.config.LinkParser
import com.grka.xray.desktop.core.CoreRuntime
import com.grka.xray.desktop.data.Store
import com.grka.xray.desktop.data.Subscription
import com.grka.xray.desktop.net.SubscriptionManager
import com.grka.xray.desktop.util.Utils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.awt.Desktop
import java.net.URI

/**
 * Handles `grkax://` links so a subscription page can offer a one-click
 * "add to GrKa X" button, exactly as on Android. The scheme itself is declared
 * in the bundle's Info.plist; macOS then delivers the URL as an AppleEvent,
 * which the JDK surfaces through [Desktop.setOpenURIHandler].
 */
object DeepLink {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val serverSchemes = listOf("vless://", "vmess://", "trojan://", "ss://")

    fun register() {
        runCatching {
            val desktop = Desktop.getDesktop()
            if (desktop.isSupported(Desktop.Action.APP_OPEN_URI)) {
                desktop.setOpenURIHandler { event -> handle(event.uri.toString()) }
            }
        }
    }

    fun handle(raw: String) {
        val text = raw.trim()
        when {
            text.startsWith("grkax://", ignoreCase = true) -> handleOwnScheme(text)
            serverSchemes.any { text.startsWith(it, ignoreCase = true) } -> importServers(text)
            else -> CoreRuntime.log("Ссылка не распознана: $text")
        }
    }

    private fun handleOwnScheme(text: String) {
        val uri = runCatching { URI(text) }.getOrNull() ?: return
        // Both shapes the Android client accepts:
        //   grkax://install-sub?url=<URL>   and   grkax://import/<URL|links>
        val fromQuery = uri.rawQuery
            ?.split("&")
            ?.firstOrNull { it.startsWith("url=") }
            ?.removePrefix("url=")
            ?.let { Utils.urlDecode(it) }

        val fromPath = uri.path?.trimStart('/')?.takeIf { it.isNotBlank() }?.let { Utils.urlDecode(it) }

        val payload = fromQuery ?: fromPath
        if (payload.isNullOrBlank()) {
            CoreRuntime.log("В ссылке нет адреса подписки: $text")
            return
        }

        if (serverSchemes.any { payload.startsWith(it, ignoreCase = true) } || !payload.startsWith("http")) {
            importServers(payload)
        } else {
            addSubscription(payload)
        }
    }

    private fun addSubscription(url: String) {
        val existing = Store.subsFlow.value.firstOrNull { it.url == url }
        val sub = existing ?: Subscription(url = url, userAgent = "Happ").also { Store.saveSubscription(it) }
        CoreRuntime.log("Добавляю подписку: $url")
        scope.launch {
            val result = SubscriptionManager.update(sub)
            CoreRuntime.log(
                if (result.ok) "Подписка обновлена, серверов: ${result.count}"
                else "Не удалось загрузить подписку: ${result.error}",
            )
        }
    }

    private fun importServers(text: String) {
        val profiles = LinkParser.parseBatch(text)
        if (profiles.isEmpty()) {
            CoreRuntime.log("Не удалось разобрать ссылку на сервер")
            return
        }
        profiles.forEach { Store.saveProfile(it) }
        CoreRuntime.log("Добавлено серверов: ${profiles.size}")
    }
}
