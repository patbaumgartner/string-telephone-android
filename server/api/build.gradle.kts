plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
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

dependencies {
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.json)
    implementation(libs.ktor.server.websockets)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.logback)
    implementation(libs.sqlite.jdbc)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.ktor.server.tests)
    testImplementation(libs.ktor.client.core)
    testImplementation(libs.ktor.client.cio)
    testImplementation(libs.ktor.client.content.negotiation)
    testImplementation(libs.ktor.client.websockets)
    testImplementation(libs.kotlinx.coroutines.test)
}

application {
    mainClass.set("com.patbaumgartner.stringtelephone.MainKt")
}

// A second start script next to the API script. installDist owns the whole
// install directory; the extra script is copied in afterwards.
val provisioningScripts = tasks.register<CreateStartScripts>("provisioningStartScripts") {
    mainClass.set("com.patbaumgartner.stringtelephone.ProvisioningCliKt")
    applicationName = "stringtelephone-provision"
    outputDir = layout.buildDirectory.dir("provisioningScripts").get().asFile
    classpath = tasks.getByName<org.gradle.jvm.tasks.Jar>("jar").outputs.files
}

tasks.named<Sync>("installDist") {
    dependsOn(provisioningScripts)
    into(layout.buildDirectory.dir("install/api"))
    from(provisioningScripts) {
        into("bin")
    }
}
