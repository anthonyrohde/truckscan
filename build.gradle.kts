// The Kotlin plugins are declared once here, with `apply false`, because both
// modules use them and declaring a version in each makes Gradle load the plugin
// twice - which it warns about and does not support.
//
// The Android plugin is deliberately NOT declared here. It is used by :app
// alone, so there is no duplication to solve, and hoisting it would force every
// build to resolve it - including a :core-only build on a machine with no
// Android SDK and no access to Google's Maven repository. Keeping it in :app
// preserves the property that the protocol module builds with nothing but a JDK.
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
