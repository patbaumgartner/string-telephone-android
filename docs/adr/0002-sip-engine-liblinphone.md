# ADR-0002: SIP/media engine — Liblinphone (linphone-sdk-android)

Date: 2026-10-11
Status: Accepted

## Context

The Android app must perform SIP REGISTER/INVITE, RTP audio, and TLS/SRTP.
Building a SIP stack from scratch is out of scope; the engine choice is a
licensing decision as much as a technical one.

Candidates:

1. **Liblinphone (org.linphone:linphone-sdk-android)** — full-featured,
   actively maintained (5.4.88 published 2026-02 on
   `https://download.linphone.org/maven_repository/`), ships arm64-v8a,
   armeabi-v7a, x86, x86_64 natives; minSdk 23; GPL-3.0 (per the artifact
   POM licence field, verified 2026-10-11). Proven TLS/SRTP support
   (`libsrtp2.so` present in the AAR). Also contains camera and QR (ZXing)
   code we do not use.
2. **Ooma/oSIP + hand-rolled RTP** — unacceptable effort and risk for audio.
3. **RFC-complainant pure-Java SIP (e.g. Mobicents/RestComm Android)** —
   unmaintained; no maintained media stack.
4. **Android built-in SIP stack (`android.net.sip`)** — deprecated, no TLS/SRTP
   on most devices, removed API surface.

## Licence analysis

- linphone-sdk-android 5.4.88 POM declares **GPL-3.0**. Linphone's own
  distribution offers a commercial licence, but the maven artifact we can
  actually consume is GPL. GPL-3.0 (not AGPL) applies to the *library*; the
  app that links it must be GPL-compatible.
- Project decision: ship the Android app under **GPL-3.0-or-later** (see
  ADR-0004). The companion API server does *not* link linphone-sdk and is a
  separate program; app and server communicate over a documented network API.
  This is a link-based boundary, not a product-merger: the server is
  independently copyrightable work licensed separately.
- No proprietary codecs are believed to be included (the AAR's `libmswebrtc`
  provides the codec set Linphone ships as free software; we do not enable
  H.264/AMR-WB patent-encumbered paths for audio-only use). This is verified
  for our configuration at prototype time; F-Droid anti-features are tracked
  in `docs/privacy.md`.

## Decision

Use `org.linphone:linphone-sdk-android:5.4.88` from Linphone's own Maven
repository (`https://download.linphone.org/maven_repository/`), isolated
behind our own `CallEngine` interface so the SIP SDK never leaks into UI or
domain code. The artifact is pinned by the SHA-256 recorded in
`docs/supply-chain.md`.

## Consequences

- The Android app licence must be GPL-3.0-or-later (ADR-0004).
- Linphone's merged manifest adds permissions (CAMERA, BLUETOOTH, etc.);
  our manifest strips camera requirement (`required=false` is already set by
  the SDK) and we document every permission in `docs/privacy.md`.
- Upgrades of the SDK require re-checking licence and native ABIs.
