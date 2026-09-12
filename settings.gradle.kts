pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()

        // usb-serial-for-android is published through JitPack, not Maven
        // Central. Without this the :app build fails to resolve it. The content
        // filter keeps JitPack out of the lookup path for everything else,
        // since it is slow to answer for artifacts it does not host.
        maven {
            url = uri("https://jitpack.io")
            content { includeGroupByRegex("com\\.github\\..*") }
        }
    }
}

rootProject.name = "truckscan"

// The :core module is pure Kotlin/JVM and carries every protocol layer
// (ISO-TP, UDS, DTC decoding, As-Built framing). It builds and tests with
// nothing but a JDK, which is what makes `./gradlew :core:test` work on a
// laptop or in CI without an Android SDK installed.
include(":core")

// :app is the Android front end. It is only included when an SDK is actually
// present, so that core protocol work is never blocked by a missing SDK.
val hasAndroidSdk = System.getenv("ANDROID_HOME") != null ||
    System.getenv("ANDROID_SDK_ROOT") != null ||
    file("local.properties").takeIf { it.exists() }
        ?.readText()?.contains("sdk.dir") == true

if (hasAndroidSdk) {
    include(":app")
} else {
    logger.lifecycle("Android SDK not found - skipping :app. Core protocol module still builds.")
}
