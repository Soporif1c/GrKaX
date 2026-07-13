package com.grka.xray.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.grka.xray.AppConfig
import com.grka.xray.R
import com.grka.xray.core.ConnState
import com.grka.xray.core.CoreRuntime
import com.grka.xray.core.TProxyService
import com.grka.xray.data.Store
import com.grka.xray.util.Utils
import java.io.File

class XVpnService : VpnService() {

    companion object {
        const val ACTION_STOP = "com.grka.xray.action.STOP"
        const val NOTIFICATION_ID = 1
        const val CHANNEL_ID = "vpn_state"
        const val CHANNEL_ID_MIN = "vpn_state_min"
    }

    private var tunFd: ParcelFileDescriptor? = null
    private var running = false

    private val connectivity by lazy { getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager }
    private var callbackRegistered = false

    private val defaultNetworkRequest by lazy {
        NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
            .build()
    }

    private val defaultNetworkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            setUnderlyingNetworks(arrayOf(network))
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            setUnderlyingNetworks(arrayOf(network))
        }

        override fun onLost(network: Network) {
            setUnderlyingNetworks(null)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopVpn()
            return START_NOT_STICKY
        }
        if (running) {
            return START_STICKY
        }

        CoreRuntime.lastError.value = null
        CoreRuntime.state.value = ConnState.CONNECTING

        val profile = Store.selectedProfile()
        startAsForeground(profile?.name ?: getString(R.string.state_connecting))

        if (prepare(this) != null) {
            fail(getString(R.string.error_vpn_permission))
            return START_NOT_STICKY
        }
        if (profile == null) {
            fail(getString(R.string.error_no_server))
            return START_NOT_STICKY
        }

        if (!establishTun()) {
            fail(CoreRuntime.lastError.value ?: getString(R.string.error_tun_failed))
            return START_NOT_STICKY
        }

        try {
            startHevTunnel()
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "hev tunnel failed", e)
            fail(e.message ?: "tun2socks failed")
            return START_NOT_STICKY
        }

        CoreRuntime.onCoreShutdownRequested = { stopVpn() }
        if (!CoreRuntime.startCore(this, profile)) {
            fail(CoreRuntime.lastError.value ?: getString(R.string.error_core_failed))
            return START_NOT_STICKY
        }

        registerNetworkCallback()
        running = true
        CoreRuntime.state.value = ConnState.CONNECTED
        updateNotification(profile.name)
        Log.i(AppConfig.TAG, "VPN started with profile ${profile.name}")
        return START_STICKY
    }

    override fun onRevoke() {
        Log.w(AppConfig.TAG, "VPN permission revoked")
        stopVpn()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (running) {
            runCatching { tunFd?.close() }
            tunFd = null
            running = false
        }
    }

    private fun fail(message: String) {
        Log.e(AppConfig.TAG, "VPN start failed: $message")
        CoreRuntime.lastError.value = message
        teardown()
    }

    private fun stopVpn() {
        if (CoreRuntime.state.value == ConnState.STOPPING) return
        CoreRuntime.state.value = ConnState.STOPPING
        teardown()
    }

    private fun teardown() {
        running = false
        CoreRuntime.onCoreShutdownRequested = null

        if (callbackRegistered) {
            runCatching { connectivity.unregisterNetworkCallback(defaultNetworkCallback) }
            callbackRegistered = false
        }

        runCatching { TProxyService.TProxyStopService() }
        CoreRuntime.stopCore()

        // Give the async core stop a moment before closing the interface;
        // closing too early can leave a stale VPN icon (see v2rayNG history).
        try {
            Thread.sleep(100)
        } catch (e: InterruptedException) {
            // ignore
        }

        runCatching { tunFd?.close() }
        tunFd = null

        CoreRuntime.state.value = ConnState.DISCONNECTED
        CoreRuntime.connectedAt.value = 0L

        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun establishTun(): Boolean {
        val s = Store.settingsSnapshot()
        val builder = Builder()
        builder.setSession(getString(R.string.app_name))
        builder.setMtu(s.mtu)
        builder.addAddress(AppConfig.TUN_IPV4_CLIENT, 30)
        builder.addRoute("0.0.0.0", 0)
        if (s.ipv6) {
            builder.addAddress(AppConfig.TUN_IPV6_CLIENT, 126)
            builder.addRoute("::", 0)
        }

        // Android needs at least one DNS server on the tun, otherwise name
        // resolution dies and the VPN looks "connected but no internet".
        var dnsAdded = false
        s.remoteDns.split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEach {
            if (Utils.isIpAddress(it)) {
                runCatching { builder.addDnsServer(it) }.onSuccess { _ -> dnsAdded = true }
            }
        }
        if (!dnsAdded) {
            builder.addDnsServer(AppConfig.DEFAULT_REMOTE_DNS)
        }

        // Per-app rules. The app itself must always bypass the tun so the
        // core's own sockets do not loop back into the VPN.
        val apps = s.perAppSet
        when {
            s.perAppMode == AppConfig.PER_APP_BYPASS && apps.isNotEmpty() -> {
                (apps + packageName).forEach {
                    runCatching { builder.addDisallowedApplication(it) }
                }
            }

            s.perAppMode == AppConfig.PER_APP_ALLOW && apps.isNotEmpty() -> {
                (apps - packageName).forEach {
                    runCatching { builder.addAllowedApplication(it) }
                }
            }

            else -> {
                runCatching { builder.addDisallowedApplication(packageName) }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }

        return try {
            tunFd?.close()
            tunFd = builder.establish()
            tunFd != null
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "establish failed", e)
            CoreRuntime.lastError.value = e.message
            false
        }
    }

    private fun startHevTunnel() {
        val s = Store.settingsSnapshot()
        val yaml = buildString {
            appendLine("tunnel:")
            appendLine("  mtu: ${s.mtu}")
            appendLine("  ipv4: ${AppConfig.TUN_IPV4_CLIENT}")
            if (s.ipv6) {
                appendLine("  ipv6: '${AppConfig.TUN_IPV6_CLIENT}'")
            }
            appendLine("socks5:")
            appendLine("  port: ${s.socksPort}")
            appendLine("  address: ${AppConfig.LOOPBACK}")
            appendLine("  udp: 'udp'")
            appendLine("misc:")
            appendLine("  tcp-read-write-timeout: 300000")
            appendLine("  udp-read-write-timeout: 60000")
            appendLine("  log-level: warn")
        }
        val configFile = File(filesDir, "hev-tunnel.yaml")
        configFile.writeText(yaml)
        val fd = tunFd ?: error("tun fd is null")
        TProxyService.TProxyStartService(configFile.absolutePath, fd.fd)
    }

    private fun registerNetworkCallback() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                connectivity.requestNetwork(defaultNetworkRequest, defaultNetworkCallback)
                callbackRegistered = true
            } catch (e: Exception) {
                Log.w(AppConfig.TAG, "requestNetwork failed: ${e.message}")
            }
        }
    }

    // ---------------- notification ----------------

    private fun createNotificationChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val normal = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel),
            NotificationManager.IMPORTANCE_LOW
        ).apply { setShowBadge(false) }
        // A separate min-importance channel: no status-bar icon, collapsed in the
        // shade. Used when the user enables "hide notification".
        val min = NotificationChannel(
            CHANNEL_ID_MIN,
            getString(R.string.notification_channel_min),
            NotificationManager.IMPORTANCE_MIN
        ).apply { setShowBadge(false) }
        manager.createNotificationChannel(normal)
        manager.createNotificationChannel(min)
    }

    private fun buildNotification(text: String): Notification {
        val hidden = Store.hideNotification
        val channel = if (hidden) CHANNEL_ID_MIN else CHANNEL_ID
        val openIntent = packageManager.getLaunchIntentForPackage(packageName)
        val contentPendingIntent = if (openIntent != null) {
            PendingIntent.getActivity(
                this, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } else null

        val stopIntent = Intent(this, XVpnService::class.java).setAction(ACTION_STOP)
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(if (hidden) NotificationCompat.PRIORITY_MIN else NotificationCompat.PRIORITY_LOW)
            .setContentIntent(contentPendingIntent)
            .addAction(0, getString(R.string.action_stop), stopPendingIntent)
            .build()
    }

    private fun startAsForeground(text: String) {
        val notification = buildNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }
}
