package com.grka.xray.desktop.util

import java.util.concurrent.TimeUnit

/** Thin helpers around external commands (`networksetup`, `ioreg`, the helper script). */
object Shell {

    data class Result(val code: Int, val out: String, val err: String) {
        val ok: Boolean get() = code == 0
        /** Whatever the command said, preferring stderr for diagnostics. */
        fun message(): String = err.trim().ifEmpty { out.trim() }
    }

    fun run(vararg command: String, timeoutSeconds: Long = 30): Result {
        return try {
            val process = ProcessBuilder(*command).redirectErrorStream(false).start()
            val out = process.inputStream.bufferedReader().readText()
            val err = process.errorStream.bufferedReader().readText()
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return Result(-1, out, "timed out after ${timeoutSeconds}s")
            }
            Result(process.exitValue(), out, err)
        } catch (e: Exception) {
            Result(-1, "", e.message ?: e.javaClass.simpleName)
        }
    }

    fun capture(vararg command: String): String = run(*command).out

    /**
     * Runs a command with administrator rights on macOS. Without an Apple
     * Developer account we cannot install a code-signed privileged helper via
     * SMJobBless, so we ask the OS for an authorization prompt instead — this
     * is the same approach Clash Verge and V2rayU take. Swap this out for a
     * launchd daemon (or NetworkExtension) once a signing identity exists.
     */
    fun runAsAdmin(script: String, timeoutSeconds: Long = 120): Result {
        if (!Platform.isMac) return Result(-1, "", "admin escalation is only implemented for macOS")
        // osascript needs the inner double quotes and backslashes escaped.
        val escaped = script.replace("\\", "\\\\").replace("\"", "\\\"")
        return run(
            "/usr/bin/osascript",
            "-e",
            "do shell script \"$escaped\" with administrator privileges",
            timeoutSeconds = timeoutSeconds,
        )
    }

    fun openUrl(url: String) {
        when {
            Platform.isMac -> run("/usr/bin/open", url)
            Platform.isWindows -> run("cmd", "/c", "start", "", url)
            else -> run("xdg-open", url)
        }
    }
}
