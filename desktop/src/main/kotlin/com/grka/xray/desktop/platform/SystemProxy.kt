package com.grka.xray.desktop.platform

import com.grka.xray.desktop.core.CoreRuntime
import com.grka.xray.desktop.util.Shell

/**
 * Points macOS at the local inbounds by writing the proxy into the active
 * network service. Costs no privileges for admin users; when `networksetup`
 * refuses (standard accounts), we retry the whole batch behind one
 * authorization prompt rather than failing.
 */
object SystemProxy {

    private const val NETWORKSETUP = "/usr/sbin/networksetup"

    /** Hosts that must never be sent to the proxy. */
    private val bypassDomains = listOf(
        "127.0.0.1", "localhost", "*.local", "169.254/16",
        "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16",
    )

    @Volatile
    private var appliedService: String? = null

    fun enable(socksPort: Int, httpPort: Int): String? {
        val service = MacNet.activeService()
            ?: return "Не удалось определить активную сеть (Wi-Fi / Ethernet)"

        val commands = listOf(
            listOf(NETWORKSETUP, "-setwebproxy", service, "127.0.0.1", httpPort.toString()),
            listOf(NETWORKSETUP, "-setsecurewebproxy", service, "127.0.0.1", httpPort.toString()),
            listOf(NETWORKSETUP, "-setsocksfirewallproxy", service, "127.0.0.1", socksPort.toString()),
            listOf(NETWORKSETUP, "-setproxybypassdomains", service) + bypassDomains,
        )

        val failed = commands.firstOrNull { !Shell.run(*it.toTypedArray()).ok }
        if (failed != null) {
            // Most likely a non-admin account: do it all in one elevated shot.
            val script = commands.joinToString(" && ") { cmd ->
                cmd.joinToString(" ") { quote(it) }
            }
            val elevated = Shell.runAsAdmin(script)
            if (!elevated.ok) {
                return "Не удалось включить системный прокси: ${elevated.message()}"
            }
        }

        appliedService = service
        CoreRuntime.log("Системный прокси включён для «$service» (SOCKS $socksPort, HTTP $httpPort)")
        return null
    }

    /**
     * Turns off a proxy left pointing at a core that is no longer running —
     * the same crash-and-SIGKILL gap [TunMode.cleanStale] covers, with the
     * milder symptom of everything failing to connect rather than the routes
     * being wrong.
     *
     * Deliberately narrow: it must be our own loopback address and port, with
     * nothing listening there. Another client's proxy, or our own from a second
     * running instance, is left untouched.
     */
    fun cleanStale(socksPort: Int) {
        val service = MacNet.activeService() ?: return
        val current = Shell.capture(NETWORKSETUP, "-getsocksfirewallproxy", service)
        val ours = current.contains("Enabled: Yes") &&
            current.contains("127.0.0.1") &&
            current.contains("Port: $socksPort")
        if (!ours) return
        if (Shell.capture("/usr/sbin/lsof", "-nP", "-iTCP:$socksPort", "-sTCP:LISTEN").isNotBlank()) {
            return
        }

        CoreRuntime.log("Найден системный прокси от прошлого запуска — выключаю")
        disable()
    }

    fun disable() {
        val service = appliedService ?: MacNet.activeService() ?: return
        appliedService = null
        val commands = listOf(
            listOf(NETWORKSETUP, "-setwebproxystate", service, "off"),
            listOf(NETWORKSETUP, "-setsecurewebproxystate", service, "off"),
            listOf(NETWORKSETUP, "-setsocksfirewallproxystate", service, "off"),
        )
        val failed = commands.firstOrNull { !Shell.run(*it.toTypedArray()).ok }
        if (failed != null) {
            val script = commands.joinToString(" && ") { cmd -> cmd.joinToString(" ") { quote(it) } }
            Shell.runAsAdmin(script)
        }
        CoreRuntime.log("Системный прокси выключен")
    }

    private fun quote(value: String): String =
        if (value.any { it.isWhitespace() }) "'" + value.replace("'", "'\\''") + "'" else value
}
