plugins {
    // These carry explicit versions rather than inheriting from the root
    // project. See the comment in the root build.gradle.kts: the Kotlin Android
    // and Android plugins have to load in the same classloader, and hoisting
    // only some of them breaks that.
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.anthonyrohde.truckscan"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.anthonyrohde.truckscan"
        // API 26 covers every phone that can realistically be used in a truck
        // and avoids the pre-Oreo Bluetooth quirks entirely.
        minSdk = 26
        // Android 16. Google Play requires API 36 of new apps and updates
        // from 31 August 2026. Targeting it also opts into edge-to-edge with
        // no way back - see enableEdgeToEdge() in MainActivity.
        targetSdk = 36
        // versionCode must increase for every release; Android refuses to
        // install a lower code over a higher one. It starts at 2 because the
        // rolling debug build already went out as 1.
        versionCode = 5
        versionName = "1.0.3"
    }

    // Release signing, driven entirely by environment variables.
    //
    // A debug APK is signed with Android's throwaway debug key, which is fine
    // for your own phone and wrong for anything handed to other people: every
    // machine generates a different debug key, so an update built elsewhere
    // will not install over it, and the build is marked debuggable.
    //
    // The keystore never enters this repository. CI materialises it from a
    // secret at build time; locally the same four variables point at your own
    // file. When they are absent the release build is simply unsigned, so a
    // clone without the keystore still builds.
    signingConfigs {
        create("release") {
            val keystore = System.getenv("KEYSTORE_PATH")
            if (keystore != null) {
                storeFile = file(keystore)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            if (System.getenv("KEYSTORE_PATH") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
            // Left off deliberately. R8 would shrink the APK, but it also
            // rewrites reflection-dependent code, and nothing in this project
            // can currently run an instrumented test to prove a shrunk build
            // still works. Turning it on is a change to make with a device in
            // hand, not blind.
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions { jvmTarget = "11" }

    buildFeatures { compose = true }

    sourceSets["main"].java.srcDirs("src/main/kotlin")

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)

    // USB serial, for the wired OBDLink EX over an OTG cable.
    implementation(libs.usb.serial)
}
