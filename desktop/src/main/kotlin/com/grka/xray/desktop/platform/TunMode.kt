package com.grka.xray.desktop.platform

import com.grka.xray.desktop.core.CoreRuntime
import com.grka.xray.desktop.data.Store
import com.grka.xray.desktop.util.Platform
import com.grka.xray.desktop.util.Shell

/**
 * Full-device tunnel: a utun interface fed into our SOCKS inbound by
 * `tun2socks`. Catches traffic from apps that ignore the system proxy.
 *
 * Creating a utun device and editing the route table need root, and without an
 * Apple Developer account we cannot ship a code-signed privileged helper — so
 * the work happens in [grkax-tun.sh] behind a single authorization prompt.
 *
 * The loop hazard (the core's own outbound being routed back into the tunnel)
 * is handled on the core side: in TUN mode every outbound is pinned to the
 * physical interface via `sockopt.interface`, see ConfigBuilder.
 */
object TunMode {

    @Volatile
    private var appliedService: String? = null

    fun enable(socksPort: Int): String? {
        if (!Platform.isMac) return "TUN-режим пока реализован только для macOS"

        val iface = MacNet.defaultInterface()
            ?: return "Не удалось определить сетевой интерфейс"
        val service = MacNet.serviceForDevice(iface)
            ?: return "Не удалось определить активную сеть для $iface"

        val script = Platform.bundled("grkax-tun.sh")
        if (!script.isFile) return "Не найден помощник grkax-tun.sh в бандле приложения"
        val tun2socks = Platform.bundled("tun2socks")
        if (!tun2socks.isFile) return "Не найден tun2socks в бандле приложения"

        val command = listOf(
            "/bin/sh", script.absolutePath, "up",
            tun2socks.absolutePath, socksPort.toString(), iface, service, Store.remoteDns,
        ).joinToString(" ") { quote(it) }

        val result = Shell.runAsAdmin(command)
        if (!result.ok) {
            return "Не удалось поднять TUN: ${result.message()}"
        }
        appliedService = service
        CoreRuntime.log("TUN поднят (${result.out.trim()}), интерфейс $iface, сеть «$service»")
        return null
    }

    fun disable() {
        if (!Platform.isMac) return
        val service = appliedService ?: MacNet.activeService() ?: ""
        appliedService = null
        val script = Platform.bundled("grkax-tun.sh")
        if (!script.isFile) return
        val command = listOf("/bin/sh", script.absolutePath, "down", service)
            .joinToString(" ") { quote(it) }
        Shell.runAsAdmin(command)
        CoreRuntime.log("TUN снят")
    }

    private fun quote(value: String): String =
        if (value.isEmpty() || value.any { it.isWhitespace() }) {
            "'" + value.replace("'", "'\\''") + "'"
        } else {
            value
        }
}
