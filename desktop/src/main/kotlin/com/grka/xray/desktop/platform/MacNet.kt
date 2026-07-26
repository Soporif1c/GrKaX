package com.grka.xray.desktop.platform

import com.grka.xray.desktop.util.Shell

/** Discovery helpers for the active macOS network service and interface. */
object MacNet {

    /** Physical interface carrying the default route, e.g. `en0`. */
    fun defaultInterface(): String? {
        val out = Shell.capture("/sbin/route", "-n", "get", "default")
        return Regex("interface:\\s*(\\S+)").find(out)?.groupValues?.get(1)
    }

    /** Gateway of the default route, needed to keep the proxy server reachable. */
    fun defaultGateway(): String? {
        val out = Shell.capture("/sbin/route", "-n", "get", "default")
        return Regex("gateway:\\s*(\\S+)").find(out)?.groupValues?.get(1)
    }

    /**
     * Name of the network service (as System Settings shows it, e.g. "Wi-Fi")
     * that owns [device]. `networksetup` only accepts service names, while the
     * routing table only knows device names, so the two have to be matched up.
     */
    fun serviceForDevice(device: String): String? {
        val out = Shell.capture("/usr/sbin/networksetup", "-listnetworkserviceorder")
        // Blocks look like:
        //   (1) Wi-Fi
        //   (Hardware Port: Wi-Fi, Device: en0)
        var lastService: String? = null
        for (line in out.lineSequence()) {
            val trimmed = line.trim()
            val service = Regex("^\\(\\d+\\)\\s+(.*)$").find(trimmed)?.groupValues?.get(1)
            if (service != null) {
                lastService = service.trim()
                continue
            }
            val dev = Regex("Device:\\s*([^)\\s,]+)").find(trimmed)?.groupValues?.get(1)
            if (dev != null && dev == device) return lastService
        }
        return null
    }

    /** The service the system is actually routing through, if it can be found. */
    fun activeService(): String? = defaultInterface()?.let { serviceForDevice(it) }
}
