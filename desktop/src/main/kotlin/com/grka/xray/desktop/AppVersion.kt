package com.grka.xray.desktop

import java.util.Properties

/** Display version, filled in from the Gradle build at packaging time. */
object AppVersion {
    val name: String by lazy {
        runCatching {
            AppVersion::class.java.getResourceAsStream("/version.properties")?.use { stream ->
                Properties().apply { load(stream) }.getProperty("version")
            }
        }.getOrNull()?.takeIf { it.isNotBlank() && !it.contains("\${") } ?: "0.1.0"
    }
}
