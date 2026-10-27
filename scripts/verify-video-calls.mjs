#!/usr/bin/env node
/**
 * Video-call verification (Phase 12, ADR-0013): bidirectional VP8 video through
 * a self-hosted Asterisk, camera only while the call screen is visible.
 * Exits nonzero with a reason per failure; prints "video call verification
 * passed" only after every assertion passes.
 *
 * Checks:
 *  - Server: PbxSettings.video (vp8|none) validated, appended last to the
 *    endpoint allow= list, admin form + managed-admin helper wiring, tests.
 *  - Prototype: caller offers VP8/VP9, callee answers VP8, the script proves
 *    VP8 is negotiated and a disallowed codec becomes `m=video 0`.
 *  - Engine: CallEngine video seam + pure policy, LinphoneCallEngine VP8
 *    enablement, bitrate cap, RecvOnly camera-off, background release, camera
 *    flip, native surface wiring, derived video state.
 *  - UI: camera permission gate with audio fallback, chat video button, call
 *    screen surfaces/PiP/controls/keepScreenOn, notification wording.
 *  - Tests: pure video policy + Robolectric camera gate.
 *  - Docs: ADR-0013, ADR-0006 amendment, protocol, operations, changelog,
 *    feature-plan, screenshots, session verification hook.
 *
 * The live media path is proved by scripts/test-prototype.sh (Docker); this
 * script verifies the contract is wired correctly in the source.
 */
import { existsSync, readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';

const root = process.cwd();

function read(relative) {
    try {
        return readFileSync(join(root, relative), 'utf8');
    } catch {
        return '';
    }
}

/** Every Kotlin source under a directory, concatenated. */
function kotlinSources(relative) {
    let entries;
    try {
        entries = readdirSync(join(root, relative), { withFileTypes: true });
    } catch {
        return '';
    }
    return entries
        .map((entry) => (entry.isDirectory()
            ? kotlinSources(`${relative}/${entry.name}`)
            : entry.name.endsWith('.kt') ? read(`${relative}/${entry.name}`) : ''))
        .join('\n');
}

/** The body of a Kotlin member, up to the next member declaration. */
function memberBody(source, signature) {
    const start = source.indexOf(signature);
    if (start < 0) return '';
    const end = source.indexOf('\n    fun ', start + signature.length);
    const endAlt = source.indexOf('\n    private fun ', start + signature.length);
    const endOverride = source.indexOf('\n    override fun ', start + signature.length);
    return source.slice(start, Math.min(
        end < 0 ? source.length : end,
        endAlt < 0 ? source.length : endAlt,
        endOverride < 0 ? source.length : endOverride,
    ));
}

const server = 'server/api/src/main/kotlin/com/patbaumgartner/stringtelephone/';
const app    = 'app/src/main/kotlin/com/patbaumgartner/android/stringtelephone/';
const core   = 'core/src/main/kotlin/com/patbaumgartner/android/stringtelephone/core/';

const asterisk  = read(server + 'AsteriskConfig.kt');
const admin     = read(server + 'Admin.kt');
const template  = read('server/api/src/main/resources/templates/admin.html');
const pbxTest   = read('server/api/src/test/kotlin/com/patbaumgartner/stringtelephone/PbxGatewayTest.kt');
const managed   = read('scripts/test-managed-admin.mjs');
const adminUi   = read('scripts/test-admin-ui.mjs');

const caller     = read('integration-tests/sip/caller.cjs');
const callee     = read('integration-tests/sip/callee.cjs');
const prototype  = read('scripts/test-prototype.sh');

const engine     = read(app + 'call/CallEngine.kt');
const linphone   = read(app + 'call/LinphoneCallEngine.kt');
const cameraGate = read(app + 'ui/CameraPermission.kt');
const appRoot    = read(app + 'ui/AppRoot.kt');
const chatScreen = read(app + 'ui/ChatScreen.kt');
const callScreen = read(app + 'ui/CallScreen.kt');
const vm         = read(app + 'ui/AppViewModel.kt');
const activity   = read(app + 'ui/MainActivity.kt');
const manifest   = read('app/src/main/AndroidManifest.xml');
const session    = read(app + 'session/SessionController.kt');
const notify     = read(app + 'notify/AppNotifications.kt');
const strings    = read('app/src/main/res/values/strings.xml');
const icons      = read(app + 'ui/StringTelephoneIcons.kt');

const policyTest = read('app/src/test/kotlin/com/patbaumgartner/android/stringtelephone/call/VideoCallPolicyTest.kt');
const cameraTest = read('app/src/test/kotlin/com/patbaumgartner/android/stringtelephone/ui/CameraPermissionTest.kt');
const fakeEngine = read('app/src/test/kotlin/com/patbaumgartner/android/stringtelephone/ui/FakeCallEngine.kt');
const shotActivity = read('app/src/test/kotlin/com/patbaumgartner/android/stringtelephone/ui/ScreenshotActivity.kt');
const shotTest   = read('app/src/test/kotlin/com/patbaumgartner/android/stringtelephone/ui/ScreenshotTest.kt');

const adr        = read('docs/adr/0013-video-calls.md');
const background = read('docs/adr/0006-background-call-policy.md');
const protocol   = read('docs/protocol.md');
const operations = read('docs/operations.md');
const plan       = read('docs/feature-plan.md');
const changelog  = read('CHANGELOG.md');
const sessionVerify = read('scripts/run-session-verify.sh');
const adrCheck   = read('scripts/check-adrs.mjs');

const fails = [];

function check(name, condition, hint) {
    if (!condition) fails.push(`${name}${hint ? ' — ' + hint : ''}`);
}

// ── Server: one admin setting, audio order untouched ─────────────────────────

check('PbxSettings — video setting defaults to vp8', asterisk.includes('val video: String = "vp8"'));
check('PbxSettings — only vp8/none allowed', asterisk.includes('require(video in setOf("vp8", "none"))'));
check('PbxSettings — validation explains the choice', asterisk.includes('Video codec must be vp8 or none'));
check('Endpoints — video trails the audio order', asterisk.includes('allow=${settings.codecs}${if (settings.video == "none") "" else ",${settings.video}"}'));
check('Admin — settings form accepts video', admin.includes('@RequestParam(defaultValue = "vp8") video: String'));
check('Admin — settings carry video into PbxSettings', admin.includes('symmetricMedia, video)'));
check('admin.html — video select bound to settings', /id="video"/.test(template) && /settings\.video/.test(template));
check('admin.html — both video options offered', template.includes('value="vp8"') && template.includes('value="none"'));
check('PbxGatewayTest — VP8 allow-list asserted', pbxTest.includes('allow=opus,ulaw,vp8'));
check('PbxGatewayTest — none removes the payload', pbxTest.includes('PbxSettings(video = "none")'));
check('PbxGatewayTest — unknown codecs rejected', pbxTest.includes('PbxSettings(video = "h264").validate()'));
check('Managed admin helper — sends video', managed.includes('video: "vp8"'));
check('Managed admin helper — asserts video', managed.includes('settings.video, "vp8"'));
check('Admin UI test — select options bound', adminUi.includes('id="video"') && adminUi.includes('settings\\.video'));

// ── Prototype: negotiation proof, not just codec configuration ───────────────

check('caller.cjs — VP8 and VP9 payload types', caller.includes('vp8: 96') && caller.includes('vp9: 98'));
check('caller.cjs — offers m=video with rtpmap', caller.includes('m=video 49172 RTP/AVP') && caller.includes('/90000'));
check('caller.cjs — reports negotiated codec', caller.includes('VIDEO-NEGOTIATED'));
check('caller.cjs — reports rejected video', caller.includes('VIDEO-REJECTED'));
check('callee.cjs — answers VP8 when offered', callee.includes("startsWith('m=video ')") && callee.includes('VP8/90000'));
check('prototype — G3b video step exists', prototype.includes('run_video_call') && prototype.includes('G3b-VIDEO-NEGOTIATION-COMPLETE'));
check('prototype — VP8 accepted case', prototype.includes('run_video_call VP8 vp8 VIDEO-NEGOTIATED'));
check('prototype — disallowed codec refused case', prototype.includes('run_video_call VP9 vp9 VIDEO-REJECTED'));

// ── Engine seam and pure policy ──────────────────────────────────────────────

check('CallEngine — CameraFacing enum', engine.includes('enum class CameraFacing { FRONT, BACK }'));
check('CallEngine — session carries video state', engine.includes('val videoOffered: Boolean = false') && engine.includes('val videoEnabled: Boolean = false') && engine.includes('val videoActive: Boolean = false'));
check('CallEngine — session carries camera facing', engine.includes('val cameraFacing: CameraFacing = CameraFacing.FRONT'));
check('CallEngine — startWithVideo policy', engine.includes('internal fun startWithVideo(requested: Boolean, cameraAllowed: Boolean)'));
check('CallEngine — background removes video', engine.includes('internal fun afterCallScreenHidden(info: CallSessionInfo)'));
check('CallEngine — camera cycle policy', engine.includes('internal fun nextCameraFacing('));
check('CallEngine — video defaults to speaker', engine.includes('internal fun defaultAudioRoute(') && engine.includes('withVideo && AudioRoute.SPEAKER in available'));
check('CallEngine — invite/accept carry video', engine.includes('fun call(remoteUser: String, withVideo: Boolean = false)') && engine.includes('fun accept(withVideo: Boolean = false)'));
check('CallEngine — camera toggle/flip/release/surfaces', engine.includes('fun setVideoEnabled(enabled: Boolean)') && engine.includes('fun releaseVideo()') && engine.includes('fun switchCamera()') && engine.includes('fun attachVideoSurfaces(remote: Any?, preview: Any?)'));
check('CallEvent — incoming call announces video', engine.includes('data class IncomingCall(val remoteUser: String, val withVideo: Boolean = false)'));

// ── liblinphone implementation ───────────────────────────────────────────────

check('Engine — VP8 is the only enabled payload', linphone.includes('payload.enable(vp8)') && linphone.includes('mimeType.equals("VP8", ignoreCase = true)'));
check('Engine — video bitrate is capped', linphone.includes('normalBitrate = VIDEO_BITRATE_BPS'));
check('Engine — video configured before start', /configureVideo\(core\)[\s\S]{0,200}core\.start\(\)/.test(linphone));
check('Engine — capture/preview/display toggles', linphone.includes('core.isVideoCaptureEnabled') && linphone.includes('core.isVideoPreviewEnabled') && linphone.includes('core.isVideoDisplayEnabled'));
check('Engine — invite negotiates video', linphone.includes('params.isVideoEnabled = video') && linphone.includes('params.videoDirection = MediaDirection.SendRecv'));
check('Engine — permission rechecked at call time', linphone.includes('startWithVideo(') && linphone.includes('Manifest.permission.CAMERA'));
check('Engine — camera off uses RecvOnly', linphone.includes('MediaDirection.RecvOnly'));
check('Engine — re-INVITE through call.update', linphone.includes('call.update(params)'));
check('Engine — background disables the stream', linphone.includes('params.isVideoEnabled = false'));
check('Engine — first frame requested', linphone.includes('requestNotifyNextVideoFrameDecoded()'));
check('Engine — video state derived from params', linphone.includes('usedVideoPayloadType') && linphone.includes('videoDirection'));
check('Engine — remote and preview surfaces', linphone.includes('setNativeVideoWindowId') && linphone.includes('setNativePreviewWindowId'));
check('Engine — camera flip through devices', linphone.includes('videoDevicesList') && linphone.includes('setVideoDevice'));
check('Engine — video route default', linphone.includes('defaultAudioRoute(availableRoutes(core), video)'));
check('Engine — video reset on end/stop', linphone.includes('isVideoCaptureEnabled = false') && linphone.includes('videoActive = false'));

// ── UI wiring ────────────────────────────────────────────────────────────────

check('CameraPermission — dedicated gate', cameraGate.includes('rememberCameraPermission') && cameraGate.includes('Manifest.permission.CAMERA'));
check('AppRoot — video start asks for the camera', appRoot.includes('rememberCameraPermission()') && appRoot.includes('viewModel.startCall(peer, withVideo = true)'));
check('AppRoot — denied camera falls back to audio', appRoot.includes('cameraDenied = true; viewModel.startCall(peer)') && appRoot.includes('cameraDenied = true; viewModel.acceptCall()'));
check('AppRoot — camera denial explains recovery', appRoot.includes('R.string.camera_permission_title') && appRoot.includes('R.string.camera_permission_message'));
check('ChatScreen — video-call header action', chatScreen.includes('onVideoCall') && chatScreen.includes('StringTelephoneIcons.Videocam'));
check('ChatScreen — accessible video-call label', chatScreen.includes('R.string.cd_video_call'));
check('CallScreen — remote surface', callScreen.includes('RemoteVideo(') && callScreen.includes('SurfaceView'));
check('CallScreen — waiting placeholder', callScreen.includes('R.string.call_video_waiting'));
check('CallScreen — draggable self-view', callScreen.includes('private fun SelfView(') && callScreen.includes('detectDragGestures'));
check('CallScreen — camera and flip controls', callScreen.includes('onToggleVideo') && callScreen.includes('viewModel::switchCamera') && callScreen.includes('StringTelephoneIcons.VideocamOff'));
check('CallScreen — surfaces attached and cleared', callScreen.includes('viewModel.attachVideoSurfaces') && callScreen.includes('attachVideoSurfaces(null, null)'));
check('CallScreen — screen stays awake for video', callScreen.includes('keepScreenOn = showVideo'));
check('AppViewModel — video actions', vm.includes('fun setVideoEnabled(enabled: Boolean)') && vm.includes('fun switchCamera()') && vm.includes('fun onCallScreenHidden()') && vm.includes('fun attachVideoSurfaces('));
check('MainActivity — background drops video', /override fun onStop\(\)\s*\{[^}]*onCallScreenHidden\(\)/.test(activity));
check('Manifest — CAMERA permission declared', manifest.includes('android.permission.CAMERA'));
check('Manifest — camera optional (no dead installs)', manifest.includes('android.hardware.camera') && manifest.includes('android:required="false"'));
check('Manifest — no camera foreground-service type', !manifest.includes('FOREGROUND_SERVICE_CAMERA') && !manifest.includes('android:foregroundServiceType="camera"'));
check('SessionController — incoming video flag forwarded', session.includes('event.remoteUser, event.withVideo') && session.includes('showIncomingCall(call.remoteUser, call.videoOffered)'));
check('Notifications — video wording and icon', notify.includes('notification_incoming_call_video_title') && notify.includes('R.drawable.ic_videocam'));
check('Icons — video/selfie vectors wired', icons.includes('R.drawable.ic_videocam') && icons.includes('R.drawable.ic_videocam_off') && icons.includes('R.drawable.ic_camera_flip'));

// No dead video resources: every new video/camera string is referenced.
const videoStrings = [...strings.matchAll(/<string name="((?:cd_)?(?:camera|video_call|call_video|notification_incoming_call_video)[a-z_]*)"/g)]
    .map((match) => match[1]);
const appSources = kotlinSources('app/src/main');
const unusedVideoStrings = videoStrings.filter((name) => !appSources.includes(`R.string.${name}`));
check('strings.xml — video/camera resources exist', videoStrings.length >= 8, videoStrings.join(', '));
check('strings.xml — every video/camera resource is used', unusedVideoStrings.length === 0, unusedVideoStrings.join(', '));

// ── Tests and screenshot fixtures ────────────────────────────────────────────

check('VideoCallPolicyTest — start/permission policy', policyTest.includes('`video starts only when the caller asked and the camera is allowed`'));
check('VideoCallPolicyTest — background policy', policyTest.includes('`leaving the call screen removes every video flag and keeps the call state`'));
check('VideoCallPolicyTest — camera cycle', policyTest.includes('`camera flip cycles through the available devices and wraps`'));
check('VideoCallPolicyTest — route policy', policyTest.includes('`video calls prefer speaker and audio calls stay on the earpiece`'));
check('CameraPermissionTest — granted path', cameraTest.includes('`already granted camera permission starts video immediately`'));
check('CameraPermissionTest — grant after request', cameraTest.includes('`first video call waits for the camera permission and continues once on grant`'));
check('CameraPermissionTest — denial falls back', cameraTest.includes('`denied camera permission falls back to audio exactly once`'));
check('FakeCallEngine — implements video seam', fakeEngine.includes('override fun call(remoteUser: String, withVideo: Boolean)') && fakeEngine.includes('override fun releaseVideo()') && fakeEngine.includes('override fun attachVideoSurfaces('));
check('ScreenshotActivity — video route fixture', shotActivity.includes('ScreenshotRoute.CALL_VIDEO') && shotActivity.includes('videoActive = false'));
check('ScreenshotTest — video capture', shotTest.includes('capture("16-call-video", ScreenshotRoute.CALL_VIDEO)'));

// ── Docs ─────────────────────────────────────────────────────────────────────

check('ADR-0013 — exists with Context and Decision', adr.includes('## Context') && adr.includes('## Decision'));
check('ADR-0013 — VP8 only', adr.includes('VP8 only'));
check('ADR-0013 — foreground camera only', adr.includes('Camera only while the call screen is visible'));
check('ADR-0013 — RecvOnly camera off', adr.includes('RecvOnly'));
check('ADR-0006 — amendment references ADR-0013', background.includes('ADR-0013'));
check('ADR check — video decision area guarded', adrCheck.includes('video-calls'));
check('docs/protocol.md — calls section', protocol.includes('## Calls (SIP media)') && protocol.includes('VP8'));
check('docs/protocol.md — admin video field', protocol.includes('"video":"vp8"'));
check('docs/operations.md — video sizing', operations.includes('### Video calls') && operations.includes('25 concurrent video calls'));
check('docs/operations.md — disable switch documented', operations.includes('video = none'));
check('docs/feature-plan.md — Phase 12 done', plan.includes('### Phase 12 — Video calls') && plan.includes('**Status:** Done (2026-10-27)'));
check('CHANGELOG.md — Phase 12 entry', changelog.includes('video (Phase 12'));
check('Screenshots — video call captured', existsSync(join(root, 'docs/screenshots/16-call-video.png')) && existsSync(join(root, 'docs/screenshots/dark-call-video.png')));
check('run-session-verify.sh — oracle appended', sessionVerify.includes('verify-video-calls.mjs'));

// ── Result ───────────────────────────────────────────────────────────────────

if (fails.length > 0) {
    for (const failure of fails) console.error('FAIL:', failure);
    process.exit(1);
}
console.log('video call verification passed');
