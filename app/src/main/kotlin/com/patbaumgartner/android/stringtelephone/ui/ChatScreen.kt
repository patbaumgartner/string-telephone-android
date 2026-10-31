package com.patbaumgartner.android.stringtelephone.ui

import android.text.format.DateFormat
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.patbaumgartner.android.stringtelephone.R
import com.patbaumgartner.android.stringtelephone.chat.ChatMessage
import com.patbaumgartner.android.stringtelephone.chat.SendState
import com.patbaumgartner.android.stringtelephone.core.ContactDto
import com.patbaumgartner.android.stringtelephone.ui.theme.StringTelephoneTheme
import com.patbaumgartner.android.stringtelephone.voice.PlaybackSpeed
import com.patbaumgartner.android.stringtelephone.voice.VoicePlayerState
import com.patbaumgartner.android.stringtelephone.voice.VoiceRecordingState
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Date
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

val LocalViewModel = androidx.compose.runtime.compositionLocalOf<AppViewModel?> { null }

@Composable
internal fun ChatScreen(
    peer: String,
    viewModel: AppViewModel,
    onCall: (String) -> Unit = viewModel::startCall,
    onVideoCall: (String) -> Unit = { viewModel.startCall(it, withVideo = true) },
    scrollbarVisible: Boolean? = null,
) {
    val messages by viewModel.messagesFor(peer).collectAsState()
    val historyFailed by viewModel.historyErrorFor(peer).collectAsState()
    val contactsState by viewModel.contactsState().collectAsState()
    val typingPeer by viewModel.typingPeerState().collectAsState()
    val replyingTo by viewModel.replyingTo.collectAsState()
    val editingMessage by viewModel.editingMessage.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val searchResults by viewModel.searchResults.collectAsState()
    val searching by viewModel.searching.collectAsState()
    var searchOpen by remember { mutableStateOf(false) }
    val rows = remember(messages) { chatTimeline(messages, ZoneId.systemDefault()) }
    val listState = rememberLazyListState()
    val jumpScope = rememberCoroutineScope()
    var emojiPickerVisible by remember { mutableStateOf(false) }
    var replySheetMessage by remember { mutableStateOf<ChatMessage?>(null) }
    var pendingReactionMessage by remember { mutableStateOf<ChatMessage?>(null) }

    // Voice playback belongs to this screen: leaving the chat stops the audio
    // instead of letting it play on behind another screen.
    DisposableEffect(viewModel) {
        onDispose {
            viewModel.stopVoicePlayback()
            // A locked take is hands-free: leaving the chat must not leave the
            // microphone recording with no UI left to send or discard it.
            viewModel.cancelVoiceRecording()
        }
    }

    LaunchedEffect(rows.size) {
        if (rows.isNotEmpty()) listState.animateScrollToItem(rows.size - 1)
    }

    // Read receipts are marked only here: the chat screen is open and the
    // messages are rendered. A notification, a background sync or a cursor
    // catch-up must never mark a message read (ADR-0010).
    val newestIncoming = messages.lastOrNull { !it.fromMe && it.serverId != null }
    LaunchedEffect(peer, newestIncoming?.serverId) {
        val id = newestIncoming?.serverId ?: return@LaunchedEffect
        viewModel.markConversationRead(peer, id)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
    ) {
        ChatHeader(
            peer = peer,
            contact = contactsState.contacts.firstOrNull { it.username == peer },
            displayName = viewModel.displayNameOf(peer, contactsState.contacts),
            isTyping = typingPeer == peer,
            searchOpen = searchOpen,
            onToggleSearch = {
                searchOpen = !searchOpen
                if (!searchOpen) viewModel.closeSearch()
            },
            onBack = {
                if (searchOpen) {
                    searchOpen = false
                    viewModel.closeSearch()
                }
                viewModel.backToContacts()
            },
            onCall = { onCall(peer) },
            onVideoCall = { onVideoCall(peer) },
        )
        if (searchOpen) {
            ChatSearchBar(
                query = searchQuery,
                searching = searching,
                resultCount = searchResults.size,
                onQueryChange = { viewModel.searchInChat(peer, it) },
                onClose = {
                    searchOpen = false
                    viewModel.closeSearch()
                },
            )
            if (searchResults.isNotEmpty()) {
                ChatSearchResults(
                    results = searchResults,
                    peerDisplayName = viewModel.displayNameOf(peer, contactsState.contacts),
                    onJump = { message ->
                        val index = timelineIndexOf(rows, message.clientKey)
                        if (index >= 0) {
                            searchOpen = false
                            viewModel.closeSearch()
                            jumpScope.launch { listState.animateScrollToItem(index) }
                        }
                    },
                )
            }
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .chatWallpaper(StringTelephoneTheme.chat.wallpaper, StringTelephoneTheme.chat.meta)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)),
        ) {
            if (historyFailed) {
                Column(modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp)) {
                    ErrorNotice(stringResource(R.string.chat_history_failed))
                    TextButton(onClick = { viewModel.openChat(peer) }) {
                        Text(stringResource(R.string.retry))
                    }
                }
            }
            BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
                val bubbleMaxWidth = maxWidth * BUBBLE_WIDTH_FRACTION
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                ) {
                    items(rows, key = { it.key }) { row ->
                        when (row) {
                            is ChatRow.Day -> DayChip(row.date)
                            is ChatRow.Bubble -> ChatBubble(
                                peer = peer,
                                message = row.message,
                                startsGroup = row.startsGroup,
                                maxWidth = bubbleMaxWidth,
                                messages = messages,
                                peerDisplayName = viewModel.displayNameOf(peer, contactsState.contacts),
                                onRetry = { viewModel.retrySend(peer, row.message) },
                                onLongPress = { replySheetMessage = row.message },
                                viewModel = viewModel,
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
            if (emojiPickerVisible) {
                EmojiPickerRow(onEmoji = { viewModel.onChatInputChanged(peer, viewModel.chatInput + it) })
            }
            val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
                uri?.let { viewModel.attachImage(peer, it) }
            }
            // Quoted reply target above the composer (ADR-0011, Phase 9).
            replyingTo?.let { quoted ->
                QuotedMessageBanner(
                    quoted = quoted,
                    authorLabel = if (quoted.fromMe) {
                        stringResource(R.string.chat_from_you)
                    } else {
                        viewModel.displayNameOf(peer, contactsState.contacts)
                    },
                    onDismiss = { viewModel.clearReplyTo() },
                )
            }
            // Edit banner above the composer (Phase 17): shows the message
            // being changed; dismissing restores the preserved draft.
            editingMessage?.let { editing ->
                EditMessageBanner(
                    content = editing.content,
                    onDismiss = { viewModel.cancelEditing() },
                )
            }
            Composer(
                value = viewModel.chatInput,
                onValueChange = { viewModel.onChatInputChanged(peer, it) },
                emojiPickerVisible = emojiPickerVisible,
                onToggleEmoji = { emojiPickerVisible = !emojiPickerVisible },
                onAttach = {
                    picker.launch(
                        androidx.activity.result.PickVisualMediaRequest(
                            ActivityResultContracts.PickVisualMedia.ImageOnly,
                        ),
                    )
                },
                onSend = { viewModel.sendCurrent(peer) },
                recordingStateFlow = viewModel.voiceRecordingState,
                onStartRecording = { viewModel.startVoiceRecording() },
                onFinishRecording = { viewModel.finishVoiceRecording(peer) },
                onCancelRecording = { viewModel.cancelVoiceRecording() },
            )
        }
    }
    // Show the reaction picker when the long-press sheet chose "React".
    pendingReactionMessage?.let { msg ->
        ReactionPickerDialog(
            myUsername = viewModel.meUsername() ?: "",
            reactions = msg.reactions,
            onReact = { emoji ->
                viewModel.toggleReaction(peer, msg, emoji)
                pendingReactionMessage = null
            },
            onDismiss = { pendingReactionMessage = null },
        )
    }
    replySheetMessage?.let { message ->
        ReplyOptionsSheet(
            message = message,
            onReply = {
                viewModel.setReplyingTo(message)
                replySheetMessage = null
            },
            onReact = {
                replySheetMessage = null
                pendingReactionMessage = message
            },
            onDeleteForMe = {
                viewModel.deleteForMe(peer, message)
                replySheetMessage = null
            },
            onDeleteForBoth = {
                viewModel.deleteMessage(peer, message)
                replySheetMessage = null
            },
            onEdit = {
                viewModel.startEditing(peer, message)
                replySheetMessage = null
            },
            onDismiss = { replySheetMessage = null },
        )
    }
}

@Composable
private fun ChatHeader(
    peer: String,
    contact: ContactDto?,
    displayName: String,
    isTyping: Boolean = false,
    searchOpen: Boolean = false,
    onToggleSearch: () -> Unit = {},
    onBack: () -> Unit,
    onCall: () -> Unit,
    onVideoCall: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.background, shadowElevation = 2.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(StringTelephoneIcons.ArrowBack, contentDescription = stringResource(R.string.cd_back))
            }
            Avatar(displayName.ifBlank { peer })
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    displayName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (isTyping) {
                    // Typing replaces the presence row: the indicator is the
                    // fresher, more specific signal while it is visible.
                    Text(
                        stringResource(R.string.chat_typing),
                        style = MaterialTheme.typography.bodySmall,
                        color = StringTelephoneTheme.accents.brand,
                        maxLines = 1,
                    )
                } else {
                    val presence = presenceStatusOf(contact?.presence)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PresenceDot(presence)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            presenceLabel(presence),
                            style = MaterialTheme.typography.bodySmall,
                            color = StringTelephoneTheme.accents.textMuted,
                            maxLines = 1,
                        )
                    }
                }
            }
            IconButton(onClick = onCall) {
                Icon(
                    StringTelephoneIcons.Call,
                    contentDescription = stringResource(R.string.cd_call),
                    tint = StringTelephoneTheme.accents.brand,
                )
            }
            IconButton(onClick = onVideoCall) {
                Icon(
                    StringTelephoneIcons.Videocam,
                    contentDescription = stringResource(R.string.cd_video_call),
                    tint = StringTelephoneTheme.accents.brand,
                )
            }
            IconButton(onClick = onToggleSearch) {
                Icon(
                    StringTelephoneIcons.Search,
                    contentDescription = stringResource(
                        if (searchOpen) R.string.cd_close_search else R.string.cd_open_search,
                    ),
                    tint = StringTelephoneTheme.accents.brand,
                )
            }
        }
    }
}

/**
 * The in-chat search field (Phase 16): a query box with the running result
 * count; clearing the text empties the results but keeps the mode open.
 */
@Composable
private fun ChatSearchBar(
    query: String,
    searching: Boolean,
    resultCount: Int,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            placeholder = { Text(stringResource(R.string.chat_search_hint)) },
            leadingIcon = { Icon(StringTelephoneIcons.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(StringTelephoneIcons.Close, contentDescription = stringResource(R.string.cd_clear_search))
                    }
                }
            },
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.fillMaxWidth(),
        )
        if (query.isNotBlank()) {
            val status = when {
                searching -> stringResource(R.string.chat_searching)
                resultCount == 0 -> stringResource(R.string.chat_search_no_results)
                else -> pluralStringResource(R.plurals.chat_search_results, resultCount, resultCount)
            }
            Text(
                status,
                style = MaterialTheme.typography.labelSmall,
                color = StringTelephoneTheme.accents.textMuted,
                modifier = Modifier.padding(start = 16.dp, top = 4.dp),
            )
        }
    }
}

/**
 * Search results (Phase 16): newest first, tap jumps to the message in the
 * timeline. Results not in the loaded history are still listed but disabled.
 */
@Composable
private fun ChatSearchResults(
    results: List<ChatMessage>,
    peerDisplayName: String,
    onJump: (ChatMessage) -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp)) {
            items(results, key = { it.clientKey }) { message ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onJump(message) }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            if (message.fromMe) stringResource(R.string.chat_search_from_me) else peerDisplayName,
                            style = MaterialTheme.typography.labelSmall,
                            color = StringTelephoneTheme.accents.textMuted,
                        )
                        Text(
                            previewSubtitle(message),
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Icon(
                        StringTelephoneIcons.ArrowBack,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp).rotate(180f),
                        tint = StringTelephoneTheme.accents.textMuted,
                    )
                }
            }
        }
    }
}

/**
 * Scrolls the timeline to a search result (Phase 16). When the timeline
 * contains older messages than the loaded history, jump to the newest end
 * first; the index exists only if the message is already loaded.
 */
/** Decorative initial; the name next to it is what screen readers announce. */
@Composable
internal fun Avatar(name: String) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            name.trim().firstOrNull()?.uppercase().orEmpty(),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

@Composable
internal fun DayChip(date: LocalDate) {
    val today = LocalDate.now()
    val locale = LocalConfiguration.current.locales[0]
    val label = when (dayLabel(date, today)) {
        DayLabel.TODAY -> stringResource(R.string.chat_today)
        DayLabel.YESTERDAY -> stringResource(R.string.chat_yesterday)
        DayLabel.WEEKDAY -> date.dayOfWeek.getDisplayName(TextStyle.FULL, locale)
        DayLabel.DATE -> date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))
    }
    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = StringTelephoneTheme.chat.incoming,
            contentColor = StringTelephoneTheme.chat.meta,
            shadowElevation = 1.dp,
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ChatBubble(
    peer: String,
    message: ChatMessage,
    startsGroup: Boolean,
    maxWidth: Dp,
    messages: List<ChatMessage>,
    peerDisplayName: String,
    onRetry: () -> Unit,
    onLongPress: () -> Unit,
    viewModel: AppViewModel? = null,
) {
    val colors = StringTelephoneTheme.chat
    val failed = message.state == SendState.FAILED
    val time = DateFormat.getTimeFormat(LocalContext.current).format(Date(message.sentAtEpochMs))
    val status = when (message.state) {
        SendState.PENDING -> stringResource(R.string.chat_sending)
        SendState.SENT -> stringResource(R.string.chat_sent)
        SendState.READ -> stringResource(R.string.chat_read)
        SendState.FAILED -> stringResource(R.string.chat_failed_to_send)
    }
    val sender = stringResource(if (message.fromMe) R.string.chat_from_you else R.string.chat_from_them)
    val editedHint = if (message.editedAtEpochMs != null) stringResource(R.string.chat_edited) else null
    val description = listOfNotNull(
        sender,
        message.content.takeIf { !message.deleted },
        time,
        editedHint,
        status.takeIf { message.fromMe },
    ).joinToString(", ")
    // Reactions need a server id (ADR-0011): a pending/failed message is not
    // on the server yet, so its bubble keeps only the retry gesture.
    val canReact = viewModel != null && message.serverId != null

    Column(
        horizontalAlignment = if (message.fromMe) Alignment.End else Alignment.Start,
        modifier = Modifier.fillMaxWidth().padding(top = if (startsGroup) 6.dp else 2.dp),
    ) {
        // Quoted reply bubble (ADR-0011, Phase 9): rendered above the main
        // bubble inside the message column. The quoted message must be in the
        // loaded history; no scroll-to-original in this phase.
        if (message.replyToId != null) {
            val quotedMessage = messages.firstOrNull { it.serverId == message.replyToId }
            if (quotedMessage != null) {
                QuotedBubble(
                    quotedMessage = quotedMessage,
                    peerDisplayName = peerDisplayName,
                    fromMe = message.fromMe,
                    maxWidth = maxWidth,
                )
            }
        }
        val shape = BubbleShape(tailOnEnd = message.fromMe, withTail = startsGroup)
        // Long-press opens the reply/reaction/delete sheet (ADR-0011). A failed
        // bubble keeps tap-to-retry: it has no server id to act on yet.
        val canDelete = viewModel != null && !message.deleted
        val pressModifier = if (canReact || canDelete) {
            Modifier.combinedClickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null,
                onClick = {},
                onLongClick = onLongPress,
                onLongClickLabel = stringResource(R.string.chat_reply),
            )
        } else {
            Modifier
        }
        val bubbleModifier = Modifier
            .widthIn(max = maxWidth)
            .then(pressModifier)
            .semantics(mergeDescendants = true) { contentDescription = description }
        val bubbleContent: @Composable () -> Unit = {
            if (message.deleted) {
                // Tombstone (ADR-0011, Phase 10): the content is gone at the
                // server; both sides render this placeholder instead.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(
                        start = if (message.fromMe) 10.dp else 10.dp + TAIL_WIDTH,
                        end = if (message.fromMe) 10.dp + TAIL_WIDTH else 10.dp,
                        top = 6.dp,
                        bottom = 6.dp,
                    ),
                ) {
                    Icon(
                        StringTelephoneIcons.Delete,
                        contentDescription = null,
                        tint = colors.meta,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        stringResource(R.string.chat_deleted),
                        style = MaterialTheme.typography.bodySmall.copy(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic),
                        color = colors.meta,
                    )
                    if (message.fromMe) {
                        Spacer(Modifier.width(6.dp))
                        Text(time, style = MaterialTheme.typography.labelSmall, color = colors.meta)
                    }
                }
            } else if (message.type == "IMAGE" && message.mediaId != null) {
                ImageBubbleContent(message, colors, time, failed, status, viewModel)
            } else if (message.type == "VOICE" && message.mediaId != null) {
                VoiceBubbleContent(message, colors, time, failed, viewModel)
            } else {
                MessageWithMeta(
                    text = message.content,
                    modifier = Modifier.padding(
                        start = if (message.fromMe) 10.dp else 10.dp + TAIL_WIDTH,
                        end = if (message.fromMe) 10.dp + TAIL_WIDTH else 10.dp,
                        top = 6.dp,
                        bottom = 6.dp,
                    ),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(time, style = MaterialTheme.typography.labelSmall, color = colors.meta)
                        if (message.editedAtEpochMs != null) {
                            Spacer(Modifier.width(3.dp))
                            Text(
                                stringResource(R.string.chat_edited),
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.meta,
                            )
                        }
                        if (message.fromMe) {
                            Spacer(Modifier.width(3.dp))
                            Icon(
                                when (message.state) {
                                    SendState.PENDING -> StringTelephoneIcons.Schedule
                                    SendState.SENT -> StringTelephoneIcons.Done
                                    SendState.READ -> StringTelephoneIcons.DoneAll
                                    SendState.FAILED -> StringTelephoneIcons.ErrorOutline
                                },
                                contentDescription = null,
                                tint = if (failed) MaterialTheme.colorScheme.error else StringTelephoneTheme.accents.brand,
                                modifier = Modifier.size(15.dp),
                            )
                        }
                    }
                }
            }
        }
        val color = if (message.fromMe) colors.outgoing else colors.incoming
        if (failed) {
            Surface(onClick = onRetry, shape = shape, color = color, contentColor = colors.onBubble,
                shadowElevation = 1.dp, modifier = bubbleModifier, content = bubbleContent)
        } else {
            Surface(shape = shape, color = color, contentColor = colors.onBubble,
                shadowElevation = 1.dp, modifier = bubbleModifier, content = bubbleContent)
        }
        if (canReact && message.reactions.isNotEmpty()) {
            ReactionChips(
                reactions = message.reactions,
                myUsername = viewModel.meUsername() ?: "",
                fromMe = message.fromMe,
                onChipClick = { emoji -> viewModel.toggleReaction(peer, message, emoji) },
            )
        }
        if (failed) {
            Text(
                stringResource(R.string.chat_tap_to_retry),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 3.dp, end = TAIL_WIDTH + 2.dp),
            )
        }
    }
}

/**
 * Lays out the message text with the time tucked into the last line when it
 * fits, otherwise on its own line underneath, aligned to the end.
 */
@Composable
private fun MessageWithMeta(text: String, modifier: Modifier = Modifier, meta: @Composable () -> Unit) {
    val textLayout = remember { arrayOfNulls<TextLayoutResult>(1) }
    Layout(
        modifier = modifier,
        content = {
            Text(text, style = MaterialTheme.typography.bodyLarge, onTextLayout = { textLayout[0] = it })
            meta()
        },
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val textPlaceable = measurables[0].measure(loose)
        val metaPlaceable = measurables[1].measure(Constraints(maxWidth = loose.maxWidth))
        val gap = 8.dp.roundToPx()
        val layout = textLayout[0]
        val lastLine = layout?.let { it.lineCount - 1 }
        val lastLineWidth = if (layout != null && lastLine != null) {
            (layout.getLineRight(lastLine) - layout.getLineLeft(lastLine)).toInt()
        } else {
            textPlaceable.width
        }
        val singleLine = layout?.lineCount == 1
        val rtlLastLine = layout != null && lastLine != null &&
            layout.getParagraphDirection(layout.getLineStart(lastLine)) == androidx.compose.ui.text.style.ResolvedTextDirection.Rtl
        val (width, height) = when {
            !rtlLastLine && singleLine && textPlaceable.width + gap + metaPlaceable.width <= loose.maxWidth ->
                (textPlaceable.width + gap + metaPlaceable.width) to maxOf(textPlaceable.height, metaPlaceable.height)
            !rtlLastLine && lastLineWidth + gap + metaPlaceable.width <= textPlaceable.width ->
                textPlaceable.width to textPlaceable.height
            else -> maxOf(textPlaceable.width, metaPlaceable.width) to textPlaceable.height + metaPlaceable.height
        }
        layout(width, height) {
            textPlaceable.placeRelative(0, 0)
            metaPlaceable.placeRelative(width - metaPlaceable.width, height - metaPlaceable.height)
        }
    }
}

@Composable
private fun ImageBubbleContent(message: ChatMessage, colors: com.patbaumgartner.android.stringtelephone.ui.theme.ChatColors, time: String, failed: Boolean, status: String, viewModel: AppViewModel?) {
    val baseUrl = viewModel?.mediaBaseUrl()
    val token = viewModel?.mediaToken()
    val thumbUrl = if (baseUrl != null && message.mediaId != null) "$baseUrl/v1/media/${message.mediaId}/thumbnail" else ""
    val fullUrl = if (baseUrl != null && message.mediaId != null) "$baseUrl/v1/media/${message.mediaId}" else ""
    var viewerOpen by remember { mutableStateOf(false) }
    Column(modifier = Modifier.padding(6.dp)) {
        Box(
            modifier = Modifier
                .size(width = 180.dp, height = 180.dp)
                .clickable { if (fullUrl.isNotBlank()) viewerOpen = true },
        ) {
            if (thumbUrl.isNotBlank()) {
                RemoteImage(url = thumbUrl, token = token, modifier = Modifier.fillMaxSize())
            }
            if (failed) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(StringTelephoneIcons.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                }
            } else if (thumbUrl.isBlank()) {
                // No URL resolved yet (no session): a quiet placeholder instead of an empty tile.
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(StringTelephoneIcons.Image, contentDescription = null, tint = colors.meta)
                }
            }
        }
        // Same edge as text bubbles: the time row hugs the end that points
        // away from the sender. Text rows place their meta at the bubble end
        // (MessageWithMeta), so image rows do the same regardless of the image.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End,
            modifier = Modifier.fillMaxWidth().padding(start = 6.dp, end = 2.dp, top = 4.dp),
        ) {
            Text(time, style = MaterialTheme.typography.labelSmall, color = colors.meta)
            if (message.fromMe) {
                Spacer(Modifier.width(3.dp))
                Icon(
                    when (message.state) {
                        SendState.PENDING -> StringTelephoneIcons.Schedule
                        SendState.SENT -> StringTelephoneIcons.Done
                        SendState.READ -> StringTelephoneIcons.DoneAll
                        SendState.FAILED -> StringTelephoneIcons.ErrorOutline
                    },
                    contentDescription = null,
                    tint = if (failed) MaterialTheme.colorScheme.error else StringTelephoneTheme.accents.brand,
                    modifier = Modifier.size(15.dp),
                )
            }
        }
    }
    if (viewerOpen) {
        androidx.compose.ui.window.Dialog(onDismissRequest = { viewerOpen = false }) {
            Surface(color = Color.Black) {
                RemoteImage(url = fullUrl, token = token, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            }
        }
    }
}

/**
 * The voice-message bubble (Phase 11): play/pause, a scrubable waveform,
 * the duration, and the speed toggle. Playback state comes from the shared
 * [AppViewModel] player, so only one message plays at a time and leaving
 * the screen stops the audio.
 */
@Composable
private fun VoiceBubbleContent(
    message: ChatMessage,
    colors: com.patbaumgartner.android.stringtelephone.ui.theme.ChatColors,
    time: String,
    failed: Boolean,
    viewModel: AppViewModel?,
) {
    val durationMs = message.durationMs ?: 0
    val playerState = viewModel?.voicePlayerState?.collectAsState()?.value ?: VoicePlayerState.Idle
    val speed = viewModel?.voicePlaybackSpeed?.collectAsState()?.value ?: PlaybackSpeed.X1
    val bars = remember(message.waveform) { normalizedWaveform(message.waveform) }
    // One player serves the whole chat: only the bubble whose media is loaded
    // may show playback controls or a progress fill.
    val isCurrent = playerState.mediaId != null && playerState.mediaId == message.mediaId
    val playing = isCurrent && playerState is VoicePlayerState.Playing
    val paused = isCurrent && playerState is VoicePlayerState.Paused
    val positionMs = when {
        !isCurrent -> 0
        playerState is VoicePlayerState.Playing -> playerState.playbackPosition
        playerState is VoicePlayerState.Paused -> playerState.position
        else -> 0
    }
    Column(modifier = Modifier.padding(start = 4.dp, end = 8.dp, top = 4.dp, bottom = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = {
                    when {
                        playing -> viewModel?.pauseVoicePlayback()
                        paused -> viewModel?.resumeVoicePlayback()
                        else -> viewModel?.playVoiceMessage(message)
                    }
                },
                modifier = Modifier.size(34.dp),
            ) {
                Icon(
                    if (playing) StringTelephoneIcons.Pause else StringTelephoneIcons.Play,
                    contentDescription = stringResource(
                        if (playing) R.string.chat_voice_pause else R.string.chat_voice_play,
                    ),
                    tint = if (failed) MaterialTheme.colorScheme.error else StringTelephoneTheme.accents.brand,
                    modifier = Modifier.size(22.dp),
                )
            }
            VoiceWaveform(
                samples = bars,
                progress = if (isCurrent && durationMs > 0) positionMs.toFloat() / durationMs else 0f,
                onScrub = if (viewModel != null && isCurrent) {
                    { fraction -> viewModel.seekVoicePlayback((durationMs * fraction).toInt()) }
                } else {
                    null
                },
                modifier = Modifier
                    .weight(1f, fill = false)
                    .width(130.dp)
                    .padding(horizontal = 4.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                formatVoiceDuration(durationMs),
                style = MaterialTheme.typography.labelSmall,
                color = colors.meta,
            )
            if (viewModel != null) {
                Spacer(Modifier.width(4.dp))
                Surface(
                    onClick = { viewModel.cycleVoicePlaybackSpeed() },
                    shape = RoundedCornerShape(10.dp),
                    color = colors.meta.copy(alpha = 0.14f),
                    contentColor = colors.meta,
                ) {
                    Text(
                        speed.label,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                    )
                }
            }
        }
        // Same edge as text bubbles: meta hugs the end away from the sender.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End,
            modifier = Modifier.fillMaxWidth().padding(start = 6.dp, end = 2.dp),
        ) {
            Text(time, style = MaterialTheme.typography.labelSmall, color = colors.meta)
            if (message.fromMe) {
                Spacer(Modifier.width(3.dp))
                Icon(
                    when (message.state) {
                        SendState.PENDING -> StringTelephoneIcons.Schedule
                        SendState.SENT -> StringTelephoneIcons.Done
                        SendState.READ -> StringTelephoneIcons.DoneAll
                        SendState.FAILED -> StringTelephoneIcons.ErrorOutline
                    },
                    contentDescription = null,
                    tint = if (failed) MaterialTheme.colorScheme.error else StringTelephoneTheme.accents.brand,
                    modifier = Modifier.size(15.dp),
                )
            }
        }
    }
}

/** "0:42" from milliseconds; voice durations are minutes:seconds. */
internal fun formatVoiceDuration(durationMs: Int): String {
    val totalSeconds = durationMs / 1000
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

/** Parses the server waveform string into normalized sample values. */
internal fun normalizedWaveform(waveform: String?): List<Int> {
    if (waveform.isNullOrBlank()) return emptyList()
    return waveform.split(',').mapNotNull { it.trim().toIntOrNull() }.map { it.coerceIn(0, 100) }
}

/** Fallback bars so an empty waveform still draws a player-shaped row. */
private fun fallbackWaveform(barCount: Int): List<Int> =
    List(barCount) { index -> 30 + ((index * 37) % 55) }

/** The voice waveform (Phase 11): bars up to the played position brighten. */
@Composable
private fun VoiceWaveform(
    samples: List<Int>,
    progress: Float,
    onScrub: ((Float) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val barColor = StringTelephoneTheme.accents.brand
    val inactiveColor = StringTelephoneTheme.chat.meta.copy(alpha = 0.45f)
    val bars = remember(samples) { if (samples.isEmpty()) fallbackWaveform(24) else samples }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        modifier = modifier.then(
            if (onScrub != null) {
                Modifier.pointerInput(samples) {
                    detectTapGestures { offset -> onScrub(offset.x / size.width.toFloat()) }
                }
            } else {
                Modifier
            },
        ),
    ) {
        bars.forEachIndexed { index, amplitude ->
            val played = progress <= 0f || index.toFloat() / bars.size <= progress
            val barHeight = 6.dp + 18.dp * (amplitude / 100f)
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(barHeight)
                    .background(
                        color = if (played) StringTelephoneTheme.accents.brand else inactiveColor,
                        shape = RoundedCornerShape(2.dp),
                    ),
            ) {}
        }
    }
}

@Composable
internal fun Composer(
    value: String,
    onValueChange: (String) -> Unit,
    emojiPickerVisible: Boolean,
    onToggleEmoji: () -> Unit,
    onAttach: () -> Unit,
    onSend: () -> Unit,
    recordingStateFlow: StateFlow<VoiceRecordingState>,
    onStartRecording: () -> Boolean,
    onFinishRecording: () -> Unit,
    onCancelRecording: () -> Unit,
) {
    val hint = stringResource(R.string.chat_hint)
    // Collected here rather than in ChatScreen: the recorder emits a new sample
    // every 50 ms and only the composer draws them.
    val recordingState by recordingStateFlow.collectAsState()
    val recordingActive = recordingState is VoiceRecordingState.Recording
    // The lock is a composer-level fact: the mic button's gesture sets it and
    // the recording bar announces it (hands-free takes end on a button, not on
    // release). It clears whenever a take ends, by send or by cancel.
    var locked by remember { mutableStateOf(false) }
    LaunchedEffect(recordingActive) {
        if (!recordingActive) locked = false
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(modifier = Modifier.weight(1f)) {
            if (recordingActive) {
                VoiceRecordingBar(
                    recordingState = recordingState,
                    locked = locked,
                    onSend = onFinishRecording,
                    onCancel = onCancelRecording,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = StringTelephoneTheme.chat.incoming,
                    shadowElevation = 1.dp,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        IconButton(onClick = onToggleEmoji) {
                            Icon(
                                StringTelephoneIcons.Mood,
                                contentDescription = stringResource(R.string.cd_emoji_picker),
                                tint = if (emojiPickerVisible) {
                                    StringTelephoneTheme.accents.brand
                                } else {
                                    StringTelephoneTheme.chat.meta
                                },
                            )
                        }
                        IconButton(onClick = onAttach) {
                            Text("📎", style = MaterialTheme.typography.titleMedium)
                        }
                        BasicTextField(
                            value = value,
                            onValueChange = onValueChange,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(color = StringTelephoneTheme.chat.onBubble),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            maxLines = 6,
                            modifier = Modifier
                                .weight(1f)
                                .padding(top = 13.dp, bottom = 13.dp, end = 16.dp)
                                .semantics { contentDescription = hint },
                            decorationBox = { field ->
                                Box {
                                    if (value.isEmpty()) {
                                        Text(
                                            hint,
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = StringTelephoneTheme.chat.meta,
                                        )
                                    }
                                    field()
                                }
                            },
                        )
                    }
                }
            }
        }
        // The mic button stays composed for the whole take. It owns the
        // press-and-hold gesture, and Compose cancels pointer handlers whose
        // node leaves the tree — swapping it for the bar on the first frame
        // would swallow release-to-send and the slide-up lock.
        if (value.isNotBlank() && !recordingActive) {
            Surface(
                onClick = onSend,
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shadowElevation = 1.dp,
                modifier = Modifier.size(48.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        StringTelephoneIcons.Send,
                        contentDescription = stringResource(R.string.chat_send),
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        } else {
            MicRecordButton(
                locked = locked,
                recording = recordingActive,
                onLockedChange = { locked = it },
                onStart = onStartRecording,
                onFinish = onFinishRecording,
                onCancel = onCancelRecording,
            )
        }
    }
}

/**
 * The mic button (Phase 11): press-and-hold records; sliding up past the lock
 * threshold locks hands-free recording; releasing sends. Mic permission is
 * requested on first press, consistent with the call gate.
 *
 * The composer keeps this composable in the tree while a take runs: the
 * gesture below decides when the take ends, and a pointer handler whose node
 * is detached is cancelled — which would swallow the release.
 */
@Composable
private fun MicRecordButton(
    locked: Boolean,
    recording: Boolean,
    onLockedChange: (Boolean) -> Unit,
    onStart: () -> Boolean,
    onFinish: () -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) onCancel()
    }
    // The gesture outlives recomposition, so it must not capture a stale take
    // state or stale callbacks.
    val isRecording by rememberUpdatedState(recording)
    val setLocked by rememberUpdatedState(onLockedChange)
    val startRecording by rememberUpdatedState(onStart)
    val finishRecording by rememberUpdatedState(onFinish)
    val cancelRecording by rememberUpdatedState(onCancel)
    Surface(
        shape = CircleShape,
        color = if (locked) StringTelephoneTheme.accents.brand else MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        shadowElevation = 1.dp,
        modifier = Modifier
            .size(48.dp)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = true)
                    // A running take — locked or not — is owned by the
                    // recording bar; a press here must not restart or end it.
                    if (isRecording) {
                        waitForUpOrCancellation()
                        return@awaitEachGesture
                    }
                    setLocked(false)
                    // Mic gate first (same as calls): no permission, no recording.
                    val granted = androidx.core.content.ContextCompat.checkSelfPermission(
                        context,
                        android.Manifest.permission.RECORD_AUDIO,
                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                    if (!granted) {
                        permissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                        waitForUpOrCancellation()
                        return@awaitEachGesture
                    }
                    if (!startRecording()) {
                        waitForUpOrCancellation()
                        return@awaitEachGesture
                    }
                    var travelUp = 0f
                    var travelLeft = 0f
                    var tookLock = false
                    var cancelled = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: break
                        if (change.changedToUp()) break
                        travelUp += change.previousPosition.y - change.position.y
                        travelLeft += change.previousPosition.x - change.position.x
                        if (!tookLock && travelUp > LOCK_GESTURE_PX) {
                            tookLock = true
                            setLocked(true)
                        }
                        // Slide left to throw the draft away; a locked recording
                        // is hands-free, so the bar's cancel button owns it.
                        if (!tookLock && travelLeft > CANCEL_GESTURE_PX) {
                            cancelled = true
                            change.consume()
                            break
                        }
                        change.consume()
                    }
                    if (cancelled) cancelRecording() else if (!tookLock) finishRecording()
                    // A locked recording continues; the bar owns the outcome.
                }
            },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                if (locked) StringTelephoneIcons.Lock else StringTelephoneIcons.Mic,
                contentDescription = stringResource(
                    if (locked) R.string.chat_voice_locked else R.string.cd_voice_record,
                ),
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

/**
 * The recording bar (Phase 11): replaces the text field while a take is in
 * progress, showing the elapsed time, a live waveform, and cancel/send.
 * [locked] marks a hands-free take, which keeps recording until one of those
 * buttons is tapped.
 */
@Composable
private fun VoiceRecordingBar(
    recordingState: VoiceRecordingState,
    locked: Boolean,
    onSend: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val recording = recordingState as? VoiceRecordingState.Recording
    val elapsed = recording?.elapsedMs ?: 0L
    val samples = recording?.samples ?: emptyList()
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = StringTelephoneTheme.chat.incoming,
        shadowElevation = 1.dp,
        modifier = modifier,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Icon(
                if (locked) StringTelephoneIcons.Lock else StringTelephoneIcons.Mic,
                contentDescription = stringResource(R.string.chat_voice_recording),
                tint = StringTelephoneTheme.accents.brand,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                formatVoiceDuration(elapsed.toInt()),
                style = MaterialTheme.typography.bodyMedium,
                color = StringTelephoneTheme.chat.onBubble,
            )
            Spacer(Modifier.width(10.dp))
            if (locked) {
                // Hands-free takes have no live bars to watch; the lock marker
                // is the useful signal, and the buttons end the take.
                Text(
                    stringResource(R.string.chat_voice_locked),
                    style = MaterialTheme.typography.labelSmall,
                    color = StringTelephoneTheme.accents.brand,
                )
                Spacer(Modifier.weight(1f))
            } else {
                VoiceWaveform(
                    samples = samples,
                    progress = 1f,
                    onScrub = null,
                    modifier = Modifier.width(80.dp),
                )
                Spacer(Modifier.weight(1f))
            }
            IconButton(onClick = onCancel) {
                Icon(
                    StringTelephoneIcons.Delete,
                    contentDescription = stringResource(R.string.cd_voice_cancel),
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp),
                )
            }
            IconButton(onClick = onSend) {
                Icon(
                    StringTelephoneIcons.Send,
                    contentDescription = stringResource(R.string.chat_send),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/** Slide-up distance (in px) that locks hands-free recording. */
private const val LOCK_GESTURE_PX = 140f

/** Slide-left distance (in px) that discards the recording instead of sending. */
private const val CANCEL_GESTURE_PX = 90f

/** Curated quick-pick emoji set; the system IME emoji panel also works. */
private val QUICK_EMOJIS = listOf(
    "😀", "😂", "🥲", "😊", "😍", "🤔", "😴", "🥱",
    "👍", "👎", "👏", "🙏", "💪", "🤝", "👌", "✌️",
    "❤️", "🔥", "🎉", "✨", "☕", "🍕", "🚀", "⭐",
    "😢", "😭", "😡", "🤯", "🙌", "👀", "💯", "🥳",
)

@Composable
private fun EmojiPickerRow(onEmoji: (String) -> Unit) {
    Surface(
        color = StringTelephoneTheme.chat.incoming,
        shape = RoundedCornerShape(16.dp),
        shadowElevation = 1.dp,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
    ) {
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        ) {
            items(QUICK_EMOJIS) { emoji ->
                Surface(
                    onClick = { onEmoji(emoji) },
                    shape = RoundedCornerShape(10.dp),
                    color = Color.Transparent,
                ) {
                    Text(
                        emoji,
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
}

/**
 * One chip per emoji under a bubble: `👍 2` when two people reacted. The
 * signed-in user's chip is highlighted; tapping any chip toggles the user's
 * own reaction to that emoji (ADR-0011).
 */
@Composable
private fun ReactionChips(
    reactions: Map<String, String>,
    myUsername: String,
    fromMe: Boolean,
    onChipClick: (String) -> Unit,
) {
    // One chip per distinct emoji; the count shows how many authors picked it.
    val grouped = reactions.entries.groupBy({ it.value }, { it.key })
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.padding(top = 3.dp, start = if (fromMe) 0.dp else TAIL_WIDTH, end = if (fromMe) TAIL_WIDTH else 0.dp),
    ) {
        grouped.forEach { (emoji, authors) ->
            val mine = myUsername in reactions && reactions[myUsername] == emoji
            val mineLabel = stringResource(R.string.chat_reaction_mine)
            Surface(
                onClick = { onChipClick(emoji) },
                shape = RoundedCornerShape(12.dp),
                color = if (mine) StringTelephoneTheme.accents.brand.copy(alpha = 0.18f) else StringTelephoneTheme.chat.incoming,
                shadowElevation = 1.dp,
                modifier = Modifier.semantics { contentDescription = if (mine) "$emoji ($mineLabel)" else emoji },
            ) {
                Text(
                    if (authors.size > 1) "$emoji ${authors.size}" else emoji,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }
    }
}

/** Long-press reaction sheet: any emoji from the quick set or the keyboard. */
@Composable
internal fun ReactionPickerDialog(
    myUsername: String,
    reactions: Map<String, String>,
    onReact: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 4.dp,
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    stringResource(R.string.chat_react),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(QUICK_EMOJIS) { emoji ->
                        val mine = reactions[myUsername] == emoji
                        Surface(
                            onClick = { onReact(emoji) },
                            shape = RoundedCornerShape(10.dp),
                            color = if (mine) StringTelephoneTheme.accents.brand.copy(alpha = 0.2f) else Color.Transparent,
                        ) {
                            Text(
                                emoji,
                                style = MaterialTheme.typography.headlineSmall,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Edit banner above the composer (Phase 17): mirrors the reply banner —
 * brand bar, "Editing" label, the message's current content, dismiss.
 */
@Composable
private fun EditMessageBanner(
    content: String,
    onDismiss: () -> Unit,
) {
    val preview = if (content.length > 60) content.take(57) + "…" else content
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(40.dp)
                .background(StringTelephoneTheme.accents.brand, RoundedCornerShape(2.dp)),
        )
        androidx.compose.foundation.layout.Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                stringResource(R.string.chat_editing),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = StringTelephoneTheme.accents.brand,
            )
            Text(
                preview,
                style = MaterialTheme.typography.bodySmall,
                color = StringTelephoneTheme.chat.meta,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
            Icon(
                StringTelephoneIcons.Close,
                contentDescription = stringResource(R.string.cd_cancel_edit),
                tint = StringTelephoneTheme.chat.meta,
            )
        }
    }
}

/**
 * The reply target shown above the composer when [quoted] is set
 * (ADR-0011, Phase 9). A small surface with author name, content preview
 * and a dismiss button.
 */
@Composable
private fun QuotedMessageBanner(
    quoted: ChatMessage,
    authorLabel: String,
    onDismiss: () -> Unit,
) {
    val preview = if (quoted.type == "IMAGE") {
        stringResource(R.string.photo)
    } else {
        if (quoted.content.length > 60) quoted.content.take(57) + "…" else quoted.content
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(40.dp)
                .background(StringTelephoneTheme.accents.brand, RoundedCornerShape(2.dp)),
        )
        androidx.compose.foundation.layout.Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                authorLabel,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = StringTelephoneTheme.accents.brand,
            )
            Text(
                preview,
                style = MaterialTheme.typography.bodySmall,
                color = StringTelephoneTheme.chat.meta,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
            Icon(
                StringTelephoneIcons.Close,
                contentDescription = stringResource(R.string.cd_close),
                tint = StringTelephoneTheme.chat.meta,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/**
 * A quoted reply shown inside a chat bubble (ADR-0011, Phase 9): a small
 * left-bordered surface with the quoted author's name and a text or media
 * preview, rendered above the main bubble content.
 */
@Composable
private fun QuotedBubble(
    quotedMessage: ChatMessage,
    peerDisplayName: String,
    fromMe: Boolean,
    maxWidth: Dp,
) {
    val colors = StringTelephoneTheme.chat
    val authorName = if (quotedMessage.fromMe) {
        stringResource(R.string.chat_from_you)
    } else {
        peerDisplayName
    }
    val contentPreview = if (quotedMessage.type == "IMAGE") {
        stringResource(R.string.photo)
    } else {
        if (quotedMessage.content.length > 40) quotedMessage.content.take(37) + "…" else quotedMessage.content
    }
    val barColor = if (fromMe) colors.outgoing else colors.incoming
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = barColor.copy(alpha = 0.6f),
        modifier = Modifier
            .widthIn(max = maxWidth)
            .padding(bottom = 3.dp),
    ) {
        Row(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(36.dp)
                    .background(StringTelephoneTheme.accents.brand, RoundedCornerShape(2.dp)),
            )
            androidx.compose.foundation.layout.Spacer(Modifier.width(8.dp))
            Column {
                Text(
                    authorName,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = StringTelephoneTheme.accents.brand,
                )
                Text(
                    contentPreview,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.meta,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * Long-press options sheet: Reply, React, Delete (ADR-0011, Phases 9–10).
 * Delete offers "for me" (local hide) and — only for messages the peer can
 * still see — "for both" (tombstone).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReplyOptionsSheet(
    message: ChatMessage,
    onReply: () -> Unit,
    onReact: () -> Unit,
    onDeleteForMe: () -> Unit,
    onDeleteForBoth: () -> Unit,
    onEdit: () -> Unit = {},
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(modifier = Modifier.padding(bottom = 16.dp)) {
            if (message.canEdit) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    Surface(
                        onClick = onEdit,
                        shape = RoundedCornerShape(12.dp),
                        color = StringTelephoneTheme.chat.incoming.copy(alpha = 0.7f),
                        modifier = Modifier.weight(1f),
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(vertical = 16.dp),
                        ) {
                            Icon(
                                StringTelephoneIcons.Edit,
                                contentDescription = null,
                                tint = StringTelephoneTheme.accents.brand,
                                modifier = Modifier.size(28.dp),
                            )
                            androidx.compose.foundation.layout.Spacer(Modifier.height(6.dp))
                            Text(
                                stringResource(R.string.chat_edit),
                                style = MaterialTheme.typography.labelMedium,
                                color = StringTelephoneTheme.chat.onBubble,
                            )
                        }
                    }
                }
                androidx.compose.foundation.layout.Spacer(Modifier.height(12.dp))
            }
            Text(
                stringResource(R.string.reply_to),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(start = 20.dp, bottom = 12.dp),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Surface(
                    onClick = onReply,
                    shape = RoundedCornerShape(12.dp),
                    color = StringTelephoneTheme.chat.incoming.copy(alpha = 0.7f),
                    modifier = Modifier.weight(1f),
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(vertical = 16.dp),
                    ) {
                        Icon(
                            StringTelephoneIcons.Reply,
                            contentDescription = null,
                            tint = StringTelephoneTheme.accents.brand,
                            modifier = Modifier.size(28.dp),
                        )
                        androidx.compose.foundation.layout.Spacer(Modifier.height(6.dp))
                        Text(
                            stringResource(R.string.reply),
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
                Surface(
                    onClick = onReact,
                    shape = RoundedCornerShape(12.dp),
                    color = StringTelephoneTheme.chat.incoming.copy(alpha = 0.7f),
                    modifier = Modifier.weight(1f),
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(vertical = 16.dp),
                    ) {
                        Text(
                            "😀",
                            style = MaterialTheme.typography.headlineMedium,
                        )
                        androidx.compose.foundation.layout.Spacer(Modifier.height(6.dp))
                        Text(
                            stringResource(R.string.react),
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.chat_delete),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(start = 20.dp, top = 8.dp, bottom = 12.dp),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Surface(
                    onClick = onDeleteForMe,
                    shape = RoundedCornerShape(12.dp),
                    color = StringTelephoneTheme.chat.incoming.copy(alpha = 0.7f),
                    modifier = Modifier.weight(1f),
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(vertical = 16.dp),
                    ) {
                        Icon(
                            StringTelephoneIcons.Delete,
                            contentDescription = null,
                            tint = StringTelephoneTheme.chat.meta,
                            modifier = Modifier.size(28.dp),
                        )
                        androidx.compose.foundation.layout.Spacer(Modifier.height(6.dp))
                        Text(
                            stringResource(R.string.chat_delete_for_me),
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
                // "Delete for both" needs a stored message; an optimistic or
                // failed send has no server id to tombstone yet.
                if (message.serverId != null) {
                    Surface(
                        onClick = onDeleteForBoth,
                        shape = RoundedCornerShape(12.dp),
                        color = StringTelephoneTheme.chat.incoming.copy(alpha = 0.7f),
                        modifier = Modifier.weight(1f),
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(vertical = 16.dp),
                        ) {
                            Icon(
                                StringTelephoneIcons.Delete,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(28.dp),
                            )
                            androidx.compose.foundation.layout.Spacer(Modifier.height(6.dp))
                            Text(
                                stringResource(R.string.chat_delete_for_everyone),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** A faint dot grid over the tinted chat background. */
internal fun Modifier.chatWallpaper(base: Color, dots: Color): Modifier = drawBehind {
    drawRect(base)
    val step = 22.dp.toPx()
    val radius = 1.2.dp.toPx()
    val dot = dots.copy(alpha = 0.12f)
    var y = step / 2
    var row = 0
    while (y < size.height) {
        var x = if (row % 2 == 0) step / 2 else step
        while (x < size.width) {
            drawCircle(dot, radius, Offset(x, y))
            x += step
        }
        y += step
        row++
    }
}

private val TAIL_WIDTH = 7.dp
internal const val BUBBLE_WIDTH_FRACTION = 0.82f

/**
 * Rounded bubble whose first message in a group points at its sender with a
 * small tail at the top corner: right for outgoing, left for incoming (in LTR).
 */
private class BubbleShape(private val tailOnEnd: Boolean, private val withTail: Boolean) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val radius = with(density) { 9.dp.toPx() }
        val tail = with(density) { TAIL_WIDTH.toPx() }
        val tailOnRight = tailOnEnd == (layoutDirection == LayoutDirection.Ltr)
        val w = size.width
        val h = size.height
        fun x(value: Float) = if (tailOnRight) value else w - value
        val bodyRight = w - tail
        val path = Path().apply {
            moveTo(x(radius), 0f)
            if (withTail) {
                lineTo(x(w), 0f)
                lineTo(x(bodyRight), minOf(tail * 1.5f, h - radius))
            } else {
                lineTo(x(bodyRight - radius), 0f)
                quadraticTo(x(bodyRight), 0f, x(bodyRight), radius)
            }
            lineTo(x(bodyRight), h - radius)
            quadraticTo(x(bodyRight), h, x(bodyRight - radius), h)
            lineTo(x(radius), h)
            quadraticTo(x(0f), h, x(0f), h - radius)
            lineTo(x(0f), radius)
            quadraticTo(x(0f), 0f, x(radius), 0f)
            close()
        }
        return Outline.Generic(path)
    }
}
