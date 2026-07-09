plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.grka.xray"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.grka.xray"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "1.0.2"

        splits {
            abi {
                isEnable = true
                reset()
                include("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
                isUniversalApk = true
            }
        }
    }

    signingConfigs {
        create("release") {
            // Populated from CI secrets when present. When absent, the release
            // build below falls back to debug signing so the APK is still
            // installable and CI stays green without manual key setup.
            val storePathEnv = System.getenv("GRKAX_KEYSTORE_FILE")
            if (!storePathEnv.isNullOrBlank() && file(storePathEnv).exists()) {
                storeFile = file(storePathEnv)
                storePassword = System.getenv("GRKAX_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("GRKAX_KEY_ALIAS")
                keyPassword = System.getenv("GRKAX_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            val releaseSigning = signingConfigs.getByName("release")
            signingConfig = if (releaseSigning.storeFile != null) {
                releaseSigning
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar", "*.jar"))))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)

    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons.core)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.mmkv.static)
    implementation(libs.okhttp)
    implementation(libs.zxing.core)
}
