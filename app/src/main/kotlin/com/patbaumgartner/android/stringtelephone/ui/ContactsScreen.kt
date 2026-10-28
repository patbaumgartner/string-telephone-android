package com.patbaumgartner.android.stringtelephone.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Badge
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.patbaumgartner.android.stringtelephone.R
import com.patbaumgartner.android.stringtelephone.core.PresenceStatus
import com.patbaumgartner.android.stringtelephone.notify.AppNotifier
import com.patbaumgartner.android.stringtelephone.notify.IncomingCallAccess
import com.patbaumgartner.android.stringtelephone.ui.theme.StringTelephoneTheme

@Composable
internal fun ContactsScreen(viewModel: AppViewModel, onCall: (String) -> Unit = viewModel::startCall) {
    val contactsState by viewModel.contactsState().collectAsState()
    val account by viewModel.accountState().collectAsState()
    val unread by viewModel.unreadState().collectAsState()
    val previews by viewModel.previewsState().collectAsState()
    var menuExpanded by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    val username = account.me?.username.orEmpty()
    val headerName = account.me?.displayName?.takeIf { it.isNotBlank() }
        ?: username.ifBlank { stringResource(R.string.app_name) }
    val visibleContacts = remember(contactsState.contacts, searchQuery) {
        viewModel.filterContacts(contactsState.contacts, searchQuery)
    }

    Column(
        modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    StringTelephoneIcons.Call,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = StringTelephoneTheme.accents.brand,
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    headerName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(
                        StringTelephoneIcons.MoreHoriz,
                        contentDescription = stringResource(R.string.menu_more),
                    )
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.menu_settings)) },
                        leadingIcon = { Icon(StringTelephoneIcons.Settings, contentDescription = null) },
                        onClick = { menuExpanded = false; viewModel.openSettings() },
                    )
                }
            }
        }

        Text(
            stringResource(R.string.contacts_title),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
        )
        ContactSearchField(
            query = searchQuery,
            onQueryChange = { searchQuery = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp),
        )
        if (viewModel.callRegistrationFailed) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
                ErrorNotice(stringResource(R.string.call_registration_failed))
                TextButton(onClick = viewModel::retryCallRegistration) {
                    Text(stringResource(R.string.retry))
                }
            }
        }
        IncomingCallAccessNotice(viewModel.incomingCallAccess)
        if (contactsState.error != null) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
                ErrorNotice(stringResource(R.string.contacts_load_failed))
                TextButton(onClick = viewModel::refreshContactsSilently) {
                    Text(stringResource(R.string.retry))
                }
            }
        }
        if (contactsState.loading && contactsState.contacts.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.contacts_loading), color = StringTelephoneTheme.accents.textMuted)
            }
        } else if (contactsState.contacts.isEmpty() && contactsState.error == null) {
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth().padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        stringResource(R.string.contacts_empty),
                        style = MaterialTheme.typography.headlineSmall,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        stringResource(R.string.contacts_empty_hint),
                        color = StringTelephoneTheme.accents.textMuted,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 24.dp),
            ) {
                items(visibleContacts, key = { it.username }) { contact ->
                    ConversationRow(
                        contact = contact,
                        presence = presenceStatusOf(contact.presence),
                        preview = previews[contact.username],
                        unreadCount = unread[contact.username] ?: 0,
                        onCall = { onCall(contact.username) },
                        onChat = { viewModel.openChat(contact.username) },
                    )
                }
            }
        }
    }
}

@Composable
private fun IncomingCallAccessNotice(access: IncomingCallAccess) {
    val message = when (access) {
        IncomingCallAccess.READY -> return
        IncomingCallAccess.NOTIFICATIONS_OFF -> R.string.incoming_call_access_notifications
        IncomingCallAccess.FULL_SCREEN_OFF -> R.string.incoming_call_access_full_screen
    }
    val context = LocalContext.current
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp)) {
        InfoNotice(stringResource(message))
        TextButton(onClick = {
            AppNotifier.incomingCallSettingsIntent(context, access)?.let { intent ->
                runCatching { context.startActivity(intent) }
            }
        }) {
            Text(stringResource(R.string.incoming_call_access_allow))
        }
    }
}

@Composable
private fun ContactSearchField(query: String, onQueryChange: (String) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        placeholder = { Text(stringResource(R.string.contacts_search_hint)) },
        leadingIcon = { Icon(StringTelephoneIcons.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(StringTelephoneIcons.Close, contentDescription = stringResource(R.string.cd_clear_search))
                }
            }
        },
        shape = RoundedCornerShape(24.dp),
        modifier = modifier,
    )
}

/**
 * A conversation-list row (Phase 15): name, unread badge, and either the
 * last-message preview with its relative timestamp or the presence subtitle.
 */
@Composable
private fun ConversationRow(
    contact: com.patbaumgartner.android.stringtelephone.core.ContactDto,
    presence: PresenceStatus,
    preview: com.patbaumgartner.android.stringtelephone.chat.ChatMessage?,
    unreadCount: Int,
    onCall: () -> Unit,
    onChat: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    contact.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (unreadCount > 0) FontWeight.Bold else null,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (unreadCount > 0) {
                    Spacer(Modifier.width(8.dp))
                    UnreadBadge(unreadCount)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (preview == null) {
                    PresenceDot(presence)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        presenceSubtitle(contact),
                        color = StringTelephoneTheme.accents.textMuted,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    Text(
                        previewSubtitle(preview),
                        color = StringTelephoneTheme.accents.textMuted,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        previewTimestamp(preview.sentAtEpochMs),
                        color = StringTelephoneTheme.accents.textMuted,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        CircleAction(
            onClick = onChat,
            icon = StringTelephoneIcons.Message,
            contentDescription = stringResource(R.string.cd_open_chat),
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(8.dp))
        CircleAction(
            onClick = onCall,
            icon = StringTelephoneIcons.Call,
            contentDescription = stringResource(R.string.cd_call),
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        )
    }
}

/**
 * The preview line under the name (Phase 15): tombstoned messages preview as
 * "message deleted", media as a placeholder, replies as their own text —
 * ADR-0011's preview rules.
 */
@Composable
internal fun previewSubtitle(message: com.patbaumgartner.android.stringtelephone.chat.ChatMessage): String {
    if (message.deleted) return stringResource(R.string.preview_deleted)
    return when (message.type) {
        "IMAGE" -> stringResource(R.string.preview_image)
        "VOICE" -> stringResource(R.string.preview_voice)
        else -> message.content
    }
}

/**
 * Relative timestamp for a conversation row (Phase 15): today shows the
 * clock time, the last week the weekday, anything older the date — the same
 * buckets as the chat's day chips.
 */
@Composable
internal fun previewTimestamp(epochMs: Long): String {
    val zone = java.time.ZoneId.systemDefault()
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    val zoned = java.time.Instant.ofEpochMilli(epochMs).atZone(zone)
    return when (previewTimeBucket(epochMs, System.currentTimeMillis(), zone)) {
        PreviewTimeLabel.TODAY -> zoned.format(
            java.time.format.DateTimeFormatter.ofLocalizedTime(java.time.format.FormatStyle.SHORT).withLocale(locale),
        )
        PreviewTimeLabel.YESTERDAY -> stringResource(R.string.chat_yesterday)
        PreviewTimeLabel.WEEKDAY -> zoned.toLocalDate().dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, locale)
        PreviewTimeLabel.DATE -> zoned.toLocalDate().format(
            java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM).withLocale(locale),
        )
    }
}

@Composable
private fun UnreadBadge(count: Int) {
    val description = pluralStringResource(R.plurals.cd_unread_messages, count, count)
    Badge(
        containerColor = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        modifier = Modifier.clearAndSetSemantics { contentDescription = description },
    ) {
        Text(if (count > MAX_BADGE_COUNT) "$MAX_BADGE_COUNT+" else count.toString())
    }
}

private const val MAX_BADGE_COUNT = 99

internal fun presenceStatusOf(presence: String?): PresenceStatus = when (presence) {
    "online" -> PresenceStatus.ONLINE
    "offline" -> PresenceStatus.OFFLINE
    else -> PresenceStatus.UNKNOWN
}

@Composable
internal fun presenceLabel(status: PresenceStatus): String = stringResource(
    when (status) {
        PresenceStatus.ONLINE -> R.string.presence_online
        PresenceStatus.OFFLINE -> R.string.presence_offline
        PresenceStatus.UNKNOWN -> R.string.presence_unknown
    },
)

/**
 * The contact-row subtitle: online and unknown keep the plain presence
 * label; offline contacts show when they were last reachable (Phase 13).
 */
@Composable
internal fun presenceSubtitle(contact: com.patbaumgartner.android.stringtelephone.core.ContactDto): String {
    val status = presenceStatusOf(contact.presence)
    val presence = presenceLabel(status)
    if (status != PresenceStatus.OFFLINE || contact.lastSeenEpochMs == null) {
        return stringResource(R.string.contact_subtitle, contact.username, presence)
    }
    val zone = java.time.ZoneId.systemDefault()
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    val lastSeen = contact.lastSeenEpochMs ?: return stringResource(R.string.contact_subtitle, contact.username, presence)
    val seen = java.time.Instant.ofEpochMilli(lastSeen).atZone(zone)
    val label = when (lastSeenBucket(lastSeen, System.currentTimeMillis(), zone)) {
        LastSeenLabel.TODAY -> stringResource(
            R.string.last_seen_today,
            seen.format(java.time.format.DateTimeFormatter.ofLocalizedTime(java.time.format.FormatStyle.SHORT).withLocale(locale)),
        )
        LastSeenLabel.YESTERDAY -> stringResource(R.string.last_seen_yesterday)
        LastSeenLabel.WEEKDAY -> stringResource(
            R.string.last_seen_weekday,
            seen.toLocalDate().dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, locale),
        )
        LastSeenLabel.DATE -> stringResource(
            R.string.last_seen_date,
            seen.toLocalDate().format(java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM).withLocale(locale)),
        )
    }
    return stringResource(R.string.contact_subtitle, contact.username, label)
}

/** Decorative: the presence text next to it carries the meaning. */
@Composable
internal fun PresenceDot(status: PresenceStatus) {
    val color = when (status) {
        PresenceStatus.ONLINE -> StringTelephoneTheme.accents.presence
        PresenceStatus.OFFLINE -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
        PresenceStatus.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f)
    }
    Surface(
        color = color,
        shape = CircleShape,
        modifier = Modifier.size(10.dp),
    ) {}
}
