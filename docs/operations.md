# Operations

Running the String Telephone stack: the Asterisk 22 PBX, the companion API,
and account provisioning. This is operator-facing guidance; the API contract
itself is in [protocol.md](protocol.md).

**Status: prototype.** The compose stack is for development and throwaway
verification, not a hardened production deployment. Treat everything below as
the pre-operator-beta layout the ADRs describe.

## Stack layout

`server/compose.yaml` starts two services on a shared internal network:

| Service | Image | Port | Notes |
| --- | --- | --- | --- |
| `asterisk` | `andrius/asterisk:22.11.0_debian-trixie` (pinned by digest, ADR-0001) | `5060/udp` (SIP) | PJSIP config from `server/asterisk/config/`, health-checked via `asterisk -rx 'core show uptime'`. |
| `api` | built from `server/api/Dockerfile` (Gradle build on JDK 17, temurin 17 JRE runtime) | `8080` | SQLite at `/data/stringtelephone.db` in the `api-data` volume. |

The API container requires `API_ADMIN_TOKEN` (at least 24 characters) and
refuses to start without it. Prototype-only credentials in the compose file
(`proto-admin-token-…`) are placeholders, never production secrets.

RTP media ports are not published by the prototype compose file; SIP media
between containers relies on the Compose network for the two-node prototype.
A real deployment must expose an RTP port range and configure it in
`server/asterisk/config/pjsip.conf`.

## Environment variables (companion API)

| Variable | Default | Meaning |
| --- | --- | --- |
| `API_PORT` | `8080` | HTTP listen port. |
| `API_DB` | `data/stringtelephone.db` | SQLite database path (WAL mode). Parent directories are created automatically. |
| `API_ADMIN_TOKEN` | *required* | Admin bearer token, minimum 24 characters; the server stores only its SHA-256 hash. |

The API listens on `0.0.0.0`; put it behind a TLS-terminating reverse proxy
for anything beyond localhost. The app enforces `https://` server addresses.

## Building and running the API outside Docker

```bash
./gradlew --no-daemon :server:api:installDist
API_ADMIN_TOKEN='<random ≥24 chars>' API_DB=/tmp/st.db ./server/api/build/install/api/bin/api
```

The install directory also contains the second start script,
`stringtelephone-provision`, for the provisioning CLI below.

## Provisioning users

The `stringtelephone-provision` CLI (or the admin HTTP routes, see
[protocol.md](protocol.md)) creates accounts. Both the companion identity and
a matching SIP secret are generated together so PBX and companion identities
stay in sync:

```bash
export API_DB=data/stringtelephone.db
stringtelephone-provision create-user alice "Alice"    # prints api_token and sip_secret ONCE
stringtelephone-provision list-users
stringtelephone-provision revoke-user bob
```

- `create-user <username> <display-name>` prints the API token
  (`st_…`, 24 random bytes) and a SIP secret exactly once; neither is stored
  in cleartext (the API token is stored hashed). Capture them when you run it.
- Usernames match `[a-z][a-z0-9-]{0,31}`; display names are free-form and are
  what the app shows everywhere in the UI.
- `revoke-user` deactivates an account; its tokens stop authenticating
  immediately, and the user disappears from other clients' contact lists.

The CLI in this prototype prints credentials to stdout instead of writing the
PJSIP fragment itself; the operator copies the generated secret into the
PBX config volume (ADR-0001 consequences). Never commit generated secrets.

## The prototype SIP test

```bash
scripts/test-prototype.sh          # full pass, tears down afterwards
scripts/test-prototype.sh --keep   # leaves the stack running for inspection
```

Requires Docker. The script:

1. Builds and waits for the stack (`docker compose … up -d --build --wait`).
2. **G2** — registers `alice` (digest auth, `prototest-alice-pass`).
3. **G3** — completes a two-party REGISTER-INVITE-200-ACK-BYE call between
   `alice` and `bob` using the dependency-free Node SIP oracles in
   `integration-tests/sip/`.
4. **G4** — asserts `mallory` with a wrong password is rejected **and** left
   no registered contact (`pjsip show contacts`).

`prototest-*` passwords are throwaway placeholders baked into the prototype
PJSIP config (`server/asterisk/config/pjsip.conf`); they are never production
credentials.

## Prototype PJSIP accounts

`server/asterisk/config/pjsip.conf` ships two template endpoints (`alice`,
`bob`) using the placeholder passwords above, plus a dialplan
(`extensions.conf`) that routes one-to-one calls between them. For a real
deployment, generate endpoints with the provisioning flow instead of editing
the committed config by hand (ADR-0001).

## Known operational limitations

- SIP transport is plain UDP between app and PBX; SRTP and TLS transport are
  planned (see [SECURITY.md](../SECURITY.md)). Run the stack on a trusted
  host or VPN.
- Messages are stored server-side in SQLite without end-to-end encryption
  (ADR-0003); the server can read them.
- Incoming calls require the app process to be alive (ADR-0006). After
  force-stop, calls are missed until the app is reopened.
- There is no automated backup story for the SQLite database yet; back up the
  `API_DB` path with your normal file-backup tooling while it is small.
- The Asterisk 22 LTS upgrade path (image digest rotation, config migration)
  is expected here before operator beta; the pinned base image is
  `andrius/asterisk:22.11.0_debian-trixie` (ADR-0001).
