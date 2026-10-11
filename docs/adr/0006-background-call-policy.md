# ADR-0006: Background incoming-call policy — no promised cold-start ringing

Date: 2026-10-11
Status: Accepted

Amended: 2026-10-18 — request microphone permission before call actions and
use the microphone FGS type supported by the current non-Telecom implementation.

Amended: 2026-10-18 — keep the process alive while signed in. Calls and
messages previously reached the user only while the Activity was open, because
SIP and the realtime stream were tied to the screen's ViewModel. See
"Background session" below.

## Context

Android restricts background execution: since Android 12, apps cannot start
foreground services from the background for most types; microphone FGS use
while backgrounded is restricted (Android 11+); OEM battery savers
aggressively stop processes; FCM push is excluded by the no-cloud rule.

Verified platform behaviour (developer.android.com, 2026-10-11):
- A call **in progress** holds a microphone foreground service and
  survives screen-off.
- **Incoming call delivery while the process is dead or force-stopped is
  not deliverable without push infrastructure.** When the app is alive but
  backgrounded, a visible ongoing service with a call notification
  keeps registration and can receive calls; after force-stop, the process
  stays dead until the user opens the app.
- OEM "battery optimisation" can still kill the service; we do not prompt
  the user to bypass optimisation by default (dark-pattern risk).

## Decision

1. In-app and backgrounded-but-alive calls: supported via a user-visible
   microphone foreground service with an ongoing notification during calls.
2. **No promise of cold-start ringing.** After process death/force-stop,
   incoming calls are missed until the user opens the app; this is an
   explicit, documented v1 limitation.
3. No battery-optimisation exemption prompts in v1.
4. Future option (opt-in, separate threat review): a self-hosted push
   relay (e.g. UnifiedPush) — out of scope for v1.

### Background session

5. An app-scoped `SessionController` owns SIP registration, the realtime
   stream, and call and message notifications, independent of any
   Activity.
6. While a verified account is signed in, a `specialUse` keep-alive
   foreground service holds the process. It shows a minimum-importance
   "Connected" notification and is started only from a foreground context
   (app launch or sign-in). Sign-out, or rejected credentials, stops it.
   Network failures retry with backoff.
7. Incoming calls ring through liblinphone native ringing. A call-style
   notification offers Answer and Decline, plus a full-screen intent when
   the OS grants it. Answer opens the app, so the microphone permission gate
   still applies. Unanswered calls leave a missed-call notification.
8. Messages for any conversation the user is not currently viewing post a
   per-conversation notification and increment an unread badge.

## Consequences

- README and in-app status screen document "calls ring while the app is
  alive (foreground or background); after force-stop the app must be
  reopened".
- Microphone permission is requested before starting or answering a call.
  Denial leaves messaging available and offers app settings for recovery.
- The current liblinphone implementation does not register a Telecom
  `ConnectionService`; it therefore does not request the `phoneCall`
  foreground-service type, which requires a dialer role or self-managed
  Telecom account. Native Telecom/CallStyle integration remains future work.
- The app must not use `SYSTEM_ALERT_WINDOW` or boot receivers to fake
  reliability.
- The keep-alive notification is always present while signed in. Users can
  hide it by turning off its channel; the service keeps running.
- On Android 14+ the full-screen call screen needs the "full-screen
  notifications" permission. Without it, calls show as a heads-up
  notification. Contacts shows a notice linking to that setting (or to the
  notification settings when notifications are off), re-checked on resume.
- Like the system dialer, the call screen opens by itself only on a locked or
  dark screen; on an unlocked device in use Android shows a heads-up
  notification. Opening over another app would need `SYSTEM_ALERT_WINDOW`,
  which this policy forbids.
- After reboot or force-stop, nothing restarts the service until the user
  opens the app.
