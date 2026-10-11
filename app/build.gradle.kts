import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Debug-only convenience: never populated for release builds.
val devPrefill = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun prefill(key: String): String = devPrefill.getProperty(key) ?: ""

// Shown in Settings. CI can set GIT_COMMIT; local builds ask git, and builds without git omit it.
val gitCommit: String = providers.environmentVariable("GIT_COMMIT").orNull?.take(10)
    ?: runCatching {
        providers.exec {
            commandLine("git", "rev-parse", "--short=10", "HEAD")
            isIgnoreExitValue = true
        }.standardOutput.asText.get().trim()
    }.getOrDefault("")

fun String.asBuildConfigLiteral(): String = buildString {
    append('"')
    this@asBuildConfigLiteral.forEach { character ->
        append(
            when (character) {
                '\\' -> "\\\\"
                '"' -> "\\\""
                '\n' -> "\\n"
                '\r' -> "\\r"
                '\t' -> "\\t"
                else -> character
            },
        )
    }
    append('"')
}

android {
    namespace = "com.patbaumgartner.android.stringtelephone"
    compileSdk = 37

    sourceSets["test"].manifest.srcFile("src/test/manifest/AndroidManifest.xml")

    defaultConfig {
        applicationId = "com.patbaumgartner.android.stringtelephone"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "GIT_COMMIT", gitCommit.asBuildConfigLiteral())
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }

    buildTypes {
        debug {
            buildConfigField("String", "DEV_SERVER_URL", prefill("stringtelephone.serverUrl").asBuildConfigLiteral())
            buildConfigField("String", "DEV_USERNAME", prefill("stringtelephone.username").asBuildConfigLiteral())
            buildConfigField("String", "DEV_PASSWORD", prefill("stringtelephone.password").asBuildConfigLiteral())
            buildConfigField("String", "DEV_SIP_SECRET", prefill("stringtelephone.sipSecret").asBuildConfigLiteral())
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            buildConfigField("String", "DEV_SERVER_URL", "\"\"")
            buildConfigField("String", "DEV_USERNAME", "\"\"")
            buildConfigField("String", "DEV_PASSWORD", "\"\"")
            buildConfigField("String", "DEV_SIP_SECRET", "\"\"")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.linphone.sdk)
    implementation(libs.androidx.security.crypto)
    implementation(libs.core.telecom)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.compose.ui.test.manifest)
    testImplementation(libs.androidx.compose.ui.graphics)
    testImplementation(libs.androidx.compose.material3)
}
