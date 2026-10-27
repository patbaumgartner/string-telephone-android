# ADR-0013: Video calls — VP8 through Asterisk, camera only in the foreground

Date: 2026-10-27
Status: Accepted

## Context

Phase 12 adds one-to-one video to a project whose calls are audio-only,
anchored on a self-hosted Asterisk 22 (ADR-0001) with liblinphone as the SIP
engine (ADR-0002). The messaging side has full parity with mainstream
messengers; calls did not. The maintainer chose **full parity**: send and
receive video, camera flip, camera off while still seeing the peer, and an
audio call that survives backgrounding. Anything less would leave the app
visibly behind the product it is compared against.

Four constraints shape the decision:

1. **Media path.** `direct_media=no` is already set, so every call relays
   through Asterisk. That gives one NAT surface (`external_media_address`
   plus `local_net` when configured) and works without ICE. Keeping video on
   the same path costs server bandwidth but removes a second NAT/ICE surface
   and any peer-to-peer reachability problem.
2. **Codec surface.** Every liblinphone Android build ships VP8; Asterisk 22
   relays it as a plain payload type; VP8 has no royalty surface and needs no
   hardware-specific configuration. H264 (hardware decode, better battery) and
   AV1 are possible follow-ups, not part of this phase.
3. **Android background limits.** Since Android 11 a backgrounded app cannot
   receive camera frames. Android 14 further requires a declared
   `camera` foreground-service type for a background camera, which this app
   deliberately does not have: the call service uses the `microphone` type
   only, because there is no Telecom `ConnectionService` (ADR-0006).
4. **Self-hosted administration.** Video must be switchable off for
   deployments that cannot afford the bandwidth or do not want it, without a
   client release.

## Decision

1. **Asterisk stays in the media path; no ICE on the PBX.** Video is relayed
   exactly like audio. liblinphone's ICE candidates are ignored by the
   anchored path and no ICE configuration is added to Asterisk. The RTP range
   (`10000-10100`) and the Compose UDP export stay unchanged: a video call
   consumes two media streams (four RTP/RTCP ports), so the range still
   supports roughly 25 concurrent video calls.
2. **VP8 only.** `LinphoneCallEngine` enables the VP8 payload and disables
   every other video payload at startup; the bitrate is capped at 1 Mbit/s
   because a self-hosted PBX relays every frame. The negotiated codec is
   visible in the `200 OK`; a peer that offers only other video codecs gets an
   `m=video 0` answer and the call continues as audio.
3. **One admin setting, `video: vp8 | none`.** `PbxSettings` appends the codec
   to the endpoint `allow=` list after the audio order (or omits it entirely
   for `none`). The settings row is JSON-backed, so the defaulted field needs
   no Flyway migration. `none` disables video for new calls; running calls
   keep their negotiation until they end.
4. **Camera only while the call screen is visible.** The camera turns on for
   a video INVITE, for a video answer, or when the user adds video mid-call.
   `MainActivity.onStop` calls `CallEngine.releaseVideo()`, which removes the
   video stream (`CallParams.setVideoEnabled(false)`) and keeps the audio
   call running under the existing microphone service. Returning to the app
   does **not** silently re-enable the camera; the call screen shows camera
   off until the user turns it on. No `camera` FGS type,
   `FOREGROUND_SERVICE_CAMERA`, or hidden capture exists anywhere.
5. **Camera off keeps the peer visible.** The in-call camera button
   re-INVITEs with `videoDirection = RecvOnly` (stop sending, keep watching).
   Only backgrounding removes the stream entirely. Turning video on for an
   audio call re-INVITEs `SendRecv`.
6. **Video calls default to the speaker.** `defaultAudioRoute` prefers a
   connected headset, then the speaker for video, then the earpiece for audio
   calls; the user's explicit route choice still wins for the rest of the
   call.
7. **The camera permission gate mirrors the microphone gate.** A denied
   camera never blocks the call: the action falls back to audio and the UI
   explains recovery, exactly like microphone denial. The engine re-checks the
   permission at INVITE/answer time, so a permission revoked between the tap
   and the request still degrades to audio instead of a stream without frames.
8. **Video state is derived, not assumed.** This SDK build has no dedicated
   video-state listener, so `videoActive` comes from the `StreamsRunning`
   params (`usedVideoPayloadType`, `videoDirection`) and the first frame is
   requested with `requestNotifyNextVideoFrameDecoded()`. The UI shows a
   deterministic "Waiting for video…" placeholder until then.
9. **The call screen stays awake during video** (`keepScreenOn`) and renders
   the remote stream full-bleed with a draggable self-view picture-in-picture.
10. **Notifications say "video" when the INVITE carried a video stream**, so
    the recipient knows what answering means.

## Consequences

- A video call is auditable end to end: SIP negotiation (prototype `G3b`),
  the engine seam, the permission gate, the background downgrade and the
  server setting each have an automated check; hardware behaviour remains a
  manual device checklist because emulator cameras only show a synthetic
  scene.
- Video bandwidth is server bandwidth. Deployments on metered links should
  set `video = none`; the operations guide documents the sizing.
- VP8 keeps the build portable but forgoes hardware decode, so older devices
  spend more CPU and battery than an H264 call would. H264/AV1 require a new
  decision (codec allow-list, server setting, and per-device capability
  detection) and are deliberately out of scope.
- Audio-only peers keep working: an endpoint that does not allow video answers
  `m=video 0`, and the app renders the familiar audio call screen.
- The no-hidden-camera rule constrains future features (for example
  picture-in-picture video calls outside this app's call screen) — those
  would need the camera FGS type and a new decision.
