// What every Neutrino Android app shows and stores the same way: Keystore-sealed secrets, the app
// lock, the brand theme, the sign-in screens and the encryption-key screens. The counterpart of
// `NeutrinoUI` in neutrino_shared_ios.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.neutrino.shared.android"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":neutrino-core"))

    api(libs.androidx.core.ktx)
    api(libs.androidx.appcompat)
    api(libs.androidx.activity.compose)
    api(libs.androidx.biometric)
    api(libs.kotlinx.coroutines.android)

    api(platform(libs.compose.bom))
    api(libs.compose.ui)
    api(libs.compose.material3)
    api(libs.compose.material.icons)

    implementation(libs.zxing.core)
    api(libs.zxing.embedded)

    testImplementation(libs.junit)
}
