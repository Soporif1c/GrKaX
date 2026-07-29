package com.grka.xray.desktop.platform

import com.grka.xray.desktop.core.CoreRuntime
import com.grka.xray.desktop.data.Store
import com.grka.xray.desktop.util.Platform
import com.grka.xray.desktop.util.Shell
import java.net.Inet4Address
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * Full-device tunnel: a utun interface fed into our SOCKS inbound by
 * `tun2socks`. Catches traffic from apps that ignore the system proxy.
 *
 * Creating a utun device and editing the route table need root, and without an
 * Apple Developer account we cannot ship a code-signed privileged helper — so
 * the work happens in [grkax-tun.sh] behind a single authorization prompt.
 *
 * The loop hazard is the core's own connection to the proxy server being routed
 * back into the tunnel it feeds. `sockopt.interface` in ConfigBuilder was meant
 * to prevent it, but on macOS it does not: with the tunnel up the core opened no
 * outbound socket at all, and a socket bound to the physical interface while the
 * route points at the tunnel is refused by the kernel outright. So the server is
 * pinned in the route table instead — a /32 via the physical gateway, installed
 * before the /1 routes. `sockopt.interface` is left in place as it is harmless
 * and still correct wherever the core does honour it.
 */
object TunMode {

    @Volatile
    private var appliedService: String? = null

    fun enable(socksPort: Int, serverHost: String?): String? {
        if (!Platform.isMac) return "TUN-режим пока реализован только для macOS"

        val iface = MacNet.defaultInterface()
            ?: return "Не удалось определить сетевой интерфейс"
        val service = MacNet.serviceForDevice(iface)
            ?: return "Не удалось определить активную сеть для $iface"
        val gateway = MacNet.defaultGateway()
            ?: return "Не удалось определить шлюз — без него сервер окажется внутри туннеля"

        // Resolved here, while the tunnel is still down and DNS works normally.
        // Only IPv4: the tunnel captures IPv4 alone, so an IPv6 server is
        // reachable regardless and needs no pin.
        val serverIps = resolveIpv4(serverHost)

        val script = Platform.bundled("grkax-tun.sh")
        if (!script.isFile) return "Не найден помощник grkax-tun.sh в бандле приложения"
        val tun2socks = Platform.bundled("tun2socks")
        if (!tun2socks.isFile) return "Не найден tun2socks в бандле приложения"
        // Packaging does not carry the executable bit through reliably, so
        // restore it the same way the core binary does.
        for (file in listOf(script, tun2socks)) {
            if (!file.canExecute()) file.setExecutable(true)
        }

        val command = (
            listOf(
                "/bin/sh", script.absolutePath, "up",
                tun2socks.absolutePath, socksPort.toString(), iface, service,
                Store.remoteDns, gateway,
            ) + serverIps
            ).joinToString(" ") { quote(it) }

        val result = Shell.runAsAdmin(command)
        if (!result.ok) {
            return "Не удалось поднять TUN: ${result.message()}"
        }
        appliedService = service
        CoreRuntime.log("TUN поднят (${result.out.trim()}), интерфейс $iface, сеть «$service»")
        if (serverIps.isEmpty()) {
            // Not fatal — an IPv6-only or unresolvable server may still work —
            // but it is the first thing to look at if nothing loads.
            CoreRuntime.log("Внимание: адрес сервера не закреплён за $gateway, возможна петля")
        } else {
            CoreRuntime.log("Сервер вне туннеля: ${serverIps.joinToString(", ")} через $gateway")
        }
        return null
    }

    /**
     * The proxy server's IPv4 addresses. A hostname is resolved rather than
     * passed through, because the route table takes addresses only — and by
     * the time the tunnel is up, resolving it would mean asking through the
     * very tunnel that cannot work until this route exists.
     */
    private fun resolveIpv4(host: String?): List<String> {
        if (host.isNullOrBlank()) return emptyList()
        return try {
            InetAddress.getAllByName(host)
                .filterIsInstance<Inet4Address>()
                .map { it.hostAddress }
                .distinct()
        } catch (e: UnknownHostException) {
            CoreRuntime.log("Не удалось разрешить адрес сервера $host: ${e.message}")
            emptyList()
        }
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
