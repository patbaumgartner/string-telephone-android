# Contributing

Thanks for improving String Telephone. Small, focused changes with behavioural
tests are easiest to review.

## Set up a development environment

For a complete app/server demo, start with [the setup guide](docs/setup.md).
The steps below set up a build environment, not accounts or a running PBX.

Install JDK 27 (17–26 also work; CI builds on 27) and Android SDK 37, then
clone the repository. Android Studio
can import the root directory directly; command-line builds use the checked-in
Gradle wrapper and need `ANDROID_HOME` set, or an `sdk.dir` line in
`local.properties`.

```bash
git clone https://github.com/patbaumgartner/string-telephone-android.git
cd string-telephone-android
export ANDROID_HOME="$HOME/Android/Sdk"
./gradlew :app:assembleDebug
```

Real devices, an Asterisk server, and credentials are not required for unit
tests or compilation. For manual device testing, copy `local.properties.example`
to the ignored `local.properties` and use development credentials only.
`stringtelephone.password` prefills the **API token**, while
`stringtelephone.sipSecret` prefills the separate **SIP password**. Prefills
exist only in debug builds; do not share debug APKs containing credentials.

Use `scripts/deploy-device.sh` to build, install, and launch on a connected
physical device. For a running, fully booted AVD, use
`scripts/deploy-emulator.sh`. Select among multiple targets using `DEVICE_SERIAL`
or `EMULATOR_SERIAL`; the generic `ANDROID_SERIAL` is used when the specific
selector is unset. An offline, unauthorized, wrong-type, or ambiguous target
fails before building. Unlike the reference project's emulator helper, these
scripts do not manage emulator processes; start the AVD in Android Studio first.
The helpers use SDK platform-tools from `ANDROID_HOME`, `ANDROID_SDK_ROOT`, or
`$HOME/Android/Sdk`, falling back to `adb` on `PATH`.

See [docs/architecture.md](docs/architecture.md) before changing module
boundaries, and follow [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md) in project spaces.

## Make a change

1. Create a branch from `main`.
2. Keep the change scoped to one concern.
3. Add or update tests for changed behaviour. Pure logic belongs in `core/`
   (or as framework-free classes in the app) so it stays unit-testable.
4. Keep user-facing text in `app/src/main/res/values/strings.xml`.
5. Never commit `local.properties`, tokens, SIP secrets, APKs, keystores, or
   generated build output. The provisioning CLI generates secrets; they are
   never committed.
6. User-visible protocol or architecture changes need an ADR under
   `docs/adr/` before or with the change.

Use Conventional Commit subjects, for example
`fix: reject http companion servers` or `docs: document force-stop limitation`.

## Validate

```bash
./gradlew --no-daemon :core:test :app:testDebugUnitTest :app:lintDebug \
  :app:assembleDebug :app:assembleRelease :server:api:test :server:api:installDist
yamllint .
find . -name '*.sh' -not -path './*/build/*' -exec shellcheck {} +
node scripts/check-adrs.mjs
node scripts/check-secrets.mjs
node --test scripts/test-deploy.mjs
node --test scripts/test-provisioning.mjs
node scripts/check-design.mjs
git diff --check
```

`check-design.mjs` compares the bundled Manrope fonts against the design
reference project and checks adaptive icon layers and cyan brand resources,
so it only passes on machines where
that reference is checked out next to this repository (see the `REF` path in
the script). CI runs the other gates.

Install Node.js 24, yamllint, and ShellCheck for the repository checks. If
available, `actionlint` also validates the GitHub workflows. Deploy-helper
tests use Node's built-in runner and mocked SDK/Gradle tools: no device,
emulator, or credentials are needed, and temporary fixtures are removed.
The provisioning smoke tests run the actual installed CLI against an HTTP
fixture, verifying session login and CSRF. Backend integration tests exercise
the real Spring server with an isolated PBX boundary. Run
`:server:api:installDist` before the CLI tests.

### Screenshots

UI screens have Robolectric screenshot tests
(`app/src/test/.../ui/ScreenshotTest.kt`) that render each screen to PNG in
`app/build/screenshots/` — no device or emulator needed. After a visual
change:

```bash
JAVA_HOME=<jdk> ./gradlew --no-daemon :app:testDebugUnitTest
cp app/build/screenshots/*.png docs/screenshots/
```

The suite also renders dark variants and the sign-in error state.
A compact-layout test checks error/help/button reachability at 320×480 dp
and 150% font scale. Review generated images as well as test results: renders
alone are not visual assertions.

Commit the refreshed `docs/screenshots/` images together with the UI change
so the README stays truthful. If a screenshot change is intentional, mention
it in the pull request; if it is unexpected, investigate before merging.

Docker is required for the server test suite (Testcontainers starts a
PostgreSQL 18 container for `:server:api:test`) and for the SIP prototype:

The prototype script uses a uniquely named `st-admin-test-*` project and
deletes only its own throwaway volumes. Stop a demo first to free SIP/RTP
ports; its retained data is not deleted. The test covers automatic provisioning,
reset and call disconnection against real Asterisk, not microphone audio.

```bash
scripts/test-prototype.sh
```

Do not suppress Android lint warnings globally; each suppression needs a
comment explaining why it is safe.

## Pull requests

Explain the user-visible effect, why the change is needed, and exactly how it
was tested. Include screenshots for visual changes when they make review
materially easier. Do not include credentials, tokens, or recordings in
screenshots, logs, fixtures, or issue reports.

Security vulnerabilities belong in a private report as described in
[SECURITY.md](SECURITY.md), not a public issue.

## Dependency updates and security CI

Dependabot opens weekly updates for Gradle dependencies, GitHub Actions, and
the server Dockerfiles. Kotlin, Spring, AndroidX, and OkHttp updates are grouped
to keep related components together. Major updates remain manual.

The security workflow runs CodeQL on pushes, pull requests, and weekly; its
manual build includes both Android and server sources with build caches
disabled. Dependency review rejects pull requests introducing known moderate
or higher severity vulnerabilities.

The Dependabot workflow queues patch/minor PRs for rebase auto-merge. Before
using it, a repository administrator must:

1. Enable **Allow auto-merge** and **Allow rebase merging** in repository settings.
2. Protect `main` with required CI and security checks (including
   **Test, lint, and package**, **CodeQL**, and **Dependency review**) so
   auto-merge cannot bypass validation.
3. Allow GitHub Actions to create/approve pull requests if required by the
   repository or organization policy.

The workflow does not check out PR code or merge major/unknown update types.
It queues merges rather than bypassing protection. No repository settings are
changed by adding these files; check the workflow result if auto-merge is
disabled or restricted.

## Publish a release

`.github/workflows/release.yml` publishes APKs to
[GitHub Releases](https://github.com/patbaumgartner/string-telephone-android/releases):

- Every push to `main` (or a manual workflow run) updates the **Snapshot**
  prerelease with freshly built per-ABI debug APKs and `SHA256SUMS.txt`.
- Pushing a `vX.Y.Z` tag builds the release APKs, signs them, verifies every
  published APK with `apksigner`, and publishes the release with the four
  per-ABI APKs (`arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64` — users pick the one
  matching their device), `SHA256SUMS.txt` and the signing certificate
  fingerprint in the notes. APKs are ABI-split because the Signal/linphone
  native libraries make a universal APK ~420 MB; a split is ~110–125 MB. The
  universal build (`app-universal-*.apk`) is still produced locally for
  `scripts/deploy-*.sh` but is not uploaded.

One-time repository setup (admin):

1. Generate the release key once and keep it offline afterwards:

   ```bash
   keytool -genkeypair -keystore release.keystore -alias stringtelephone \
     -keyalg RSA -keysize 4096 -validity 10000 -storetype PKCS12
   ```

   Never commit it: `*.keystore`, `*.p12` and `keystore.properties` are
   git-ignored and `scripts/check-secrets.mjs` fails on committed key material.
2. Add the repository secrets `ANDROID_KEYSTORE_BASE64`
   (`base64 -w 0 release.keystore`), `ANDROID_KEYSTORE_PASSWORD`,
   `ANDROID_KEY_ALIAS` and `ANDROID_KEY_PASSWORD`.
3. Bump `versionCode`/`versionName` in `app/build.gradle.kts`, move the
   `## Unreleased` section of `CHANGELOG.md` to `## [X.Y.Z] - <date>`, and
   merge to `main` with green CI.
4. Tag and push: `git tag vX.Y.Z && git push origin vX.Y.Z`.

The workflow decodes the keystore and passes `ST_KEYSTORE_FILE` plus
`ST_KEYSTORE_PASSWORD` / `ST_KEY_ALIAS` / `ST_KEY_PASSWORD` to
`:app:assembleRelease`; `app/build.gradle.kts` signs only when
`ST_KEYSTORE_FILE` is set, and the workflow refuses to publish an unsigned
APK. For a local signed build, put the same four `ST_KEYSTORE_*` values in an
ignored `keystore.properties` instead. Snapshots never need the secrets: they
ship only the debug APK, which is debug-signed and contains no prefilled
credentials (CI has no `local.properties`).
