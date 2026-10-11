# Start here

String Telephone has three parts: the Android app, a companion API for
contacts and messages, and Asterisk for calls. Building the APK does **not**
start either server or create accounts.

This guide starts with a private, throwaway development setup. Do not expose
its HTTP/UDP services or example passwords to the Internet. For real use,
follow the [TLS and operator guidance](operations.md).

## If someone already runs your server

Ask the administrator for:

| App field | What to enter |
| --- | --- |
| Server address | The full companion API URL, such as `https://phone.example.com`. Not the admin page or a `/v1` endpoint. |
| Username | Your provisioned username, for example `alice`. |
| API token | The `api_token` printed when your account was created. |
| SIP password | The separate `sip_secret` installed in Asterisk for that same username. |

The API and PBX must be reachable at the **same hostname**. The API can use a
custom port in its URL; calls use SIP/TLS on port 5061 (or UDP 5060 for an
explicit HTTP development server). The API token and SIP password are
different credentials; neither is the administrator password.

Install the app, enter those four values, and tap **Sign in**. Contacts open
only after the API accepts the token and confirms the username. This does not
prove that SIP registration succeeded: if the app reports that calls could
not connect to the PBX, check the SIP password and PBX configuration, then
use **Try again** on that notice.

## 1. Prepare a development computer

You need:

- Git.
- JDK 27, matching CI. The build also supports JDK 17–26.
- Android Studio with Android SDK Platform 37, its build tools and
  platform-tools installed. Accept the SDK licences in the SDK Manager.
- Docker Engine/Desktop with `docker compose` for the servers.
- Node.js 24 and `openssl` for the live SIP/TLS verification script.

Run all commands below from the repository root unless stated otherwise:

```bash
git clone https://github.com/patbaumgartner/string-telephone-android.git
cd string-telephone-android
export ANDROID_HOME="$HOME/Android/Sdk"
./gradlew --no-daemon :app:assembleDebug
```

On Windows, use `gradlew.bat` and set `ANDROID_HOME` in your environment;
the shell deployment/prototype helpers require a Bash-compatible environment.
You can also install the APK with Android Studio.

If your SDK is elsewhere, use that directory instead. Alternatively create
an ignored `local.properties` containing `sdk.dir=/absolute/path/to/your/sdk`.
Do not copy another developer's SDK path verbatim.

## 2. Start the development servers

First configure the administrator. There are **no default passwords**:

```bash
cp server/.env.example server/.env
chmod 600 server/.env
openssl rand -hex 32
```

Edit `server/.env` in a text editor:

- Set `ADMIN_USERNAME` to your chosen operator username.
- Set `ADMIN_PASSWORD` to a unique password-manager-generated passphrase
  of at least 24 characters. Long hyphenated phrases are supported.
- Set `PBX_AMI_PASSWORD` to the random hex value printed above. This is a
  separate private API-to-PBX credential; do not reuse the admin password.

Do not commit this file or share screenshots of it. Then start both services:

```bash
docker compose -f server/compose.yaml up -d --build --wait
curl --fail http://localhost:8080/health
```

The health response should be `{"status":"ok"}`. The first build downloads
Gradle dependencies and container images and can take several minutes.
If startup fails:

```bash
docker compose -f server/compose.yaml ps
docker compose -f server/compose.yaml logs --tail=100 api asterisk
```

Ports 8080, 8443, 5060/UDP, 5061/TCP and UDP 10000-10100 must be available. To move the API HTTP
port, for example, start Compose with `ST_API_HTTP_PORT=18080` and use that
port in the app. The default stack is a prototype, not an Internet-ready
deployment.

## 3. Create two accounts (PBX setup is automatic)

Open `http://localhost:8080/admin/` on the same computer. Sign in with the
`ADMIN_USERNAME` and `ADMIN_PASSWORD` you configured. In **Create account**:

1. Create `alice` with display name `Alice`.
2. Optionally choose a 24-1024-character SIP passphrase; leave empty to
   generate a random one.
3. Save the **API token** and **SIP password** from the one-time result.
4. Repeat for `bob` with display name `Bob`.

Both accounts should show **Ready** and PBX status should show
**Configuration synchronized**. The server creates SIP endpoints and
dialplan entries and reloads Asterisk for you. No manual PBX edits are needed.
If provisioning is pending, save the credentials first, resolve the error
shown on the dashboard, and use **Reconcile and reload PBX**.

Alternatively, the CLI uses the same running service:

```bash
docker compose -f server/compose.yaml exec api \
  stringtelephone-provision create-user alice "Alice"
docker compose -f server/compose.yaml exec api \
  stringtelephone-provision create-user bob "Bob"
docker compose -f server/compose.yaml exec api \
  stringtelephone-provision list-users
```

Each CLI create command prints an `api_token` and `sip_secret` **once**. Store
them privately. Do not paste them into issues, screenshots or Git. Accounts
persist in the Compose `api-data` volume; do not rerun `create-user` for an
existing username.

If a credential is lost, use **Reset credentials and end calls** in the
dashboard. Save the replacement credentials and sign in again on the phone.
There are no bundled Alice/Bob accounts or passwords.

### Long passphrases

Long, hyphen-separated word passphrases are supported in the Android SIP
password field and in the server's `ADMIN_PASSWORD` used to sign in to
the web administration page. These fields do not impose a 32-character
limit or remove hyphens. The admin password must be at least 24 characters.
Use a randomly generated, unique passphrase from a password manager.

The SIP password must exactly match the one created or reset through
administration. Typing a new password in Android does not change the server
password. Copy the complete value, including the final words or digits.

The Android API token field also preserves long, hyphenated values, but
account provisioning currently **generates** API tokens: use the printed
`api_token`, not a passphrase you choose yourself. Keep the API token, SIP
password and admin password distinct. Never reuse an example or a credential
shared in chat as a real secret.

## 4. Install and sign in

Enable USB debugging on an Android 8+ device and authorize your computer, or
start an emulator in Android Studio. Then:

```bash
scripts/deploy-device.sh
# Or, for an already running emulator:
scripts/deploy-emulator.sh
```

If multiple devices are connected, set `DEVICE_SERIAL` or `EMULATOR_SERIAL`
to a value shown by `adb devices`.

Choose the server address for your target:

| Target | Development API address |
| --- | --- |
| Android Studio emulator | `http://10.0.2.2:8080` |
| Physical phone on the same private Wi-Fi | `http://YOUR_COMPUTER_LAN_IP:8080` |

`localhost` on a phone means **the phone**, not your computer. Keep the phone
and server on a mutually reachable private network and allow the required
ports through the host firewall. An `adb reverse` tunnel for HTTP alone is
not sufficient for SIP/UDP or call audio.

HTTP works only in the **debug** APK and displays a development warning.
Release APKs require HTTPS with a trusted certificate, SIP/TLS and SRTP.
Do not bypass certificate verification to make a release installation work.

Sign in as Alice using her API token and SIP password. On another
device, sign in as Bob. Each should see the other in Contacts. Open Messages,
send a message, and verify it arrives on the other device.

## 5. Verify calls, then enable phone audio

The quickest reproducible PBX check is:

```bash
scripts/test-prototype.sh
```

This uses a unique `st-admin-test-*` Compose project and removes **only its
own** throwaway containers and volumes. Stop a running demo first to free the
SIP/RTP ports; its retained volumes are not touched. Success ends with
`PROTOTYPE-OK`.

The check registers endpoints, completes SIP call setup and teardown, rejects
bad credentials, and checks TLS registration and API HTTPS when `openssl`
and `curl` are available. It does **not** prove two-way microphone audio on
Android.

The default Compose stack publishes the RTP range. For two-way audio
between real phones on a private LAN, configure the advertised media address
through the dashboard as described in the
[LAN audio configuration](operations.md#private-lan-audio-development-only).
Two physical devices are recommended; emulator networking is not evidence
that real incoming calls or microphone routing work.

On both phones, grant microphone permission, keep the app open, then:

1. Call Bob from Alice and answer.
2. Speak in both directions; test mute, unmute, speaker and earpiece.
3. Hang up and call in the other direction.
4. Reject an incoming call and verify both devices leave the call screen.

Incoming calls are not guaranteed after process death or force-stop; reopen
the app first. This is a [documented prototype limit](adr/0006-background-call-policy.md).

## Troubleshooting

| Symptom | Check |
| --- | --- |
| Sign-in rejected | Use the account's API token, not its SIP or admin password. Confirm the username matches the token and the dashboard says Ready. |
| Cannot reach the server | Check `/health`, the address from the phone's network, firewall, port, and certificate. Container health on your computer does not prove phone reachability. |
| Wrong server response | Use the companion API base URL, not the PBX, admin page or another web service. |
| No contacts | Provision a second active API account. A contact-loading error has its own **Try again** action; it is not an empty directory. |
| Messages work, calls fail | Check the separate SIP password, same-hostname rule, port 5060/5061, TLS trust and PBX registration logs. |
| Calls connect but have no audio | Check RTP publishing, advertised media address, host firewall and microphone permission. |
| `create-user` says the account exists | Use the existing account or reset its credentials in the dashboard. Do not delete the database just to reset a password. |
| PBX provisioning pending | Check both containers, matching AMI credentials and the shared config volume. Save one-time credentials, then retry reconciliation. |
| Admin login rejected | Check `ADMIN_USERNAME` and `ADMIN_PASSWORD` in `server/.env`; restart the API after changing them. These are separate from Android credentials. |
| SDK not found | Set `ANDROID_HOME` or `sdk.dir`; install SDK 37 and accept its licences. |

For more detail, see [Operations](operations.md), [Contributing](../CONTRIBUTING.md)
and the [API protocol](protocol.md).

## Stop without deleting accounts

```bash
docker compose -f server/compose.yaml down
```

The volume remains. **Only for a disposable reset**, add `--volumes`; this
permanently deletes the demo's accounts and messages. Clearing local data in
the app signs out and removes local credentials/cache, not server messages.
