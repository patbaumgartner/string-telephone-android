package com.patbaumgartner.android.stringtelephone.ui

import android.text.format.DateFormat
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
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
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Date

val LocalViewModel = androidx.compose.runtime.compositionLocalOf<AppViewModel?> { null }

@Composable
internal fun ChatScreen(
    peer: String,
    viewModel: AppViewModel,
    onCall: (String) -> Unit = viewModel::startCall,
    scrollbarVisible: Boolean? = null,
) {
    val messages by viewModel.messagesFor(peer).collectAsState()
    val historyFailed by viewModel.historyErrorFor(peer).collectAsState()
    val contactsState by viewModel.contactsState().collectAsState()
    val typingPeer by viewModel.typingPeerState().collectAsState()
    val replyingTo by viewModel.replyingTo.collectAsState()
    val rows = remember(messages) { chatTimeline(messages, ZoneId.systemDefault()) }
    val listState = rememberLazyListState()
    var emojiPickerVisible by remember { mutableStateOf(false) }
    var replySheetMessage by remember { mutableStateOf<ChatMessage?>(null) }
    var pendingReactionMessage by remember { mutableStateOf<ChatMessage?>(null) }

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
            onBack = viewModel::backToContacts,
            onCall = { onCall(peer) },
        )
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
    onBack: () -> Unit,
    onCall: () -> Unit,
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
        }
    }
}

/** Decorative initial; the name next to it is what screen readers announce. */
@Composable
private fun Avatar(name: String) {
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
private fun DayChip(date: LocalDate) {
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
private fun ChatBubble(
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
    val description = listOf(sender, message.content, time, status.takeIf { message.fromMe })
        .filterNotNull()
        .joinToString(", ")
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

@Composable
private fun Composer(
    value: String,
    onValueChange: (String) -> Unit,
    emojiPickerVisible: Boolean,
    onToggleEmoji: () -> Unit,
    onAttach: () -> Unit,
    onSend: () -> Unit,
) {
    val hint = stringResource(R.string.chat_hint)
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = StringTelephoneTheme.chat.incoming,
            shadowElevation = 1.dp,
            modifier = Modifier.weight(1f),
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
                                Text(hint, style = MaterialTheme.typography.bodyLarge, color = StringTelephoneTheme.chat.meta)
                            }
                            field()
                        }
                    },
                )
            }
        }
        val canSend = value.isNotBlank()
        Surface(
            onClick = onSend,
            enabled = canSend,
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary.copy(alpha = if (canSend) 1f else 0.5f),
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
    }
}

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
private fun ReactionPickerDialog(
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
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(modifier = Modifier.padding(bottom = 16.dp)) {
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
private fun Modifier.chatWallpaper(base: Color, dots: Color): Modifier = drawBehind {
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
private const val BUBBLE_WIDTH_FRACTION = 0.82f

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
