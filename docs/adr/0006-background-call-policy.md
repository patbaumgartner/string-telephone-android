# ADR-0006: Background incoming-call policy — no promised cold-start ringing

Date: 2026-10-11
Status: Accepted

## Context

Android restricts background execution: since Android 12, apps cannot start
foreground services from the background for most types; microphone FGS use
while backgrounded is restricted (Android 11+); OEM battery savers
aggressively stop processes; FCM push is excluded by the no-cloud rule.

Verified platform behaviour (developer.android.com, 2026-10-11):
- A call **in progress** holds a microphone/phone-call foreground service and
  survives screen-off.
- **Incoming call delivery while the process is dead or force-stopped is
  not deliverable without push infrastructure.** When the app is alive but
  backgrounded, a visible ongoing service with a CallStyle notification
  keeps registration and can receive calls; after force-stop, the process
  stays dead until the user opens the app.
- OEM "battery optimisation" can still kill the service; we do not prompt
  the user to bypass optimisation by default (dark-pattern risk).

## Decision

1. In-app and backgrounded-but-alive calls: supported via a user-visible
   foreground service with a CallStyle notification during calls.
2. **No promise of cold-start ringing.** After process death/force-stop,
   incoming calls are missed until the user opens the app; this is an
   explicit, documented v1 limitation.
3. No battery-optimisation exemption prompts in v1.
4. Future option (opt-in, separate threat review): a self-hosted push
   relay (e.g. UnifiedPush) — out of scope for v1.

## Consequences

- README and in-app status screen document "calls ring while the app is
  alive (foreground or background); after force-stop the app must be
  reopened".
- Telecom integration (CallStyle) is used so active calls behave natively.
- The app must not use `SYSTEM_ALERT_WINDOW` or boot receivers to fake
  reliability.
