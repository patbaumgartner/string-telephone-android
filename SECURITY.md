# Security Policy

## Supported versions

String Telephone is pre-1.0 prototype software. Security fixes are applied to
the latest commit on `main`; older commits and locally modified APKs are not
supported.

## Report a vulnerability

Please use GitHub's private vulnerability reporting flow at:

<https://github.com/patbaumgartner/string-telephone-android/security/advisories/new>

Include the affected version or commit, Android version, impact, reproduction
steps, and any proposed mitigation. Do not include real credentials, SIP
secrets, API tokens, or publicly exploitable details in an issue.

You should receive an acknowledgement within seven days. Valid reports will be
investigated privately, fixed on supported code, and disclosed after affected
users have a reasonable opportunity to update.

## Scope notes

- The Android app never sends SIP or API credentials over cleartext transport;
  the onboarding screen rejects non-HTTPS companion URLs. Reports that
  demonstrate credential leakage past that boundary are in scope.
- SIP transport is plain UDP in this prototype, protected by network
  positioning (the server is meant to run on a trusted host or VPN). SRTP and
  TLS transport are planned; a report about on-path audio interception on the
  public internet is accepted but will likely resolve to the documented
  limitation rather than a code fix.
- Messages are not end-to-end encrypted; the UI and docs must never claim E2EE
  (ADR-0003). Reports demonstrating otherwise are in scope.
- The companion API admin surface is protected by a bearer token of at least
  24 characters; reports that reach admin routes without it are in scope.
- The prototype compose stack uses throwaway placeholder passwords
  (`prototest-*`); they are not production credentials.
