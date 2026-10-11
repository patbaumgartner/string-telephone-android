# ADR-0005: Android SDK levels and device matrix

Date: 2026-10-11
Status: Accepted

## Context

The app must support modern Android while integrating with Telecom
(CallStyle notifications, self-managed calls) and microphone foreground
services, which changed substantially in Android 12–15.

Facts used:

- linphone-sdk-android 5.4.88 requires minSdk 23 (its merged manifest).
- AndroidX Core-Telecom self-managed `CallsManager` is available from
  API 26 (Jetpack `androidx.core:core-telecom` 1.x requires compileSdk 34+
  and works back to API 26 for self-managed calls).
- Gradle 9.8+ supports JDK 27 for the daemon and toolchains; AGP 9.4.x and
  Kotlin 2.4.x run on it. Kotlin 2.4 emits JVM bytecode up to target 26, and
  Android desugaring (D8) requires bytecode ≤ 17, so the bytecode target
  stays 17 regardless of the build JDK.

## Decision

- `minSdk = 26` (Android 8.0): below that, Core-Telecom self-managed calls
  and modern notification handling degrade; SDK floor is 23 but 26 keeps
  the Telecom path uniform.
- `targetSdk = 37`, `compileSdk = 37`. The build runs on **JDK 27**
  (17–26 also work); the bytecode target stays **17** for Android D8
  compatibility. CI builds on JDK 27 (Temurin); the API image builds with
  `gradle:9.8.1-jdk27` and runs on `eclipse-temurin:27-jre`.
- Supported device matrix (v1): Android 8.0–16.x arm64 devices; emulator
  x86_64 for UI smoke tests. Verified on physical devices in Phase 8 with
  recorded models/versions.

## Consequences

- On API 26–30 the app uses the platform Telecom self-managed path only
  where functional; `ConnectionService` behaviour differences on older APIs
  are noted during device verification.
- Android 14+ foreground-service microphone declarations
  (`FOREGROUND_SERVICE_MICROPHONE`) and Android 13+ notification runtime
  permission are required paths; documented in the manifest review.
