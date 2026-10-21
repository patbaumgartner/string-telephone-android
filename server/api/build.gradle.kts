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
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation(kotlin("reflect"))
    implementation("tools.jackson.module:jackson-module-kotlin")
    implementation(libs.kotlinx.serialization.json)
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.postgresql:postgresql")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation(libs.aws.s3)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.localstack)
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
