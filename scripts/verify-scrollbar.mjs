#!/usr/bin/env node
/**
 * Scrollbar verification: asserts the chat screen composes the scrollbar with
 * the real list state and that the geometry function exists with the
 * documented contract. Exits nonzero with a reason on any failure; prints
 * "scrollbar verification passed" only after every assertion passes.
 */
import { readFileSync } from 'node:fs';
import { join } from 'node:path';

const root = process.cwd();

function read(relative) {
  return readFileSync(join(root, relative), 'utf8');
}

const uiCommon = read('app/src/main/kotlin/com/patbaumgartner/android/stringtelephone/ui/UiCommon.kt');
const chatScreen = read('app/src/main/kotlin/com/patbaumgartner/android/stringtelephone/ui/ChatScreen.kt');
const metricsTest = read('app/src/test/kotlin/com/patbaumgartner/android/stringtelephone/ui/ScrollbarMetricsTest.kt');
const screenshotTest = read('app/src/test/kotlin/com/patbaumgartner/android/stringtelephone/ui/ScreenshotTest.kt');

const assertions = [
  // The geometry function exists, is pure, and derives from layout info inputs.
  [uiCommon.includes('internal fun scrollbarMetrics('), 'scrollbarMetrics exists'],
  [uiCommon.includes('totalItems: Int'), 'metrics take totalItems'],
  [uiCommon.includes('firstVisibleScrolledPx'), 'metrics read the scroll magnitude'],
  [uiCommon.includes('firstVisibleScrolledPx = -first.offset'), 'composable negates the topmost item offset'],
  [uiCommon.includes('canScrollForward'), 'metrics honour the scroll end'],
  [uiCommon.includes('fillsViewport = thumbHeight >= viewportHeightPx'), 'metrics expose fills-viewport'],

  // The composable is driven by LazyListState and draws from layout info.
  [/internal fun ChatScrollbar\(\s*listState: LazyListState/.test(uiCommon), 'ChatScrollbar takes the list state'],
  [uiCommon.includes('listState.layoutInfo'), 'composable reads layout info'],
  [uiCommon.includes('SCROLLBAR_MIN_ITEMS'), 'minimum item threshold applied'],
  [uiCommon.includes('drawRoundRect'), 'thumb is drawn as a rounded rect'],

  // The chat screen composes the scrollbar over its list.
  [chatScreen.includes('ChatScrollbar('), 'chat screen composes ChatScrollbar'],
  [chatScreen.includes('Alignment.CenterEnd'), 'thumb aligned to the trailing edge'],
  [uiCommon.includes('fillMaxHeight()') && uiCommon.includes('width(20.dp)'), 'thumb owns its viewport-spanning track size'],

  // The metrics rules are unit tested.
  [/class ScrollbarMetricsTest/.test(metricsTest), 'metrics test class exists'],
  [/\bfun `/m.test(metricsTest), 'metrics tests use backtick case names'],
  [(metricsTest.match(/@Test/g) || []).length >= 8, 'at least eight geometry assertions covered'],

  // The screenshot suite still renders the chat so the thumb is reviewable.
  [screenshotTest.includes('ScreenshotRoute.CHAT'), 'screenshot suite renders the chat screen'],
];

let failures = 0;
for (const [condition, label] of assertions) {
  if (!condition) {
    console.error(`FAIL: ${label}`);
    failures += 1;
  }
}

if (failures > 0) {
  console.error(`scrollbar verification failed (${failures} assertion(s))`);
  process.exit(1);
}
console.log('scrollbar verification passed');
