# ADR-0007: Spring Boot administration with managed Asterisk provisioning

## Status

Accepted. Supersedes the Ktor framework choice in ADR-0003 and the manual
account-installation prototype workflow described by ADR-0001.

## Context

API-only provisioning left identities and PBX credentials out of sync.
Operators had to edit PJSIP/dialplan files, reload Asterisk and separately
terminate calls. A token pasted into a small static admin page was not a
complete account-management system.

## Decision

Use Kotlin with Spring Boot 4, Spring MVC, native Spring WebSockets,
Spring Security and server-rendered Thymeleaf. Preserve Android's v1 JSON,
bearer authentication, WebSocket path and event envelope. Keep SQLite and
the existing messaging/idempotency behavior.

Require `ADMIN_USERNAME` and `ADMIN_PASSWORD` from the environment, with no
default credentials. Use session fixation protection, PBKDF2 password
hashing, CSRF-protected POST forms, HttpOnly/SameSite cookies, CSP and
no-store responses. Keep Android routes stateless.

Persist desired PBX identities and validated settings alongside accounts.
Store SIP digest credentials rather than plaintext passwords. Generate
managed PJSIP and explicit per-user dialplan entries on a shared volume;
reload, verify endpoints and terminate channels over private authenticated
AMI. Never expose AMI publicly or give the API a Docker socket.

Disable/reset blocks API access and closes streams before applying PBX
changes. Replace/remove old SIP identities and hang up affected channels.
Persist pending disconnection intent so retries survive API restarts.
Report partial failures and retry reconciliation every 15 seconds.

Support a fresh installation rather than silently migrating handwritten PBX
passwords. Never delete retained data automatically. The CLI uses the
running API and the same authentication/provisioning path.

## Consequences

- Administrators can manage accounts and validated codec/NAT/calling
  settings without editing PBX files.
- No transaction spans SQLite, managed files and Asterisk. Pending state and
  explicit failure reporting are required; unreachable PBX means revocation
  cannot guarantee immediate SIP disconnection.
- Configuration is single-writer. Multiple API replicas against one
  database/PBX pair are unsupported.
- SIP digest material is credential-equivalent and needs protected volumes
  and backups. MD5 here is protocol interoperability, not admin password
  hashing.
- Transport reloads may briefly interrupt signaling. Certificates, firewall
  ports, advanced dialplans/trunks and public-edge hardening stay operator
  responsibilities.
- AGPL/GPL separation and the Android manual-DI architecture are unchanged.
