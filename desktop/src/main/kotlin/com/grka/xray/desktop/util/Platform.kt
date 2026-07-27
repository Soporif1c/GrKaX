package com.grka.xray.desktop.util

import java.io.File
import java.nio.file.Path

/** Host OS facts and the on-disk locations the app uses. */
object Platform {
    private val osName: String = System.getProperty("os.name")?.lowercase() ?: ""

    val isMac: Boolean = osName.contains("mac")
    val isWindows: Boolean = osName.contains("win")

    val isArm64: Boolean = (System.getProperty("os.arch") ?: "").let {
        it.equals("aarch64", true) || it.equals("arm64", true)
    }

    /** Where profiles, subscriptions and settings live. */
    val dataDir: File by lazy {
        val home = System.getProperty("user.home")
        val dir = when {
            isMac -> File(home, "Library/Application Support/GrKaX")
            isWindows -> File(System.getenv("APPDATA") ?: "$home\\AppData\\Roaming", "GrKaX")
            else -> File(home, ".config/GrKaX")
        }
        dir.mkdirs()
        dir
    }

    /** Working dir for the core: generated config, logs, runtime state. */
    val runtimeDir: File by lazy { File(dataDir, "runtime").apply { mkdirs() } }

    /**
     * Files shipped inside the app bundle (xray binary, geo data, helper
     * scripts). Compose's jpackage layout exposes them through this property;
     * during a local `gradlew run` we fall back to the source tree.
     */
    val bundledResourcesDir: File by lazy {
        System.getProperty("compose.application.resources.dir")?.let { File(it) }
            ?.takeIf { it.isDirectory }
            ?: File("resources/common").takeIf { it.isDirectory }
            ?: File(".")
    }

    fun bundled(name: String): File = File(bundledResourcesDir, name)

    /**
     * The `.app` bundle we are running from, if any. Resources live at
     * `<App>/Contents/app/resources`, so the bundle is three levels up. Null
     * during development, where there is no bundle.
     */
    val appBundle: File? by lazy {
        System.getProperty("compose.application.resources.dir")
            ?.let { File(it).parentFile?.parentFile?.parentFile }
            ?.takeIf { it.name.endsWith(".app") }
    }

    fun runtimePath(name: String): Path = File(runtimeDir, name).toPath()
}
