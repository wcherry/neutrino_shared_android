// Consumed by local path, unversioned: each app's settings.gradle.kts has
// `includeBuild("../neutrino_shared_android")`, and Gradle substitutes these coordinates with the
// projects here. Editing this build changes every Android app the moment it rebuilds (root
// CLAUDE.md, rule 2) — build at least one dependent, not just these tests.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

allprojects {
    group = "com.neutrino.shared"
    version = "0.1.0"
}
