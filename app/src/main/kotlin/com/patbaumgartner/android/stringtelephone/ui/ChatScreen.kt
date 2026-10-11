package com.patbaumgartner.android.stringtelephone.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.width
import com.patbaumgartner.android.stringtelephone.R
import com.patbaumgartner.android.stringtelephone.chat.SendState
import com.patbaumgartner.android.stringtelephone.ui.theme.StringTelephoneTheme

@Composable
internal fun ChatScreen(peer: String, viewModel: AppViewModel) {
    val messages by viewModel.messagesFor(peer).collectAsState()
    val contactsState by viewModel.contactsState().collectAsState()
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    var emojiPickerVisible by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        PageHeader(
            stringResource(R.string.chat_title, viewModel.displayNameOf(peer, contactsState.contacts)),
            viewModel::backToContacts,
        )

        LaunchedEffect(messages.size) {
            if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(messages, key = { it.clientKey }) { message ->
                ChatBubble(message)
            }
        }
        if (emojiPickerVisible) {
            EmojiPickerRow(onEmoji = { viewModel.onChatInputChanged(peer, viewModel.chatInput + it) })
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.material3.IconButton(onClick = { emojiPickerVisible = !emojiPickerVisible }) {
                Text(stringResource(R.string.chat_emoji), style = MaterialTheme.typography.titleLarge)
            }
            OutlinedTextField(
                value = viewModel.chatInput,
                onValueChange = { viewModel.onChatInputChanged(peer, it) },
                modifier = Modifier.weight(1f),
                label = { Text(stringResource(R.string.chat_hint)) },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
            )
            androidx.compose.material3.Button(onClick = { viewModel.sendCurrent(peer) }) {
                Text(stringResource(R.string.chat_send))
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
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
    ) {
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        ) {
            items(QUICK_EMOJIS) { emoji ->
                Surface(
                    onClick = { onEmoji(emoji) },
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surface,
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

@Composable
private fun ChatBubble(message: com.patbaumgartner.android.stringtelephone.chat.ChatMessage) {
    val alignment = if (message.fromMe) Alignment.End else Alignment.Start
    val container = if (message.fromMe) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val content = if (message.fromMe) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    Column(
        horizontalAlignment = alignment,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = container,
            contentColor = content,
        ) {
            Text(
                message.content,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
        if (message.state != SendState.SENT) {
            Text(
                text = when (message.state) {
                    SendState.QUEUED -> stringResource(R.string.chat_sending)
                    SendState.FAILED -> stringResource(R.string.chat_failed_to_send)
                    SendState.SENT -> ""
                },
                style = MaterialTheme.typography.labelSmall,
                color = StringTelephoneTheme.accents.textMuted,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
    }
}
