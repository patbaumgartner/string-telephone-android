plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
    alias(libs.plugins.spring.boot)
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
    implementation(platform("org.springframework.boot:spring-boot-dependencies:${libs.versions.springBoot.get()}"))
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-websocket")
    implementation("org.springframework.boot:spring-boot-starter-thymeleaf")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation(kotlin("reflect"))
    implementation("tools.jackson.module:jackson-module-kotlin")
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.sqlite.jdbc)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.bootJar { enabled = false }

application {
    mainClass.set("com.patbaumgartner.stringtelephone.MainKt")
}

// A second start script next to the API script. installDist owns the whole
// install directory; the extra script is copied in afterwards.
val provisioningScripts = tasks.register<CreateStartScripts>("provisioningStartScripts") {
    mainClass.set("com.patbaumgartner.stringtelephone.ProvisioningCliKt")
    applicationName = "stringtelephone-provision"
    outputDir = layout.buildDirectory.dir("provisioningScripts").get().asFile
    classpath = files(tasks.named<org.gradle.jvm.tasks.Jar>("jar"), configurations.runtimeClasspath)
}

tasks.named<Sync>("installDist") {
    dependsOn(provisioningScripts)
    into(layout.buildDirectory.dir("install/api"))
    from(provisioningScripts) {
        into("bin")
    }
}
