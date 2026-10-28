#!/usr/bin/env node
/**
 * Quick-reply-from-notification verification (Phase 14): an inline RemoteInput
 * reply and a "Mark as read" action on message notifications, both handled
 * without opening the activity.
 * Exits nonzero with a reason per failure; prints "quick reply verification
 * passed" only after every assertion passes.
 *
 * Checks:
 *  - App: MessageActionReceiver extracts the RemoteInput and sends through
 *    the repository's normal send path (client key, optimistic append,
 *    delivery state), posts a follow-up notification when the send fails,
 *    marks the conversation read from the shade, and never starts an
 *    activity.
 *  - Notifications: the conversation notification carries the reply action
 *    with a RemoteInput and the mark-as-read action; failure notifications
 *    reuse the message channel.
 *  - Manifest: the receiver is registered and not exported.
 *  - Strings: all new resources exist and are referenced.
 *  - Tests: Robolectric coverage for every seam (actions present, round trip,
 *    failure notification, no-activity rule, empty input).
 *  - Docs: feature-plan status, ADR-0010 amendment, changelog, session
 *    verification hook.
 *
 * The live round trip is proved by the Robolectric suite (real repository,
 * recording-free optimistic path); this script verifies the contract is
 * wired correctly in the source.
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

const app      = 'app/src/main/kotlin/com/patbaumgartner/android/stringtelephone/';
const receiver = read(app + 'notify/MessageActionReceiver.kt');
const notifier = read(app + 'notify/AppNotifications.kt');
const manifest = read('app/src/main/AndroidManifest.xml');
const strings  = read('app/src/main/res/values/strings.xml');
const repository = read(app + 'data/StringTelephoneRepository.kt');
const appSources = kotlinSources('app/src/main');

const test = read('app/src/test/kotlin/com/patbaumgartner/android/stringtelephone/notify/MessageActionReceiverTest.kt');
const brandingTest = read('app/src/test/kotlin/com/patbaumgartner/android/stringtelephone/notify/NotificationBrandingTest.kt');

const protocol  = read('docs/protocol.md');
const plan      = read('docs/feature-plan.md');
const changelog = read('CHANGELOG.md');
const adr10     = read('docs/adr/0010-message-delivery-states.md');
const agents    = read('AGENTS.md');
const sessionVerify = read('scripts/run-session-verify.sh');

const fails = [];

function check(name, condition, hint) {
    if (!condition) fails.push(`${name}${hint ? ' — ' + hint : ''}`);
}

// ── Receiver: RemoteInput round trip, no activity, failure notification ──────

check('Receiver — class exists and handles both actions', receiver.includes('class MessageActionReceiver : BroadcastReceiver()') && receiver.includes('ACTION_QUICK_REPLY') && receiver.includes('ACTION_MARK_READ'));
check('Receiver — extracts the RemoteInput result', receiver.includes('RemoteInput.getResultsFromIntent(intent)'));
check('Receiver — blank replies are ignored', receiver.includes('if (text.isNullOrEmpty()) return'));
check('Receiver — sends through the repository send path', receiver.includes('container.repository.send(peer, text)'));
check('Receiver — mark-as-read goes through markConversationRead', receiver.includes('container.repository.markConversationRead(peer, null)'));
check('Receiver — failed sends post a follow-up notification', receiver.includes('notification_send_failed_title') && receiver.includes('notify(peer.hashCode()'));
check('Receiver — failure notification opens the conversation when tapped', receiver.includes('MainActivity.ACTION_OPEN_CHAT'));
check('Receiver — RemoteInput helper is shared with the notifier', receiver.includes('fun replyRemoteInput(context: Context)') && receiver.includes('RemoteInput.Builder(KEY_REPLY_TEXT)'));
check('Receiver — pending intents are immutable and receiver-directed', receiver.includes('PendingIntent.FLAG_IMMUTABLE') && receiver.includes('MessageActionReceiver::class.java'));

// ── Notifier: actions on the conversation notification ───────────────────────

check('Notifier — reply action with RemoteInput on message notifications', notifier.includes('addRemoteInput(replyRemoteInput)') && notifier.includes('MessageActionReceiver.quickReplyIntent(context, peer)'));
check('Notifier — mark-as-read action on message notifications', notifier.includes('MessageActionReceiver.markReadIntent(context, peer)') && notifier.includes('R.string.notification_mark_read'));
check('Notifier — actions declare semantic actions and no UI', notifier.includes('SEMANTIC_ACTION_REPLY') && notifier.includes('SEMANTIC_ACTION_MARK_AS_READ') && notifier.includes('setShowsUserInterface(false)'));
check('Notifier — both actions added to the builder', notifier.includes('.addAction(quickReplyAction)') && notifier.includes('.addAction(markReadAction)'));

// ── Manifest and strings ─────────────────────────────────────────────────────

check('Manifest — MessageActionReceiver registered, not exported', manifest.includes('.notify.MessageActionReceiver') && /<receiver\s+android:name="\.notify\.MessageActionReceiver"\s+android:exported="false"/.test(manifest));
check('strings.xml — reply and mark-read labels', strings.includes('name="notification_reply"') && strings.includes('name="notification_mark_read"') && strings.includes('name="notification_reply_hint"'));
check('strings.xml — send-failure strings', strings.includes('name="notification_send_failed_title"') && strings.includes('name="notification_send_failed_text"'));

// No dead resources: every new string is referenced.
const newStrings = [...strings.matchAll(/<string name="(notification_(?:reply|mark_read|reply_hint|send_failed_title|send_failed_text))"/g)].map((match) => match[1]);
const unused = newStrings.filter((name) => !appSources.includes(`R.string.${name}`));
check('strings.xml — every Phase 14 resource is used', unused.length === 0, unused.join(', '));

// ── ADR-0010 amendment and repository docstring ──────────────────────────────

check('ADR-0010 — shade action amended as an explicit user act', adr10.includes("notification's explicit \"Mark as read\" action (Phase 14)") && adr10.includes('still never mark anything read on their own'));
check('Repository — docstring covers both legitimate callers', repository.includes("notification's \"Mark as read\" action (Phase 14)") && repository.includes('still never mark anything read on their own'));

// ── Tests ────────────────────────────────────────────────────────────────────

check('Test — actions present with RemoteInput', test.includes('`message notifications offer inline reply and mark as read`') && test.includes('reply.remoteInputs.single().resultKey'));
check('Test — quick reply round trip through the repository', test.includes('`a quick reply sends through the repository without opening the activity`') && test.includes('SendState.FAILED'));
check('Test — failure follow-up notification', test.includes('`a failed quick reply surfaces a follow-up notification`') && test.includes('notification_send_failed_title'));
check('Test — mark-as-read never opens the activity', test.includes('`mark as read never opens the activity`') && test.includes('nextStartedOrNull'));
check('Test — empty and whitespace replies ignored', test.includes('`quick reply without text does nothing`') && test.includes('`quick reply with only whitespace does not append a message`'));

// The branding test still passes with two actions now on the notification.
check('BrandingTest — still exercises the conversation notification', brandingTest.includes('AppNotifier.notifyConversation'));

// ── Docs ─────────────────────────────────────────────────────────────────────

check('feature-plan — Phase 14 done', plan.includes('### Phase 14 — Quick reply from notification') && plan.includes('**Status:** Done'));
check('CHANGELOG — Phase 14 entry', changelog.includes('quick reply') && changelog.includes('(Phase 14)'));
check('AGENTS.md — read-rule updated for the shade action', agents.includes("notification's explicit \"Mark as read\" action") || agents.includes('the notification\'s\n  explicit'));
check('run-session-verify.sh — oracle appended', sessionVerify.includes('verify-quick-reply.mjs'));

// ── Result ───────────────────────────────────────────────────────────────────

if (fails.length > 0) {
    for (const failure of fails) console.error('FAIL:', failure);
    process.exit(1);
}
console.log('quick reply verification passed');
