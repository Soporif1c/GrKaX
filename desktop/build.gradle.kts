import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.4.0"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.0"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.0"
    id("org.jetbrains.compose") version "1.11.1"
}

group = "com.grka.xray"
// Display version shown in the UI and used by the updater.
val appVersion = "0.1.0"
version = appVersion

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    // Swing dispatcher — required to hop back to the UI thread from IO work.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
}

// Surfaces the display version to the app without a BuildConfig equivalent.
tasks.processResources {
    filesMatching("version.properties") {
        expand("version" to appVersion)
    }
}

// Renders every routing mode to disk; CI then validates each with `xray -test`.
tasks.register<JavaExec>("smoke") {
    mainClass.set("com.grka.xray.desktop.SmokeMainKt")
    classpath = sourceSets["main"].runtimeClasspath
    args = listOf(project.findProperty("smokeOut")?.toString() ?: "build/smoke")
}

compose.desktop {
    application {
        mainClass = "com.grka.xray.desktop.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg)
            packageName = "GrKa X"
            // jpackage rejects a macOS bundle version whose major part is 0,
            // so the bundle version and the display version differ on purpose.
            packageVersion = "1.0.0"
            description = "GrKa X — VPN client on the Xray core"
            copyright = "GPL-3.0"
            vendor = "Soporif1c"

            // Bundled at runtime by the CI workflow: the xray binary, the geo
            // data files and the TUN helper scripts.
            appResourcesRootDir.set(project.layout.projectDirectory.dir("resources"))

            macOS {
                bundleID = "com.grka.xray.desktop"
                dockName = "GrKa X"
                appCategory = "public.app-category.utilities"
                // Optional on purpose: a missing .icns must not break the build.
                project.file("icons/app.icns").takeIf { it.isFile }?.let { iconFile.set(it) }
                // No Apple Developer account yet: the CI signs the bundle
                // ad-hoc (required on Apple Silicon) and users clear the
                // quarantine flag manually. Swap in a Developer ID here when
                // the account exists.
                infoPlist {
                    extraKeysRawXml = """
                        <key>LSMinimumSystemVersion</key>
                        <string>12.0</string>
                        <key>LSUIElement</key>
                        <false/>
                    """.trimIndent()
                }
            }

            windows {
                menuGroup = "GrKa X"
                shortcut = true
            }
        }
    }
}
