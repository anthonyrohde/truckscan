// Intentionally declares no plugins.
//
// Gradle warns that the Kotlin plugin is loaded once per module and suggests
// hoisting it here with `apply false`. That was tried and reverted, because the
// Kotlin Android plugin and the Android plugin must share a classloader:
// hoisting Kotlin while leaving AGP in :app puts them in parent and child
// loaders respectively, and applying kotlin-android then dies with
// NoClassDefFoundError on com/android/build/gradle/api/BaseVariant.
//
// The consistent alternative is hoisting AGP as well, but that forces every
// build to resolve AGP - including a :core-only build on a machine with no
// Android SDK and no reachable Google Maven repository, which is exactly the
// configuration the module split exists to support and the one CI runs.
//
// So the warning is accepted deliberately. It is cosmetic: both modules request
// the same Kotlin version, and the build works.
