// Standalone Gradle build: kept separate from the Android build on purpose, so
// the desktop client needs no Android SDK and the (working) app build stays
// untouched. A shared :core module can be extracted later once this settles.
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        google()
    }
}

rootProject.name = "GrKaX-Desktop"
