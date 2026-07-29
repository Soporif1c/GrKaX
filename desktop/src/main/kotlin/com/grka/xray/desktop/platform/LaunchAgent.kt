package com.grka.xray.desktop.platform

import com.grka.xray.desktop.core.CoreRuntime
import com.grka.xray.desktop.util.Platform
import java.io.File

/**
 * Start-at-login via a user LaunchAgent — no privileges needed, and it is a
 * single file the user can delete by hand if the app ever misbehaves.
 *
 * Launches through `open -a` rather than the bundle's inner executable: the
 * launcher binary is named after the package and would break the moment the
 * product name changes.
 */
object LaunchAgent {

    private const val LABEL = "com.grka.xray.desktop"

    private val plistFile: File
        get() = File(System.getProperty("user.home"), "Library/LaunchAgents/$LABEL.plist")

    /** False when running outside an .app bundle — nothing to launch. */
    val isSupported: Boolean get() = Platform.isMac && Platform.appBundle != null

    val isEnabled: Boolean get() = plistFile.isFile

    /** Returns an error message, or null on success. */
    fun setEnabled(enabled: Boolean): String? {
        if (!Platform.isMac) return "Автозапуск реализован только для macOS"
        return if (enabled) enable() else disable()
    }

    private fun enable(): String? {
        val app = Platform.appBundle
            ?: return "Автозапуск работает только для установленного приложения (.app)"
        return try {
            plistFile.parentFile?.mkdirs()
            plistFile.writeText(plist(app.absolutePath))
            CoreRuntime.log("Автозапуск включён: ${plistFile.absolutePath}")
            null
        } catch (e: Exception) {
            e.message ?: e.javaClass.simpleName
        }
    }

    private fun disable(): String? = try {
        if (plistFile.exists()) plistFile.delete()
        CoreRuntime.log("Автозапуск выключен")
        null
    } catch (e: Exception) {
        e.message ?: e.javaClass.simpleName
    }

    private fun plist(appPath: String): String = """
        <?xml version="1.0" encoding="UTF-8"?>
        <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
        <plist version="1.0">
        <dict>
            <key>Label</key>
            <string>$LABEL</string>
            <key>ProgramArguments</key>
            <array>
                <string>/usr/bin/open</string>
                <string>-a</string>
                <string>$appPath</string>
            </array>
            <key>RunAtLoad</key>
            <true/>
        </dict>
        </plist>
    """.trimIndent()
}
