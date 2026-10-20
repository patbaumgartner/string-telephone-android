#!/usr/bin/env node
/**
 * Call UI route verification: asserts the call screen renders one button per
 * available AudioRoute, that Bluetooth visibility is driven by route
 * availability, that taps reach AppViewModel.setAudioRoute, and that the
 * supporting resources (Bluetooth icon, string) and screenshot fixture exist.
 * Exits nonzero with a reason on any failure; prints "call ui route
 * verification passed" only after every assertion passes.
 *
 * Negative control: run from a worktree of the phase-1 commit (no AudioRoute
 * work) and confirm it fails before trusting its absence assertions.
 */
import { readFileSync, existsSync } from 'node:fs';
import { join } from 'node:path';

const root = process.cwd();

function read(relative) {
  return readFileSync(join(root, relative), 'utf8');
}

const callScreen = read('app/src/main/kotlin/com/patbaumgartner/android/stringtelephone/ui/CallScreen.kt');
const viewModel = read('app/src/main/kotlin/com/patbaumgartner/android/stringtelephone/ui/AppViewModel.kt');
const icons = read('app/src/main/kotlin/com/patbaumgartner/android/stringtelephone/ui/StringTelephoneIcons.kt');
const strings = read('app/src/main/res/values/strings.xml');
const screenshotActivity = read('app/src/test/kotlin/com/patbaumgartner/android/stringtelephone/ui/ScreenshotActivity.kt');
const bluetoothIconPath = 'app/src/main/res/drawable/ic_bluetooth.xml';
const bluetoothIcon = existsSync(join(root, bluetoothIconPath)) ? read(bluetoothIconPath) : '';

const assertions = [
  // One button per available route, in fixed order, gated on availability.
  [
    callScreen.includes('AudioRoute.entries.filter { it in call.availableAudioRoutes }'),
    'call screen renders only the available routes',
  ],
  [callScreen.includes('viewModel.setAudioRoute(route)'), 'route taps reach the view model'],
  [callScreen.includes('routeSelected = call.audioRoute == route'), 'the current route renders as selected'],
  [
    callScreen.includes('AudioRoute.BLUETOOTH -> StringTelephoneIcons.Bluetooth'),
    'the Bluetooth route draws the Bluetooth icon',
  ],
  [
    callScreen.includes('AudioRoute.EARPIECE -> StringTelephoneIcons.Call'),
    'the earpiece route draws the handset icon',
  ],
  [
    callScreen.includes('AudioRoute.BLUETOOTH -> R.string.call_bluetooth'),
    'the Bluetooth button announces a Bluetooth content description',
  ],
  [callScreen.includes('selected = routeSelected'), 'route buttons expose selected semantics'],
  [!callScreen.includes('toggleSpeaker') && !callScreen.includes('speakerOn'), 'call screen drops the legacy speaker toggle'],

  // The view model exposes the route action the screen calls.
  [viewModel.includes('fun setAudioRoute(route: AudioRoute)'), 'AppViewModel exposes setAudioRoute'],
  [!viewModel.includes('toggleSpeaker'), 'AppViewModel drops toggleSpeaker'],

  // Supporting resources exist.
  [/val Bluetooth: ImageVector/.test(icons), 'Bluetooth icon is wired into StringTelephoneIcons'],
  [strings.includes('<string name="call_bluetooth">Bluetooth</string>'), 'Bluetooth content description string exists'],
  [/pathData="[^"]+/.test(bluetoothIcon), 'ic_bluetooth drawable carries a path'],

  // The screenshot fixture advertises Bluetooth so the third button is reviewable.
  [
    screenshotActivity.includes('AudioRoute.BLUETOOTH'),
    'screenshot fixture lists Bluetooth among available routes',
  ],
];

let failures = 0;
for (const [condition, label] of assertions) {
  if (!condition) {
    console.error(`FAIL: ${label}`);
    failures += 1;
  }
}

if (failures > 0) {
  console.error(`call ui route verification failed (${failures} assertion(s))`);
  process.exit(1);
}
console.log('call ui route verification passed');
