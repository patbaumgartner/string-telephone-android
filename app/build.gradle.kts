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

// Release signing credentials come from the environment (CI) or from a local,
// git-ignored keystore.properties file (see CONTRIBUTING.md, "Releases").
// Without either, the release build stays unsigned, which is fine for local
// development. Never commit the keystore or the passwords.
val keystoreProps = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.isFile) file.inputStream().use(::load)
}

fun signingSecret(envName: String, propertyName: String): String? =
    System.getenv(envName)?.takeIf { it.isNotBlank() }
        ?: keystoreProps.getProperty(propertyName)?.takeIf { it.isNotBlank() }

val releaseKeystoreFile: String? = signingSecret("SR_KEYSTORE_FILE", "storeFile")

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
        // Overridable from the command line (-PversionCode=… -PversionName=…),
        // which the release workflow uses to stamp tag-based versions.
        versionCode = (project.findProperty("versionCode") as String?)?.toIntOrNull() ?: 1
        versionName = (project.findProperty("versionName") as String?) ?: "0.1.0"
        buildConfigField("String", "GIT_COMMIT", gitCommit.asBuildConfigLiteral())
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }

    signingConfigs {
        if (releaseKeystoreFile != null) {
            create("release") {
                storeFile = file(releaseKeystoreFile)
                storePassword = signingSecret("SR_KEYSTORE_PASSWORD", "storePassword")
                keyAlias = signingSecret("SR_KEY_ALIAS", "keyAlias")
                keyPassword = signingSecret("SR_KEY_PASSWORD", "keyPassword")
            }
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
            // Null (unsigned) when no signing credentials are configured.
            signingConfig = signingConfigs.findByName("release")
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
        isCoreLibraryDesugaringEnabled = true
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
        // libsignal-android ships a second, testing-only native lib per ABI, and
        // its POM pulls in libsignal-client, whose JAR carries desktop natives
        // (.so/.dylib/.dll at the JAR root, packaged at the APK root). None of
        // those belong in an Android APK: together they are ~400 MB of the
        // ~900 MB APK, which matters for GitHub Releases downloads.
        jniLibs.excludes += setOf("**/libsignal_jni_testing.so")
        resources.excludes += setOf("libsignal_jni_*", "signal_jni_*.dll")
    }

    // Ship per-ABI APKs: the Signal and linphone native libs are tens of MB per
    // ABI and .so files cannot be compressed inside an APK, so a universal APK
    // is ~420 MB while a split APK is ~120 MB. The universal APK is still built
    // as app-universal-{debug,release}.apk (scripts/deploy-android.sh and its
    // tests install it), but only the split APKs are published to Releases.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
            isUniversalApk = true
        }
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
    // Signal protocol (PQXDH + Double Ratchet) for E2EE text (ADR-0015, Phase 22).
    // AGPL-3.0 like linphone: the app module stays GPL-compatible (ADR-0004).
    implementation(libs.libsignal.android)
    coreLibraryDesugaring(libs.desugaring.lib)
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
