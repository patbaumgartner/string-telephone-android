plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.pitest)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

// Mutation testing runs on this pure-JVM module only; the Android :app module
// is not pitest-compatible (AGP-produced bytecode/variant complexity).
pitest {
    targetClasses = setOf("com.patbaumgartner.android.stringtelephone.core.*")
    outputFormats = setOf("XML", "HTML")
    timestampedReports = false
    threads = 4
    // Regression floor: measured baseline 2026-11-16 is 27% (297/1095 killed,
    // test strength 92% on covered lines). Fails the build if coverage drops.
    mutationThreshold = 25
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    // JVM reference implementation of the E2EE seam (ADR-0015): proves the real
    // Signal-protocol crypto round trip on the host JVM. The app module binds
    // the Android AAR instead; the engine interface lives in core main.
    testImplementation(libs.libsignal.client)
}
