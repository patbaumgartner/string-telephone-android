# ADR-0016: Release signing and GitHub Releases distribution (aligned pipeline)

## Status

Accepted (2026-11-14). Replaces the ad-hoc pipeline shipped with v0.1.0 and
aligns this app with spaced-repeater-android, dice-roller-android and
esp32-room-watchdog-android.

## Context

v0.1.0 published per-ABI signed APKs on `v*` tags, signed from repository
secrets named `ANDROID_KEYSTORE_*` that were decoded to `ST_KEYSTORE_*`
environment variables. The `snapshot` prerelease shipped debug-signed APKs
with `SHA256SUMS.txt`, no AAB, and no tag-derived `versionCode`. That
keystore was shared with another app, so its private key existed in two
repositories' secret stores — a leak in either would compromise both apps.
The other three Android apps instead use one dedicated key per app held in
`SR_KEYSTORE_*` secrets, publish a signed snapshot on every `main` push, and
derive the version from the `vX.Y.Z` tag. Divergent secret names, snapshot
semantics, checksum file names and versioning make operating four repos
error-prone.

## Decisions

- **Pipeline:** `.github/workflows/release.yml` publishes to the GitHub
  Releases page:
  - every push to `main` rebuilds the **Snapshot** prerelease (tag `snapshot`)
    with signed per-ABI APKs (`string-telephone-snapshot-<abi>.apk`) and
    `SHA256SUMS`;
  - every push of a tag `vX.Y.Z` creates a release with signed per-ABI APKs
    (`string-telephone-<version>-<abi>.apk`), a signed AAB
    (`string-telephone-<version>.aab`, for Play-style distribution),
    `SHA256SUMS`, install instructions, and the signer certificate
    fingerprint. The tag sets `versionName` (`v0.2.0` → `0.2.0`) and
    `versionCode` (`major*10000 + minor*100 + patch` → `200`) via
    `-PversionName` / `-PversionCode`; snapshot builds use
    `versionCode = 2_000_000_000 + run_number` so they always sort above any
    tag-derived code and can update over releases.
- **Per-ABI assets:** kept (not a universal APK): the Signal/linphone native
  libs make a universal APK ~420 MB while a split is ~110–125 MB. The
  universal build (`app-universal-*.apk`) is still produced locally for
  `scripts/deploy-*.sh` but is not uploaded. All other pipeline mechanics
  (triggers, signing, secrets, versioning, checksums, notes, verification)
  are identical to the other three apps.
- **Signing key:** a dedicated PKCS12 keystore (RSA 2048, alias
  `string-telephone-release`, 30-year validity) generated 2026-11-14. It
  exists only as the repository secrets `SR_KEYSTORE_BASE64`,
  `SR_KEYSTORE_PASSWORD`, `SR_KEY_ALIAS`, `SR_KEY_PASSWORD` (base64-encoded
  keystore plus passwords) and in one offline backup outside the repository.
  The legacy `ANDROID_KEYSTORE_*` secrets (which held the shared key) were
  deleted. Signer certificate SHA-256:
  `7F:73:86:D7:FC:E8:03:38:E1:8D:C0:DD:8C:04:9C:77:1A:36:F5:36:62:7D:48:6F:C9:E3:90:A7:DE:A6:DD:6A`.
  Snapshots and releases share this key, so they update in place.
- **Build wiring:** `app/build.gradle.kts` reads the credentials from the
  `SR_KEYSTORE_FILE` / `SR_KEYSTORE_PASSWORD` / `SR_KEY_ALIAS` /
  `SR_KEY_PASSWORD` environment variables (CI) or a local, git-ignored
  `keystore.properties` file (`storeFile` / `storePassword` / `keyAlias` /
  `keyPassword`), and attaches the `release` signing config only when they
  are present — a plain source checkout still builds an unsigned release APK.
  The four `DEV_*` debug prefill fields stay empty in release builds
  regardless.
- **Verification:** both jobs run `apksigner verify --print-certs` on every
  published APK and fail fast if the signing secrets are missing; users can
  verify downloads against the published `SHA256SUMS` and the certificate
  fingerprint in the release notes. Workflow permissions are
  `contents: read` by default with `contents: write` only in the two
  publishing jobs.

## Consequences

- Users can download and install the per-ABI APKs directly from the Releases
  page; releases and snapshots share one key and update in place.
- One-time migration: v0.1.0 (versionCode 1) was signed with the earlier
  shared key, so the first release signed with the new key cannot update over
  v0.1.0 — users uninstall v0.1.0 once. Later releases chain normally.
- The release key is a single point of failure: the offline backup must be
  kept safe (password manager / HSM). Key rotation requires re-signing and a
  documented migration.
- The workflow needs `contents: write` only in the two publishing jobs.
