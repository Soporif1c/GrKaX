package com.grka.xray.desktop.core

import com.grka.xray.desktop.AppConfig
import com.grka.xray.desktop.config.ConfigBuilder
import com.grka.xray.desktop.data.Profile
import com.grka.xray.desktop.data.Store
import com.grka.xray.desktop.platform.NetworkController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalTime
import java.time.format.DateTimeFormatter

enum class ConnState { DISCONNECTED, CONNECTING, CONNECTED, STOPPING }

data class Traffic(
    val upSpeed: Long = 0,
    val downSpeed: Long = 0,
    val upTotal: Long = 0,
    val downTotal: Long = 0,
)

object CoreRuntime {
    val state = MutableStateFlow(ConnState.DISCONNECTED)
    val traffic = MutableStateFlow(Traffic())
    val connectedAt = MutableStateFlow(0L)
    val lastError = MutableStateFlow<String?>(null)
    val logs = MutableStateFlow<List<String>>(emptyList())
    /** The config handed to the core on the last start — shown in the UI. */
    val lastConfig = MutableStateFlow("")

    private const val MAX_LOG_LINES = 2000
    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm:ss")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private var statsJob: Job? = null

    fun log(line: String) {
        val stamped = "${LocalTime.now().format(timeFormat)}  $line"
        logs.value = (logs.value + stamped).takeLast(MAX_LOG_LINES)
    }

    fun clearLogs() {
        logs.value = emptyList()
    }

    suspend fun connect(profile: Profile): Boolean {
        lock.withLock {
            if (state.value == ConnState.CONNECTED || state.value == ConnState.CONNECTING) return true
            state.value = ConnState.CONNECTING
            lastError.value = null

            val started = withContext(Dispatchers.IO) { startCore(profile) }
            if (started != null) {
                fail(started)
                return false
            }

            val netError = withContext(Dispatchers.IO) {
                NetworkController.enable(
                    Store.networkMode, Store.socksPort, Store.httpPort, profile.server,
                )
            }
            if (netError != null) {
                withContext(Dispatchers.IO) { XrayProcess.stop() }
                fail(netError)
                return false
            }

            connectedAt.value = System.currentTimeMillis()
            state.value = ConnState.CONNECTED
            startStatsPolling()
            log("Подключено · ${profile.name} · ${modeLabel(Store.mode)}")
            return true
        }
    }

    suspend fun disconnect() {
        lock.withLock {
            if (state.value == ConnState.DISCONNECTED) return
            state.value = ConnState.STOPPING
            statsJob?.cancel()
            statsJob = null
            withContext(Dispatchers.IO) {
                NetworkController.disable()
                XrayProcess.stop()
            }
            traffic.value = Traffic()
            connectedAt.value = 0
            state.value = ConnState.DISCONNECTED
            log("Отключено")
        }
    }

    /**
     * Rule / Global / Direct switch. Xray cannot swap routing at runtime, so a
     * live connection is restarted with the new config — the OS-level plumbing
     * (system proxy or TUN) stays in place, which keeps the switch quick and
     * avoids a second admin prompt.
     */
    suspend fun applyMode(mode: String) {
        Store.mode = mode
        if (state.value != ConnState.CONNECTED) return
        val profile = Store.selectedProfile() ?: return
        lock.withLock {
            val error = withContext(Dispatchers.IO) { startCore(profile) }
            if (error != null) {
                fail(error)
                withContext(Dispatchers.IO) { NetworkController.disable() }
            } else {
                log("Mode → ${modeLabel(mode)}")
            }
        }
    }

    /** Builds the config and (re)launches the core. Returns an error or null. */
    private fun startCore(profile: Profile): String? {
        val config = try {
            ConfigBuilder.build(
                profile = profile,
                s = Store.settingsSnapshot(),
                routingJson = Store.routingTemplateFor(profile),
                useSubRouting = Store.useSubscriptionRouting,
                customTemplate = Store.configTemplate,
            )
        } catch (e: Exception) {
            return "Failed to build config: ${e.message ?: e.javaClass.simpleName}"
        }
        lastConfig.value = config
        return XrayProcess.start(config) { line -> log(line) }
    }

    private fun fail(message: String) {
        lastError.value = message
        log("Error: $message")
        state.value = ConnState.DISCONNECTED
        connectedAt.value = 0
    }

    private fun startStatsPolling() {
        statsJob?.cancel()
        statsJob = scope.launch {
            var upTotal = 0L
            var downTotal = 0L
            val apiPort = Store.apiPort
            while (isActive) {
                delay(1000)
                val delta = XrayProcess.queryTrafficDelta(apiPort) ?: continue
                upTotal += delta.first
                downTotal += delta.second
                traffic.value = Traffic(delta.first, delta.second, upTotal, downTotal)
            }
        }
    }

    fun modeLabel(mode: String): String = when (mode) {
        AppConfig.MODE_GLOBAL -> "Глобально"
        AppConfig.MODE_DIRECT -> "Прямое"
        else -> "Правила"
    }

    /** Best-effort teardown for JVM shutdown — never leave the system proxy set. */
    fun shutdownHook() {
        runCatching { NetworkController.disable() }
        runCatching { XrayProcess.stop() }
    }
}
