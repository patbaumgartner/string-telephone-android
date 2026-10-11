# Contributing

Thanks for improving String Telephone. Small, focused changes with behavioural
tests are easiest to review.

## Set up a development environment

Install JDK 17 and Android SDK 36, then clone the repository. Android Studio
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
./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug assembleRelease
./gradlew --no-daemon :server:api:test
yamllint .
node scripts/check-adrs.mjs
node scripts/check-secrets.mjs
node scripts/check-design.mjs
git diff --check
```

`check-design.mjs` compares the bundled Manrope fonts and the adaptive icon
against the design reference project, so it only passes on machines where
that reference is checked out next to this repository (see the `REF` path in
the script). CI runs the other gates.

### Screenshots

UI screens have Robolectric screenshot tests
(`app/src/test/.../ui/ScreenshotTest.kt`) that render each screen to PNG in
`app/build/screenshots/` — no device or emulator needed. After a visual
change:

```bash
JAVA_HOME=<jdk-17> ./gradlew --no-daemon :app:testDebugUnitTest
cp app/build/screenshots/*.png docs/screenshots/
```

Commit the refreshed `docs/screenshots/` images together with the UI change
so the README stays truthful. If a screenshot change is intentional, mention
it in the pull request; if it is unexpected, investigate before merging.

Docker is required only for the SIP prototype:

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
