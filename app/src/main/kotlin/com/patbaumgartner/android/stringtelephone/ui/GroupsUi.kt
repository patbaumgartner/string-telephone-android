package com.patbaumgartner.android.stringtelephone.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.patbaumgartner.android.stringtelephone.R
import com.patbaumgartner.android.stringtelephone.core.ContactDto
import com.patbaumgartner.android.stringtelephone.core.GroupDto
import com.patbaumgartner.android.stringtelephone.core.GroupMemberDto
import com.patbaumgartner.android.stringtelephone.ui.theme.StringTelephoneTheme
import java.time.ZoneId
import com.patbaumgartner.android.stringtelephone.chat.ChatMessage
import androidx.compose.foundation.lazy.rememberLazyListState

/**
 * Group chats UI (Phase 19, ADR-0012): the group list embedded in the
 * contacts screen, the group detail (members, admin actions) and the create
 * dialog. The group chat itself reuses [ChatScreen] through the group
 * branches in [AppRoot]; only the header and receipts differ here.
 */

/**
 * The group list screen (Phase 19): every group with name, member count and
 * unread badge; the toolbar action creates a group. Reuses the pairwise
 * unread map keyed by group id.
 */
@Composable
internal fun GroupsScreen(viewModel: AppViewModel) {
    val groups by viewModel.groupsState().collectAsState()
    val unread by viewModel.unreadState().collectAsState()
    val contactsState by viewModel.contactsState().collectAsState()
    var showCreate by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { viewModel.refreshGroupsSilently() }

    Column(
        modifier = Modifier.fillMaxSize().windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.safeDrawing),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = viewModel::backToContacts) {
                Icon(StringTelephoneIcons.ArrowBack, contentDescription = stringResource(R.string.cd_back))
            }
            Text(
                stringResource(R.string.groups_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { showCreate = true }) {
                Icon(
                    StringTelephoneIcons.Message,
                    contentDescription = stringResource(R.string.groups_create_cd),
                    tint = StringTelephoneTheme.accents.brand,
                )
            }
        }
        if (groups.isEmpty()) {
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth().padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.groups_empty), style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.size(10.dp))
                    Text(
                        stringResource(R.string.groups_empty_hint),
                        color = StringTelephoneTheme.accents.textMuted,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 24.dp),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                items(groups, key = { it.id }) { group ->
                    GroupRow(
                        group = group,
                        unreadCount = unread[group.id] ?: 0,
                        onOpen = { viewModel.openGroupChat(group.id) },
                    )
                }
            }
        }
    }
    if (showCreate) {
        CreateGroupDialog(
            contacts = contactsState.contacts,
            onCreate = { name, members -> viewModel.createGroup(name, members) { created -> created?.let { viewModel.openGroupChat(it.id) } } },
            onDismiss = { showCreate = false },
        )
    }
}

/** A group list row: name, member count and unread badge (Phase 19). */
@Composable
internal fun GroupRow(
    group: GroupDto,
    unreadCount: Int,
    onOpen: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp)
            .clickable(onClick = onOpen),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GroupAvatar(group.name)
        Spacer(Modifier.width(12.dp))
        Text(
            group.name,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = if (unreadCount > 0) FontWeight.Bold else null,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (unreadCount > 0) {
            Spacer(Modifier.width(8.dp))
            Text(
                unreadCount.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.primary, CircleShape)
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
    }
}

/** Round initial avatar for a group (decorative; the name row is announced). */
@Composable
internal fun GroupAvatar(name: String) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            name.trim().firstOrNull()?.uppercase().orEmpty(),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

/**
 * The create-group dialog (Phase 19): name plus member picking from the
 * existing contacts. The creator is the ADMIN by definition; the picked
 * contacts join as MEMBER.
 */
@Composable
internal fun CreateGroupDialog(
    contacts: List<ContactDto>,
    onCreate: (name: String, members: List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    val picked = remember { mutableStateOf(setOf<String>()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.groups_create_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.groups_name_label)) },
                )
                Spacer(Modifier.size(12.dp))
                Text(
                    stringResource(R.string.groups_pick_members),
                    style = MaterialTheme.typography.labelLarge,
                    color = StringTelephoneTheme.accents.textMuted,
                )
                LazyColumn(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    items(contacts, key = { it.username }) { contact ->
                        val checked = contact.username in picked.value
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    picked.value = if (checked) picked.value - contact.username else picked.value + contact.username
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = {
                                    picked.value = if (it) picked.value + contact.username else picked.value - contact.username
                                },
                            )
                            Text(
                                contact.displayName.ifBlank { contact.username },
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(name.trim(), picked.value.toList()); onDismiss() },
                enabled = name.isNotBlank(),
            ) {
                Text(stringResource(R.string.groups_create))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

/**
 * The group detail screen (Phase 19): member list with roles; the ADMIN may
 * add and remove members, rename and disband; a MEMBER may only leave.
 */
@Composable
internal fun GroupDetailScreen(
    group: GroupDto?,
    contacts: List<ContactDto>,
    meUsername: String,
    viewModel: AppViewModel,
) {
    if (group == null) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = viewModel::backToContacts) {
                    Icon(StringTelephoneIcons.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                }
            }
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.groups_load_failed), color = StringTelephoneTheme.accents.textMuted)
            }
        }
        return
    }
    var showAddMember by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var confirmDisband by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 0.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = viewModel::backToContacts) {
                Icon(StringTelephoneIcons.ArrowBack, contentDescription = stringResource(R.string.cd_back))
            }
            Column {
                Text(
                    group.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    stringResource(R.string.groups_member_count, group.members.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = StringTelephoneTheme.accents.textMuted,
                )
            }
        }
        val admin = group.members.any { it.username == meUsername && it.role == "ADMIN" }
        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 24.dp)) {
            item(key = "actions") {
                Row {
                    TextButton(onClick = { showRename = true }, enabled = admin) {
                        Text(stringResource(R.string.groups_rename))
                    }
                    Spacer(Modifier.width(12.dp))
                    TextButton(onClick = { showAddMember = true }, enabled = admin) {
                        Text(stringResource(R.string.groups_add_member))
                    }
                    Spacer(Modifier.width(12.dp))
                    if (admin) {
                        TextButton(onClick = { confirmDisband = true }) {
                            Text(stringResource(R.string.groups_disband), color = MaterialTheme.colorScheme.error)
                        }
                    } else {
                        TextButton(onClick = {
                            viewModel.removeGroupMember(group.id, meUsername) { viewModel.backToContacts() }
                        }) {
                            Text(stringResource(R.string.groups_leave), color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
            items(group.members, key = { it.username }) { member ->
                MemberRow(
                    member = member,
                    displayName = viewModel.memberDisplayName(member.username, contacts),
                    canRemove = admin,
                    onRemove = { viewModel.removeGroupMember(group.id, member.username) { } },
                )
            }
        }
    }
    if (showAddMember) {
        AddMemberDialog(
            contacts = contacts.filter { candidate -> group.members.none { it.username == candidate.username } },
            onAdd = { username -> viewModel.addGroupMember(group.id, username) { showAddMember = false } },
            onDismiss = { showAddMember = false },
        )
    }
    if (showRename) {
        RenameGroupDialog(
            current = group.name,
            onRename = { name -> viewModel.renameGroup(group.id, name) { showRename = false } },
            onDismiss = { showRename = false },
        )
    }
    if (confirmDisband) {
        AlertDialog(
            onDismissRequest = { confirmDisband = false },
            title = { Text(stringResource(R.string.groups_disband_confirm_title)) },
            text = { Text(stringResource(R.string.groups_disband_confirm_body)) },
            confirmButton = {
                TextButton(onClick = { confirmDisband = false; viewModel.disbandGroup(group.id) { viewModel.backToContacts() } }) {
                    Text(stringResource(R.string.groups_disband), color = MaterialTheme.colorScheme.error)
                }
            },            dismissButton = {
                TextButton(onClick = { confirmDisband = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun MemberRow(member: GroupMemberDto, displayName: String, canRemove: Boolean, onRemove: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(displayName)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(displayName, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (member.role == "ADMIN") {
                Text(
                    stringResource(R.string.groups_role_admin),
                    style = MaterialTheme.typography.labelSmall,
                    color = StringTelephoneTheme.accents.brand,
                )
            }
        }
        if (canRemove && member.role != "ADMIN") {
            TextButton(onClick = onRemove) {
                Text(stringResource(R.string.groups_remove), color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun AddMemberDialog(
    contacts: List<ContactDto>,
    onAdd: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var selected by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.groups_add_member)) },
        text = {
            if (contacts.isEmpty()) {
                Text(stringResource(R.string.groups_no_candidates), color = StringTelephoneTheme.accents.textMuted)
            } else {
                LazyColumn {
                    items(contacts, key = { it.username }) { contact ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selected = contact.username }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = selected == contact.username,
                                onClick = { selected = contact.username },
                            )
                            Text(contact.displayName.ifBlank { contact.username }, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { selected?.let(onAdd); onDismiss() },
                enabled = selected != null,
            ) {
                Text(stringResource(R.string.groups_add_member))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun RenameGroupDialog(
    current: String,
    onRename: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.groups_rename)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text(stringResource(R.string.groups_name_label)) },
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onRename(name.trim()); onDismiss() },
                enabled = name.isNotBlank(),
            ) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

/**
 * The group chat screen (Phase 19): identical layout to the pairwise chat —
 * timeline, day chips, bubbles — with the group name in the header, sender
 * names above others' bubbles, the composing members line, and the "N read"
 * counter instead of a single tick legend. Text only in this first cut
 * (ADR-0012): media and voice keep the pairwise path.
 */
@Composable
internal fun GroupChatScreen(
    groupId: String,
    viewModel: AppViewModel,
    scrollbarVisible: Boolean? = null,
) {
    val messages by viewModel.messagesFor(groupId).collectAsState()
    val historyFailed by viewModel.historyErrorFor(groupId).collectAsState()
    val groups by viewModel.groupsState().collectAsState()
    val contactsState by viewModel.contactsState().collectAsState()
    val groupTyping by viewModel.groupTypingState().collectAsState()
    val replyingTo by viewModel.replyingTo.collectAsState()
    val group = groups.firstOrNull { it.id == groupId }
    val meUsername = viewModel.meUsername().orEmpty()
    val rows = remember(messages) { chatTimeline(messages, ZoneId.systemDefault()) }
    val listState = rememberLazyListState()
    var replySheetMessage by remember { mutableStateOf<ChatMessage?>(null) }
    var pendingReactionMessage by remember { mutableStateOf<ChatMessage?>(null) }

    LaunchedEffect(rows.size) {
        if (rows.isNotEmpty()) listState.animateScrollToItem(rows.size - 1)
    }

    // Group read receipts are marked only here (ADR-0010 through ADR-0012):
    // the group chat is open and rendered; nothing background marks read.
    val newestIncoming = messages.lastOrNull { !it.fromMe && it.serverId != null }
    LaunchedEffect(groupId, newestIncoming?.serverId) {
        val id = newestIncoming?.serverId ?: return@LaunchedEffect
        viewModel.markGroupConversationRead(groupId, id)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = viewModel::backToContacts) {
                Icon(StringTelephoneIcons.ArrowBack, contentDescription = stringResource(R.string.cd_back))
            }
            GroupAvatar(group?.name.orEmpty().ifBlank { "?" })
            Spacer(Modifier.width(12.dp))
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable { group?.let { viewModel.openGroupDetail(groupId) } },
            ) {
                Text(
                    group?.name.orEmpty(),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val composing = groupTyping[groupId].orEmpty()
                Text(
                    if (composing.isEmpty()) {
                        stringResource(R.string.groups_member_count, group?.members?.size ?: 0)
                    } else {
                        // Composing names replace the member count while visible.
                        composing.joinToString(", ") { viewModel.memberDisplayName(it, contactsState.contacts) }
                            .plus(" ${stringResource(R.string.chat_typing)}")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (composing.isEmpty()) StringTelephoneTheme.accents.textMuted else StringTelephoneTheme.accents.brand,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (historyFailed) {
            Column(modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp)) {
                ErrorNotice(stringResource(R.string.chat_history_failed))
                TextButton(onClick = { viewModel.openGroupChat(groupId) }) {
                    Text(stringResource(R.string.retry))
                }
            }
        }
        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .chatWallpaper(StringTelephoneTheme.chat.wallpaper, StringTelephoneTheme.chat.meta),
        ) {
            val bubbleMaxWidth = maxWidth * BUBBLE_WIDTH_FRACTION
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
            ) {
                items(rows, key = { it.key }) { row ->
                    when (row) {
                        is ChatRow.Day -> DayChipRow(row.date)
                        is ChatRow.Bubble -> GroupBubble(
                            message = row.message,
                            startsGroup = row.startsGroup,
                            maxWidth = bubbleMaxWidth,
                            senderName = viewModel.memberDisplayName(row.message.senderId(meUsername), contactsState.contacts),
                            showSender = !row.message.fromMe,
                        )
                    }
                }
            }
            ChatScrollbar(
                listState = listState,
                modifier = Modifier.align(Alignment.CenterEnd),
                visibleOverride = scrollbarVisible,
            )
        }
        Composer(
            value = viewModel.chatInput,
            onValueChange = { viewModel.onGroupChatInputChanged(groupId, it) },
            emojiPickerVisible = false,
            onToggleEmoji = { },
            onAttach = { },
            onSend = { viewModel.sendGroupMessage(groupId, viewModel.chatInput) },
            recordingStateFlow = viewModel.voiceRecordingState,
            onStartRecording = { false },
            onFinishRecording = { },
            onCancelRecording = { },
        )
    }
    pendingReactionMessage?.let { msg ->
        ReactionPickerDialog(
            myUsername = meUsername,
            reactions = msg.reactions,
            onReact = { emoji ->
                viewModel.toggleGroupReaction(groupId, msg, emoji)
                pendingReactionMessage = null
            },
            onDismiss = { pendingReactionMessage = null },
        )
    }
    replySheetMessage?.let { message ->
        GroupMessageSheet(
            message = message,
            isAdmin = group?.members?.any { it.username == meUsername && it.role == "ADMIN" } == true,
            onReply = {
                viewModel.setReplyingTo(message)
                replySheetMessage = null
            },
            onReact = {
                replySheetMessage = null
                pendingReactionMessage = message
            },
            onDelete = {
                viewModel.deleteGroupMessage(groupId, message)
                replySheetMessage = null
            },
            onDismiss = { replySheetMessage = null },
        )
    }
}

/** Long-press sheet for a group message (Phase 19): reply, react, delete. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GroupMessageSheet(
    message: ChatMessage,
    isAdmin: Boolean,
    onReply: () -> Unit,
    onReact: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            TextButton(onClick = onReply) { Text(stringResource(R.string.reply)) }
            if (message.serverId != null && !message.deleted) {
                TextButton(onClick = onReact) { Text(stringResource(R.string.react)) }
            }
            // Author or admin may delete for everyone (Phase 19).
            if (message.fromMe || isAdmin) {
                if (message.serverId != null && !message.deleted) {
                    TextButton(onClick = onDelete) {
                        Text(stringResource(R.string.chat_delete_for_everyone), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

/** Sender identity for the group bubble header: self or the sender's username. */
private fun ChatMessage.senderId(meUsername: String): String = if (fromMe) meUsername else sender

@Composable
private fun DayChipRow(date: java.time.LocalDate) = DayChip(date)

/**
 * A group chat bubble (Phase 19): the pairwise bubble plus the sender name
 * above others' bubbles and the "N read" counter replacing the read tick
 * legend — the counter is the group receipt surface (ADR-0012).
 */
@Composable
private fun GroupBubble(
    message: ChatMessage,
    startsGroup: Boolean,
    maxWidth: Dp,
    senderName: String,
    showSender: Boolean,
) {
    Column(
        horizontalAlignment = if (message.fromMe) Alignment.End else Alignment.Start,
        modifier = Modifier.fillMaxWidth().padding(top = if (startsGroup) 6.dp else 2.dp),
    ) {
        if (showSender && startsGroup) {
            Text(
                senderName,
                style = MaterialTheme.typography.labelSmall,
                color = StringTelephoneTheme.accents.brand,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 10.dp, bottom = 2.dp),
            )
        }
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = if (message.fromMe) StringTelephoneTheme.chat.outgoing else StringTelephoneTheme.chat.incoming,
            contentColor = StringTelephoneTheme.chat.onBubble,
            shadowElevation = 1.dp,
            modifier = Modifier.widthIn(max = maxWidth),
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                if (message.deleted) {
                    Text(
                        stringResource(R.string.preview_deleted),
                        style = MaterialTheme.typography.bodyMedium,
                        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                        color = StringTelephoneTheme.chat.meta,
                    )
                } else {
                    Text(message.content, style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.size(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        previewTimestamp(message.sentAtEpochMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = StringTelephoneTheme.chat.meta,
                    )
                    if (message.fromMe) {
                        Spacer(Modifier.size(4.dp))
                        val readLabel = if ((message.readCount ?: 0) > 0) {
                            stringResource(R.string.chat_read_count, message.readCount ?: 0)
                        } else {
                            stringResource(R.string.chat_sent)
                        }
                        Text(
                            readLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = StringTelephoneTheme.chat.meta,
                        )
                    }
                }
                if (message.reactions.isNotEmpty()) {
                    Spacer(Modifier.size(2.dp))
                    Text(
                        message.reactions.values.distinct().joinToString(" "),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}
