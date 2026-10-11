# Third-party notices

This file lists third-party components included in or linked by String
Telephone. Regenerate and re-verify before any 1.0 release (ADR-0004).

## Android app

### linphone-sdk-android

The `org.linphone:linphone-sdk-android` artifact (5.4.88) provides SIP
signalling, RTP media, and TLS/SRTP. It is copyright Belledonne Communications,
SAS and distributed under the GNU General Public License, version 3. The full
text is in [LICENSE](LICENSE). Because the app links it, the app is distributed
under GPL-3.0-or-later (ADR-0002, ADR-0004).

### AndroidX, Kotlin, and Compose libraries

The AndroidX libraries, Kotlin standard library, coroutines, and serialization
libraries, and JetBrains annotations are distributed under the Apache License,
version 2.0, copyright their respective authors (Google LLC, JetBrains s.r.o.).

### OkHttp

`com.squareup.okhttp3:okhttp` is copyright Square, Inc. and distributed under
the Apache License 2.0.

### androidx.security:security-crypto

Encrypted credential storage uses `androidx.security:security-crypto`, which
includes Google Tink (Apache-2.0) and depends, at compile time only, on
error-prone annotations (MIT-style licence terms, runtime-absent).

### Material Design icons

The vector drawables named `ic_*.xml` in `app/src/main/res/drawable/` are
derived from the Material Design icons, copyright Google LLC, distributed
under the Apache License 2.0.

## Companion API (AGPL-3.0-or-later)

- Spring Boot, Spring Framework, Spring Security, Thymeleaf, Kotlin,
  kotlinx.serialization, kotlinx.coroutines: Apache-2.0.
- Logback: EPL-1.0 / LGPL-2.1 dual licence.
- sqlite-jdbc (Xerial): Apache-2.0; bundles SQLite, public domain.

## Server-side images (not distributed to app users)

- Asterisk 22 LTS container image `andrius/asterisk` (GPL-2.0 components
  server-side only; never distributed to app users).
- Node.js-based SIP test scripts in `integration-tests/` are original to this
  project and licensed with the repository (GPL-3.0-or-later). SIPp, if later
  adopted for load testing, is GPL-2.0 and test tooling only.
