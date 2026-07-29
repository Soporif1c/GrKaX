package com.grka.xray.desktop.platform

import com.grka.xray.desktop.AppConfig
import com.grka.xray.desktop.util.Platform

/** Dispatches between the two ways of pointing the OS at the running core. */
object NetworkController {

    @Volatile
    private var active: String? = null

    /** [serverHosts] are pinned outside the tunnel in TUN mode; see [TunMode]. */
    fun enable(mode: String, socksPort: Int, httpPort: Int, serverHosts: List<String>): String? {
        disable()
        if (!Platform.isMac) {
            // Windows/Linux plumbing is not wired up yet; the core still runs,
            // so a manually configured proxy works.
            active = null
            return null
        }
        val error = when (mode) {
            AppConfig.NET_TUN -> TunMode.enable(socksPort, serverHosts)
            else -> SystemProxy.enable(socksPort, httpPort)
        }
        if (error == null) active = mode
        return error
    }

    fun disable() {
        when (active) {
            AppConfig.NET_TUN -> TunMode.disable()
            AppConfig.NET_SYSTEM_PROXY -> SystemProxy.disable()
        }
        active = null
    }
}
