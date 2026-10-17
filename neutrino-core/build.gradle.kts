// Everything that has to agree byte for byte with the web, iOS, macOS and Windows clients — the
// E2EE primitives, the keyring and its transports, saved places — plus the HTTP client, sign-in
// and key provisioning every app shares. Plain Kotlin/JVM with no Android dependency, so all of it
// is tested on the JVM in seconds. The Android counterpart of `NeutrinoCore` + `NeutrinoAuth` +
// `NeutrinoCrypto` in neutrino_shared_ios.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    `java-test-fixtures`
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
    api(libs.okhttp)
    implementation(libs.bouncycastle)

    // FakeServer, an in-memory Neutrino the apps' own tests run against.
    testFixturesApi(libs.okhttp.mockwebserver)
    testFixturesApi(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.test {
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

// Driven by scripts/crosscheck-web-crypto.sh: `-Pargs="emit out.json"` / `-Pargs="verify a.json w.json"`.
tasks.register<JavaExec>("crossCheck") {
    dependsOn("testClasses")
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.neutrino.shared.core.CrossCheck")
    args = (project.findProperty("args") as String? ?: "").split(" ").filter { it.isNotEmpty() }
}
