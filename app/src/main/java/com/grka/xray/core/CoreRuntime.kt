package com.grka.xray.core

import android.content.Context
import android.provider.Settings
import android.util.Base64
import android.util.Log
import com.grka.xray.AppConfig
import com.grka.xray.config.ConfigBuilder
import com.grka.xray.data.Profile
import com.grka.xray.data.Store
import go.Seq
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

enum class ConnState { DISCONNECTED, CONNECTING, CONNECTED, STOPPING }

data class Traffic(
    val upSpeed: Long = 0,
    val downSpeed: Long = 0,
    val upTotal: Long = 0,
    val downTotal: Long = 0,
)

object CoreRuntime {
    val state = kotlinx.coroutines.flow.MutableStateFlow(ConnState.DISCONNECTED)
    val traffic = kotlinx.coroutines.flow.MutableStateFlow(Traffic())
    val connectedAt = kotlinx.coroutines.flow.MutableStateFlow(0L)
    val lastError = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    /** Set by the VPN service so a core-initiated shutdown tears the whole VPN down. */
    @Volatile
    var onCoreShutdownRequested: (() -> Unit)? = null

    private val initialized = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var statsJob: Job? = null

    private val controller: CoreController by lazy { Libv2ray.newCoreController(Callback()) }

    fun init(context: Context) {
        if (!initialized.compareAndSet(false, true)) return
        try {
            Seq.setContext(context.applicationContext)
            val assetDir = prepareGeoAssets(context)
            Libv2ray.initCoreEnv(assetDir, deviceId(context))
            Log.i(AppConfig.TAG, "Core env initialized, assets: $assetDir")
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to init core env", e)
            initialized.set(false)
        }
    }

    fun coreVersion(): String = try {
        Libv2ray.checkVersionX()
    } catch (e: Exception) {
        "unknown"
    }

    fun isRunning(): Boolean = try {
        controller.isRunning
    } catch (e: Exception) {
        false
    }

    fun startCore(context: Context, profile: Profile): Boolean {
        init(context)
        return try {
            val routing = Store.routingTemplateFor(profile)
            val config = ConfigBuilder.build(
                profile, Store.settingsSnapshot(), forTest = false,
                routingJson = routing, useSubRouting = Store.useSubscriptionRouting,
            )
            Log.d(AppConfig.TAG, "Core config: $config")
            controller.startLoop(config, 0)
            if (!controller.isRunning) {
                lastError.value = "Core failed to start"
                false
            } else {
                connectedAt.value = System.currentTimeMillis()
                startStatsPolling()
                true
            }
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "startCore failed", e)
            lastError.value = e.message ?: e.javaClass.simpleName
            false
        }
    }

    fun stopCore() {
        statsJob?.cancel()
        statsJob = null
        traffic.value = Traffic()
        scope.launch {
            try {
                controller.stopLoop()
            } catch (e: Exception) {
                Log.e(AppConfig.TAG, "stopLoop failed", e)
            }
        }
    }

    /** Measures latency through the currently running core. Returns ms or -1. */
    fun measureConnectedDelay(): Long {
        if (!isRunning()) return -1
        return try {
            controller.measureDelay(AppConfig.DELAY_TEST_URL)
        } catch (e: Exception) {
            try {
                controller.measureDelay(AppConfig.DELAY_TEST_URL2)
            } catch (e2: Exception) {
                -1
            }
        }
    }

    private fun startStatsPolling() {
        statsJob?.cancel()
        statsJob = scope.launch {
            var upTotal = 0L
            var downTotal = 0L
            while (isActive) {
                delay(1000)
                try {
                    // Counters are reset by the core on each query, so each value
                    // is the byte delta for the last interval.
                    val payload = controller.queryAllOutboundTrafficStats()
                    var up = 0L
                    var down = 0L
                    payload.split(';').forEach { entry ->
                        if (entry.isBlank()) return@forEach
                        val parts = entry.split(',')
                        if (parts.size != 3) return@forEach
                        val value = parts[2].toLongOrNull() ?: return@forEach
                        when (parts[1]) {
                            "uplink" -> up += value
                            "downlink" -> down += value
                        }
                    }
                    upTotal += up
                    downTotal += down
                    traffic.value = Traffic(up, down, upTotal, downTotal)
                } catch (e: Exception) {
                    // core may be stopping; ignore
                }
            }
        }
    }

    /**
     * Copies bundled geoip/geosite files into a private dir the core reads from.
     * Re-copies when the app is updated so newer bundled data wins.
     */
    private fun prepareGeoAssets(context: Context): String {
        val dir = File(context.filesDir, "assets")
        if (!dir.exists()) dir.mkdirs()
        val versionCode = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionCode
        } catch (e: Exception) {
            0
        }
        val force = Store.geoAssetsVersion != versionCode
        for (name in listOf("geoip.dat", "geosite.dat")) {
            val target = File(dir, name)
            if (force || !target.exists()) {
                try {
                    context.assets.open(name).use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    }
                } catch (e: Exception) {
                    Log.w(AppConfig.TAG, "Bundled asset $name not available: ${e.message}")
                }
            }
        }
        Store.geoAssetsVersion = versionCode
        return dir.absolutePath
    }

    private fun deviceId(context: Context): String = try {
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?: "grkax-default"
        Base64.encodeToString(
            androidId.toByteArray(Charsets.UTF_8).copyOf(32),
            Base64.NO_PADDING or Base64.URL_SAFE
        )
    } catch (e: Exception) {
        ""
    }

    private class Callback : CoreCallbackHandler {
        override fun startup(): Long = 0

        override fun shutdown(): Long {
            return try {
                onCoreShutdownRequested?.invoke()
                0
            } catch (e: Exception) {
                -1
            }
        }

        override fun onEmitStatus(l: Long, s: String?): Long = 0
    }
}
