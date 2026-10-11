package com.patbaumgartner.android.stringtelephone.ui

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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

@Composable
internal fun ChatScreen(peer: String, viewModel: AppViewModel, onCall: (String) -> Unit = viewModel::startCall) {
    val messages by viewModel.messagesFor(peer).collectAsState()
    val historyFailed by viewModel.historyErrorFor(peer).collectAsState()
    val contactsState by viewModel.contactsState().collectAsState()
    val rows = remember(messages) { chatTimeline(messages, ZoneId.systemDefault()) }
    val listState = rememberLazyListState()
    var emojiPickerVisible by remember { mutableStateOf(false) }

    LaunchedEffect(rows.size) {
        if (rows.isNotEmpty()) listState.animateScrollToItem(rows.size - 1)
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
                                message = row.message,
                                startsGroup = row.startsGroup,
                                maxWidth = bubbleMaxWidth,
                                onRetry = { viewModel.retrySend(peer, row.message) },
                            )
                        }
                    }
                }
            }
            if (emojiPickerVisible) {
                EmojiPickerRow(onEmoji = { viewModel.onChatInputChanged(peer, viewModel.chatInput + it) })
            }
            Composer(
                value = viewModel.chatInput,
                onValueChange = { viewModel.onChatInputChanged(peer, it) },
                emojiPickerVisible = emojiPickerVisible,
                onToggleEmoji = { emojiPickerVisible = !emojiPickerVisible },
                onSend = { viewModel.sendCurrent(peer) },
            )
        }
    }
}

@Composable
private fun ChatHeader(
    peer: String,
    contact: ContactDto?,
    displayName: String,
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

@Composable
private fun ChatBubble(message: ChatMessage, startsGroup: Boolean, maxWidth: Dp, onRetry: () -> Unit) {
    val colors = StringTelephoneTheme.chat
    val failed = message.state == SendState.FAILED
    val time = DateFormat.getTimeFormat(LocalContext.current).format(Date(message.sentAtEpochMs))
    val status = when (message.state) {
        SendState.QUEUED -> stringResource(R.string.chat_sending)
        SendState.SENT -> stringResource(R.string.chat_sent)
        SendState.FAILED -> stringResource(R.string.chat_failed_to_send)
    }
    val sender = stringResource(if (message.fromMe) R.string.chat_from_you else R.string.chat_from_them)
    val description = listOf(sender, message.content, time, status.takeIf { message.fromMe })
        .filterNotNull()
        .joinToString(", ")
    Column(
        horizontalAlignment = if (message.fromMe) Alignment.End else Alignment.Start,
        modifier = Modifier.fillMaxWidth().padding(top = if (startsGroup) 6.dp else 2.dp),
    ) {
        val shape = BubbleShape(tailOnEnd = message.fromMe, withTail = startsGroup)
        val bubbleModifier = Modifier
            .widthIn(max = maxWidth)
            .semantics(mergeDescendants = true) { contentDescription = description }
        val bubbleContent: @Composable () -> Unit = {
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
                                SendState.QUEUED -> StringTelephoneIcons.Schedule
                                SendState.SENT -> StringTelephoneIcons.Done
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
        val color = if (message.fromMe) colors.outgoing else colors.incoming
        if (failed) {
            Surface(onClick = onRetry, shape = shape, color = color, contentColor = colors.onBubble,
                shadowElevation = 1.dp, modifier = bubbleModifier, content = bubbleContent)
        } else {
            Surface(shape = shape, color = color, contentColor = colors.onBubble,
                shadowElevation = 1.dp, modifier = bubbleModifier, content = bubbleContent)
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
private fun Composer(
    value: String,
    onValueChange: (String) -> Unit,
    emojiPickerVisible: Boolean,
    onToggleEmoji: () -> Unit,
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
