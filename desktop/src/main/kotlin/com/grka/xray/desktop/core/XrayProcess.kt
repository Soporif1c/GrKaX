package com.grka.xray.desktop.core

import com.grka.xray.desktop.util.Platform
import com.grka.xray.desktop.util.Shell
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Runs the bundled `xray` binary as a child process.
 *
 * The desktop core is a real process rather than the gomobile binding the
 * Android client links against, so start/stop, log capture and stats polling
 * all go through the CLI.
 */
object XrayProcess {

    @Volatile
    private var process: Process? = null

    val isRunning: Boolean get() = process?.isAlive == true

    /** Resolved once: the binary inside the app bundle, or on PATH when developing. */
    fun binary(): File {
        val bundled = Platform.bundled(if (Platform.isWindows) "xray.exe" else "xray")
        if (bundled.isFile) {
            if (!bundled.canExecute()) bundled.setExecutable(true)
            return bundled
        }
        return File(if (Platform.isWindows) "xray.exe" else "xray")
    }

    fun version(): String = runCatching {
        val out = Shell.capture(binary().absolutePath, "version")
        out.lineSequence().firstOrNull()?.trim().orEmpty().ifEmpty { "unknown" }
    }.getOrDefault("unknown")

    /**
     * Starts the core with [config]. Returns null on success, or a message
     * describing why the core refused to come up.
     */
    fun start(config: String, onLine: (String) -> Unit): String? {
        stop()
        val configFile = File(Platform.runtimeDir, "config.json")
        configFile.writeText(config)

        val bin = binary()
        if (!bin.isFile && bin.absolutePath == bin.name) {
            return "Xray binary not found in the app bundle"
        }

        return try {
            val builder = ProcessBuilder(bin.absolutePath, "run", "-c", configFile.absolutePath)
                .directory(Platform.runtimeDir)
                .redirectErrorStream(true)
            // Where the core looks for geoip.dat / geosite.dat.
            builder.environment()["XRAY_LOCATION_ASSET"] = Platform.bundledResourcesDir.absolutePath
            val proc = builder.start()
            process = proc

            Thread({
                runCatching {
                    proc.inputStream.bufferedReader().forEachLine(onLine)
                }
            }, "xray-log").apply { isDaemon = true }.start()

            // The core validates the config at startup; if it is going to die,
            // it dies immediately, so a short grace period catches config errors.
            if (proc.waitFor(700, TimeUnit.MILLISECONDS)) {
                process = null
                "Core exited immediately (code ${proc.exitValue()}) — see the log screen"
            } else {
                null
            }
        } catch (e: Exception) {
            process = null
            e.message ?: e.javaClass.simpleName
        }
    }

    fun stop() {
        val proc = process ?: return
        process = null
        runCatching {
            proc.destroy()
            if (!proc.waitFor(3, TimeUnit.SECONDS)) proc.destroyForcibly()
        }
    }

    /**
     * Queries and resets the outbound traffic counters. Each returned value is
     * therefore the delta since the previous call, matching how the Android
     * client reads its stats.
     */
    fun queryTrafficDelta(apiPort: Int): Pair<Long, Long>? {
        val result = Shell.run(
            binary().absolutePath, "api", "statsquery",
            "--server=127.0.0.1:$apiPort", "-reset",
            timeoutSeconds = 5,
        )
        if (!result.ok) return null
        return parseStats(result.out)
    }

    /** Parses `{"stat":[{"name":"outbound>>>proxy>>>traffic>>>uplink","value":"42"}]}`. */
    internal fun parseStats(payload: String): Pair<Long, Long> {
        var up = 0L
        var down = 0L
        val entries = Regex("\\{[^{}]*}").findAll(payload)
        for (entry in entries) {
            val text = entry.value
            val name = Regex("\"name\"\\s*:\\s*\"([^\"]*)\"").find(text)?.groupValues?.get(1) ?: continue
            // Sum every outbound: an xray-json subscription keeps its own
            // outbound tags, so filtering on a "proxy" tag would miss traffic.
            if (!name.startsWith("outbound>>>")) continue
            val value = Regex("\"value\"\\s*:\\s*\"?(\\d+)\"?").find(text)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            when {
                name.endsWith("uplink") -> up += value
                name.endsWith("downlink") -> down += value
            }
        }
        return up to down
    }
}
