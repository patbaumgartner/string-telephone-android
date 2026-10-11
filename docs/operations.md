# Operations

Start with the [beginner setup guide](setup.md). This page covers deployment,
configuration, recovery and the automated Asterisk lifecycle.

The stack remains a prototype. Keep administration on a trusted private
network and use HTTPS for anything beyond loopback development.

## Stack layout

`server/compose.yaml` starts a Kotlin/Spring Boot companion API and
Asterisk 22.11.0. The API provides REST, WebSockets, Spring Security login
and Thymeleaf administration. It writes managed PJSIP/dialplan files to the
`pbx-config` volume and reloads Asterisk over its private Manager Interface
(AMI). No Docker socket, shell access or privileged container is required.

| Service | Published ports | Persistent state |
| --- | --- | --- |
| API | 8080 HTTP; 8443 HTTPS when enabled | `api-data`: SQLite users, messages, desired PBX identities and settings |
| Asterisk | 5060/UDP SIP; 5061/TCP SIP/TLS; 10000-10100/UDP RTP | `pbx-config`: generated configuration |

AMI port 5038 is **not published**. Both services need the same private AMI
username/password. Never expose AMI through the host or public router.

## Environment variables

Copy `server/.env.example` to ignored `server/.env`. Set all required
credentials before starting Compose. There are no built-in accounts or
default admin passwords.

| Variable | Default | Meaning |
| --- | --- | --- |
| `ADMIN_USERNAME` | required | Administrator username, 1-64 ASCII letters, numbers, `_`, `-`, `.`; must start with a letter or number |
| `ADMIN_PASSWORD` | required | At least 24 characters, no line breaks; unique password-manager-generated passphrase recommended |
| `PBX_AMI_PASSWORD` | required | Separate random AMI secret, 24-1024 characters from letters, numbers, `_`, `.`, `~`, `-`; `openssl rand -hex 32` is suitable |
| `PBX_AMI_USERNAME` | `stringtelephone` | Private AMI account, same on API and PBX |
| `PBX_AMI_HOST` | `asterisk` | AMI host, API only |
| `PBX_AMI_PORT` | `5038` | AMI port, API only |
| `ASTERISK_MANAGED_DIR` | `data/pbx` outside Docker | Shared managed-config directory; Compose uses `/managed` in both containers |
| `API_DB` | `data/stringtelephone.db` | SQLite database; Compose uses `/data/stringtelephone.db` |
| `API_PORT` | `8080` | Plain HTTP/WS listener |
| `API_HTTPS_PORT` | `8443` | Additional HTTPS/WSS listener when a keystore is configured |
| `API_TLS_KEYSTORE` | unset | PKCS12/JKS file enabling native TLS |
| `API_TLS_KEYSTORE_PASSWORD` | empty | Keystore password |
| `API_TLS_KEYSTORE_TYPE` | `PKCS12` | Keystore type |
| `API_TLS_KEY_ALIAS` | first private key | Key alias |
| `API_TLS_KEY_PASSWORD` | keystore password | Private-key password |
| `ST_API_HTTP_PORT` | `8080` | Compose host HTTP port |
| `ST_API_HTTPS_PORT` | `8443` | Compose host HTTPS port |
| `ST_CERTS_DIR` | `./certs` | Mounted PBX PEM certificates and optional API keystore |
| `PJSIP_TLS_ENABLE` | `auto` | `auto`, `true` or `false`; true requires mounted certificates |

The old `API_ADMIN_TOKEN` is removed. Admin login uses Spring Security's
session authentication with PBKDF2 password hashing, CSRF protection on every
mutation, HttpOnly/SameSite cookies, a 15-minute idle timeout, no-store
responses and a same-origin CSP. Long admin passphrases are hashed in full,
not truncated to BCrypt's 72-byte limit.

Keep `/admin`, `/admin/**` and `/v1/admin/**` off the public router. Use a
private HTTPS route (for example over Tailscale). HTTPS is required for
remote use of the provisioning CLI. If terminating TLS at a trusted proxy,
configure forwarding only for that proxy and set
`SERVER_SERVLET_SESSION_COOKIE_SECURE=true`. Never log request bodies,
authorization headers or one-time credential responses.

## Provisioning users

Visit `/admin/` and sign in with `ADMIN_USERNAME` and `ADMIN_PASSWORD`.

- **Create account:** creates the API identity, hashes the SIP password,
  generates the endpoint/auth/AOR and dialplan, reloads and verifies the PBX.
  Accounts become ready only after successful reconciliation.
- **SIP passphrase:** optionally supply a 24-1024-character passphrase, including
  hyphens. Leave empty to generate a random password. API tokens are always
  generated and remain separate from SIP passwords.
- **Save credentials once:** the complete API token and SIP password are shown
  only in the create/reset response. Supply these and the username to the
  Android user. Refreshing or leaving the page loses that response.
- **Reset credentials:** replaces both credentials, closes existing
  WebSockets, removes the old SIP identity, ends its calls, then installs the
  new identity. The user must sign in again.
- **Disable:** immediately blocks API requests, closes WebSockets, removes
  the SIP endpoint and dialplan, and hangs up that account's active calls.
- **Enable:** restores the account using its current credentials.
- **Display name:** editable without changing the immutable username.

Usernames match `[a-z][a-z0-9-]{0,31}`; the dialplan special extensions
`a`, `e`, `h`, `i`, `o`, `s` and `t` are reserved Asterisk extensions.
Duplicate usernames return a conflict.

Only SHA-256 API-token hashes and SIP digest credentials are persisted.
SIP uses Asterisk's `password_digest=MD5:<digest>` with realm `asterisk`;
the server does not retain recoverable SIP passwords. Treat the database and
managed configuration as secrets: SIP digests are password-equivalent
material, not a substitute for encrypted disks and protected backups.

### Command-line administration

The installed CLI now authenticates to the **running API**. It never
modifies SQLite independently or bypasses PBX synchronization.

Inside Compose it inherits the configured admin credentials:

```bash
docker compose -f server/compose.yaml exec api \
  stringtelephone-provision create-user alice "Alice"
docker compose -f server/compose.yaml exec api \
  stringtelephone-provision list-users
docker compose -f server/compose.yaml exec api \
  stringtelephone-provision reset-user alice
docker compose -f server/compose.yaml exec api \
  stringtelephone-provision revoke-user alice
docker compose -f server/compose.yaml exec api \
  stringtelephone-provision enable-user alice
docker compose -f server/compose.yaml exec api \
  stringtelephone-provision reconcile
```

Outside Docker:

```bash
./gradlew --no-daemon :server:api:installDist
# Export ADMIN_USERNAME and ADMIN_PASSWORD securely, and set API_URL to HTTPS.
export API_URL=https://phone.example.com
server/api/build/install/api/bin/stringtelephone-provision list-users
```

HTTP is allowed for CLI loopback only (`localhost`, `127.0.0.1`, `::1`).
Create/reset print credentials to stdout once. When PBX setup fails, they
still print the credentials so you can save them, report **pending**, and
exit nonzero. Do not automatically repeat create after a pending result;
reconcile the existing account.

## Managed PBX settings

The dashboard controls codec preference (`opus`, `ulaw`, `alaw`), ring timeout
(5-120 seconds), device limit (1-5), symmetric RTP, and optional external IPv4
and local IPv4/CIDR network. Settings are validated server-side before being
persisted and applied. Arbitrary Asterisk text, trunks, PSTN routing and shell
commands are deliberately not exposed.

Transport reloads can briefly interrupt signaling. Schedule network changes
for a maintenance window. SIP listener ports, the RTP port range and
certificates remain deployment concerns, not editable configuration text.

### Private LAN audio (development only)

The debug APK accepts an explicit HTTP server and SIP/UDP. The release app
requires trusted HTTPS, SIP/TLS on 5061 and SRTP.

The Compose file already publishes UDP 10000-10100 and Asterisk uses that same
RTP range. For phones outside the Docker bridge:

1. Find the computer's LAN IP and Docker bridge network:
   `docker network inspect st-proto_pbx`.
2. In **Calling and network settings**, set the external IPv4 address to the
   computer's reachable LAN address and local network to the Docker bridge
   CIDR (not the phone's Wi-Fi network). Save and apply.
3. Allow SIP and UDP 10000-10100 through the host firewall only from the
   intended private network. Ensure both directions of routing work.
4. Test two real phones, microphone permission, mute and speaker routing.

Do not expose development HTTP/SIP/UDP directly to the Internet. For an
Internet-facing release deployment, publish HTTPS/WSS and SIP/TLS only,
install trusted certificates, disable the UDP transport and require
non-optimistic SRTP in a reviewed deployment configuration. The bundled
development stack supports optional SRTP for compatibility with SIP probes;
it is not a hardened public edge.

## Failure handling and reconciliation

SQLite is the desired-state source. Managed files are written atomically
per file. AMI reloads and endpoint-set verification must succeed before the
dashboard reports synchronized. Changes that remove/replace identities first
remove the old endpoint, then hang up its channels, then apply the final
configuration.

There is no transaction spanning SQLite and Asterisk. If PBX access fails:

- The dashboard shows an error and the pending state, never a success message.
- New/reset identities remain API-inactive until the change is confirmed.
- Disabled API identities stay blocked immediately; **SIP access or active
  calls may remain until the PBX is reachable**. Treat this as a security
  incident when urgent revocation is required; isolate the PBX/network.
- Pending disconnections persist across API restarts. The server retries
  every 15 seconds; **Reconcile and reload PBX** retries immediately.
- If files or runtime state drift, reconciliation restores the desired set.
  Do not run multiple API writers against one database/PBX pair.

Inspect without printing credentials:

```bash
docker compose -f server/compose.yaml ps
docker compose -f server/compose.yaml logs --tail=100 api asterisk
docker compose -f server/compose.yaml exec asterisk asterisk -rx 'pjsip show endpoints'
```

## Backups, upgrades and fresh installation

Stop the API while taking consistent backups of `api-data` and `pbx-config`,
and protect the backups as credentials. Keep admin/AMI environment secrets
and certificates separately. After restoring both volumes, start the stack
and reconcile; desired PBX configuration can be rebuilt from the database.

This version supports a **fresh installation**, not automatic import of
legacy hand-written PBX accounts. Existing tables/messages are not deleted
on startup, but unmanaged users are not silently assigned new SIP passwords.
Back up an old installation and retain its volumes before switching.

`docker compose -f server/compose.yaml down` preserves accounts.
Only when intentionally discarding **all** accounts, messages and managed PBX
configuration, run `docker compose -f server/compose.yaml down --volumes`.
The app's Clear local data action does not delete server messages.

## Verification and remaining limits

`scripts/test-prototype.sh` creates a uniquely named `st-admin-test-*`
Compose project and deletes only its throwaway volumes on cleanup. It needs
unused SIP/RTP ports; stop a retained demo first without deleting its volumes.
The check provisions accounts through admin login, registers long SIP
passphrases, completes calls, rejects bad credentials, checks SIP/TLS and
HTTPS, disconnects an established call through revocation, and tests reset
credentials. It does not verify Android microphone audio.

Messages are not end-to-end encrypted. Android incoming calls still require
a live app process; see [ADR-0006](adr/0006-background-call-policy.md).
