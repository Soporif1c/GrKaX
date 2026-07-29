package com.grka.xray.desktop.platform

import com.grka.xray.desktop.core.CoreRuntime
import com.grka.xray.desktop.data.Store
import com.grka.xray.desktop.util.Platform
import com.grka.xray.desktop.util.Shell
import java.io.File
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
 * back into the tunnel it feeds. Two things keep it out, and both are needed:
 * `sockopt.interface` in ConfigBuilder, which binds outbound sockets to the
 * physical interface, and the /32 route installed here before the /1 routes.
 * The binding alone is not enough — the core has to know which address to dial
 * before it can dial it, and the lookup would go through the tunnel.
 */
object TunMode {

    /** Runtime state written by the helper script; the path must match it. */
    private val stateDir = File("/var/run/grkax")

    @Volatile
    private var appliedService: String? = null

    /**
     * The physical interface this tunnel was built on, remembered for as long
     * as it is up. Once the /1 routes are in place the default route points at
     * our own utun device, so anything that needs the real interface after that
     * has to read it here rather than ask the route table — see
     * [com.grka.xray.desktop.data.Store.settingsSnapshot].
     */
    @Volatile
    var physicalInterface: String? = null
        private set

    @Volatile
    private var pinKey: Set<String> = emptySet()

    @Volatile
    private var pins: Map<String, List<String>> = emptyMap()

    fun enable(socksPort: Int, serverHosts: List<String>): String? {
        if (!Platform.isMac) return "TUN-режим пока реализован только для macOS"

        val iface = MacNet.defaultInterface()
            ?: return "Не удалось определить сетевой интерфейс"
        val service = MacNet.serviceForDevice(iface)
            ?: return "Не удалось определить активную сеть для $iface"
        val gateway = MacNet.defaultGateway()
            ?: return "Не удалось определить шлюз — без него сервер окажется внутри туннеля"

        // Every known server is pinned, not just the selected one, so switching
        // servers later is a core restart and nothing more — no route changes,
        // and therefore no second password prompt. Resolved here while the
        // tunnel is still down and DNS works normally. Only IPv4: the tunnel
        // captures IPv4 alone, so an IPv6 server needs no pin.
        val serverIps = serverPins(serverHosts).values.flatten().distinct()

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
        physicalInterface = iface
        CoreRuntime.log("TUN поднят (${result.out.trim()}), интерфейс $iface, сеть «$service»")
        if (serverIps.isEmpty()) {
            // Not fatal — an IPv6-only or unresolvable server may still work —
            // but it is the first thing to look at if nothing loads.
            CoreRuntime.log("Внимание: адреса серверов не закреплены за $gateway, возможна петля")
        } else {
            CoreRuntime.log("Вне туннеля через $gateway: ${serverIps.joinToString(", ")}")
        }
        return null
    }

    /**
     * hostname → IPv4 for every known server, for both the host routes here and
     * the core's static DNS table.
     *
     * Resolved only while the tunnel is down. Once it is up, this lookup would
     * go through the tunnel like everything else — and the tunnel cannot carry
     * it until the server connection it is meant to establish already exists.
     * So the answers are taken before that door closes and cached for as long
     * as the tunnel lives, which is also what lets a mode or server switch
     * rebuild the config without asking anything of the network.
     */
    fun serverPins(hosts: List<String>): Map<String, List<String>> {
        if (physicalInterface != null) return pins
        val key = hosts.filter { it.isNotBlank() }.toSet()
        if (key != pinKey) {
            pins = resolveIpv4(key)
            pinKey = key
        }
        return pins
    }

    /**
     * Parallel on purpose: a subscription can carry dozens of servers, and one
     * after another a few unreachable names would stall the connect behind
     * their DNS timeouts.
     */
    private fun resolveIpv4(hosts: Set<String>): Map<String, List<String>> =
        hosts.parallelStream()
            .map { host -> host to lookupIpv4(host) }
            .filter { it.second.isNotEmpty() }
            .toList()
            .toMap()

    private fun lookupIpv4(host: String): List<String> = try {
        InetAddress.getAllByName(host)
            .filterIsInstance<Inet4Address>()
            .mapNotNull { it.hostAddress }
            .distinct()
    } catch (e: UnknownHostException) {
        CoreRuntime.log("Не удалось разрешить $host: ${e.message}")
        emptyList()
    }

    /**
     * Tears down a tunnel left behind by a run that never cleaned up after
     * itself. The shutdown hook handles a normal exit, but a SIGKILL or a crash
     * leaves the /1 routes and the DNS override in place with no tun2socks
     * behind them — every packet then goes to a tunnel that no longer exists,
     * and the machine stays offline until someone removes the routes by hand.
     *
     * Recognised by the state files outliving the process they describe. A live
     * pid means a tunnel that is genuinely running, which is left alone.
     */
    fun cleanStale() {
        if (!Platform.isMac) return
        if (!File(stateDir, "tun.dev").isFile) return

        val pid = File(stateDir, "tun2socks.pid")
            .takeIf { it.isFile }
            ?.runCatching { readText().trim().toLong() }
            ?.getOrNull()
        val alive = pid != null && ProcessHandle.of(pid).map { it.isAlive }.orElse(false)
        if (alive) return

        CoreRuntime.log("Найден туннель от прошлого запуска — снимаю, чтобы вернуть сеть")
        disable()
    }

    fun disable() {
        if (!Platform.isMac) return
        val service = appliedService ?: MacNet.activeService() ?: ""
        appliedService = null
        physicalInterface = null
        // Addresses can move between sessions; the next connect resolves afresh.
        pinKey = emptySet()
        pins = emptyMap()
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
