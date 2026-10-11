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
- Reference project (esp32-room-watchdog-android) uses minSdk 26,
  target/compileSdk 37 (Android 16), JDK 17, AGP 9.x — a known-good
  toolchain on this machine and CI.

## Decision

- `minSdk = 26` (Android 8.0): below that, Core-Telecom self-managed calls
  and modern notification handling degrade; SDK floor is 23 but 26 keeps
  the Telecom path uniform.
- `targetSdk = 37`, `compileSdk = 37`, JDK 17 toolchain, matching the
  reference project's verified toolchain (AGP 9.4.x, Kotlin 2.4.x).
- Supported device matrix (v1): Android 8.0–16.x arm64 devices; emulator
  x86_64 for UI smoke tests. Verified on physical devices in Phase 8 with
  recorded models/versions.

## Consequences

- On API 26–30 the app uses the platform Telecom self-managed path only
  where functional; `ConnectionService` behaviour differences are recorded
  in `docs/testing.md`.
- Android 14+ foreground-service microphone declarations
  (`FOREGROUND_SERVICE_MICROPHONE`) and Android 13+ notification runtime
  permission are required paths; documented in the manifest review.
