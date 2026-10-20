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
- The production Android client requires verified SIP/TLS on port 5061 and
  mandatory SDES SRTP media encryption. The PBX must be configured to require
  the same encryption and present a publicly trusted certificate for its
  hostname. Plaintext transport is development-only; the prototype Compose
  stack must never be published as-is.
- Messages are not end-to-end encrypted; the UI and docs must never claim E2EE
  (ADR-0003). Reports demonstrating otherwise are in scope.
- The companion API admin surface uses Spring Security session authentication
  with environment-configured `ADMIN_USERNAME` and `ADMIN_PASSWORD` (at least
  24 characters), PBKDF2 hashing and CSRF-protected mutations. No default admin
  credentials exist. Production operators must additionally restrict both the
  `/admin/` UI and `/v1/admin` endpoints to Tailscale/private access at the edge;
  the API alone does not enforce that network boundary. Reports that reach
  admin routes without authentication or bypass CSRF are in scope.
- Realtime credentials use bearer headers over WSS, never query parameters.
  Rotate credentials that were previously exposed through URL/access logging.
- The live verification script uses throwaway placeholder passwords
  (`prototest-*`); the normal Compose stack requires configured secrets.
- PBX management uses private AMI and generated configuration. Never publish
  AMI or mount the Docker socket. Treat SIP digests, database volumes and their
  backups as credentials. PBX outages can delay SIP revocation even though
  API access is already blocked; the dashboard must report this explicitly.
