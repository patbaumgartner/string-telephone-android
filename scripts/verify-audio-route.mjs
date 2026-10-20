#!/usr/bin/env node
/**
 * Audio-route verification: asserts the CallEngine seam, the Linphone
 * implementation, the call UI, the view model, the screenshot fixture, and the
 * test fake all use the three-way AudioRoute (EARPIECE/SPEAKER/BLUETOOTH)
 * instead of the boolean speaker switch, and that the route-selection rules
 * are unit tested. Exits nonzero with a reason on any failure; prints
 * "audio route verification passed" only after every assertion passes.
 *
 * Authoring control: run before the Phase 2 implementation and confirm it
 * fails (the old `speakerOn` boolean is still present); absence assertions are
 * only trustworthy against that known positive control.
 */
import { readFileSync, existsSync } from 'node:fs';
import { join } from 'node:path';

const root = process.cwd();

function read(relative) {
  return readFileSync(join(root, relative), 'utf8');
}

const callEngine = read('app/src/main/kotlin/com/patbaumgartner/android/stringtelephone/call/CallEngine.kt');
const linphone = read('app/src/main/kotlin/com/patbaumgartner/android/stringtelephone/call/LinphoneCallEngine.kt');
const callScreen = read('app/src/main/kotlin/com/patbaumgartner/android/stringtelephone/ui/CallScreen.kt');
const viewModel = read('app/src/main/kotlin/com/patbaumgartner/android/stringtelephone/ui/AppViewModel.kt');
const fakeEngine = read('app/src/test/kotlin/com/patbaumgartner/android/stringtelephone/ui/FakeCallEngine.kt');
const screenshotActivity = read('app/src/test/kotlin/com/patbaumgartner/android/stringtelephone/ui/ScreenshotActivity.kt');
const routeTestPath = 'app/src/test/kotlin/com/patbaumgartner/android/stringtelephone/call/AudioRouteTest.kt';
const routeTest = existsSync(join(root, routeTestPath)) ? read(routeTestPath) : '';

const assertions = [
  // The seam: a three-value AudioRoute replaces the speakerOn boolean.
  [/enum class AudioRoute\s*\{[^}]*EARPIECE[^}]*SPEAKER[^}]*BLUETOOTH/s.test(callEngine), 'CallEngine declares AudioRoute(EARPIECE, SPEAKER, BLUETOOTH)'],
  [callEngine.includes('fun setAudioRoute(route: AudioRoute)'), 'CallEngine declares setAudioRoute'],
  [callEngine.includes('audioRoute: AudioRoute'), 'CallSessionInfo carries the current audioRoute'],
  [callEngine.includes('availableAudioRoutes'), 'CallSessionInfo exposes availableAudioRoutes'],
  [!callEngine.includes('speakerOn') && !callEngine.includes('setSpeaker'), 'CallEngine no longer declares the speakerOn boolean'],

  // The Linphone implementation routes to Bluetooth and listens for device-list changes.
  [linphone.includes('override fun setAudioRoute'), 'LinphoneCallEngine implements setAudioRoute'],
  [linphone.includes('AudioDevice.Type.Bluetooth'), 'LinphoneCallEngine maps AudioRoute.BLUETOOTH to linphone Bluetooth devices'],
  [linphone.includes('onAudioDevicesListUpdated'), 'LinphoneCallEngine handles onAudioDevicesListUpdated'],
  [!linphone.includes('setSpeaker') && !linphone.includes('speakerOn'), 'LinphoneCallEngine no longer implements setSpeaker'],

  // The UI offers the third route only when a Bluetooth output exists.
  [callScreen.includes('AudioRoute.BLUETOOTH'), 'CallScreen renders the Bluetooth route'],
  [callScreen.includes('availableAudioRoutes'), 'CallScreen gates the route list on availability'],
  [callScreen.includes('setAudioRoute('), 'CallScreen routes button taps to setAudioRoute'],
  [!callScreen.includes('speakerOn') && !callScreen.includes('setSpeaker'), 'CallScreen no longer reads the speakerOn boolean'],

  // The view model, fake, and screenshot fixture mirror the new interface.
  [viewModel.includes('fun setAudioRoute('), 'AppViewModel exposes setAudioRoute'],
  [!viewModel.includes('setSpeaker('), 'AppViewModel no longer toggles a speaker boolean'],
  [fakeEngine.includes('override fun setAudioRoute'), 'FakeCallEngine mirrors setAudioRoute'],
  [!fakeEngine.includes('setSpeaker'), 'FakeCallEngine drops setSpeaker'],
  [screenshotActivity.includes('audioRoute =') && !screenshotActivity.includes('speakerOn ='), 'Screenshot fixture uses audioRoute'],

  // Route-selection rules are unit tested.
  [/class AudioRouteTest/.test(routeTest), 'AudioRouteTest exists'],
  [(routeTest.match(/@Test/g) || []).length >= 6, 'AudioRouteTest covers at least six cases'],
];

let failures = 0;
for (const [condition, label] of assertions) {
  if (!condition) {
    console.error(`FAIL: ${label}`);
    failures += 1;
  }
}

if (failures > 0) {
  console.error(`audio route verification failed (${failures} assertion(s))`);
  process.exit(1);
}
console.log('audio route verification passed');
