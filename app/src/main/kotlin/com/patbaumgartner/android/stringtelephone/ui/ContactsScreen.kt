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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.patbaumgartner.android.stringtelephone.R
import com.patbaumgartner.android.stringtelephone.core.PresenceStatus
import com.patbaumgartner.android.stringtelephone.ui.theme.StringTelephoneTheme

@Composable
internal fun ContactsScreen(viewModel: AppViewModel, onCall: (String) -> Unit = viewModel::startCall) {
    val contactsState by viewModel.contactsState().collectAsState()
    val account by viewModel.accountState().collectAsState()
    var menuExpanded by remember { mutableStateOf(false) }
    val username = account.me?.username.orEmpty()
    val headerName = account.me?.displayName?.takeIf { it.isNotBlank() }
        ?: username.ifBlank { stringResource(R.string.app_name) }

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
        if (viewModel.callRegistrationFailed) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
                ErrorNotice(stringResource(R.string.call_registration_failed))
                TextButton(onClick = viewModel::retryCallRegistration) {
                    Text(stringResource(R.string.retry))
                }
            }
        }
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
                items(contactsState.contacts, key = { it.username }) { contact ->
                    ContactRow(
                        contact = contact,
                        presence = viewModel.presenceOf(contact.username),
                        onCall = { onCall(contact.username) },
                        onChat = { viewModel.openChat(contact.username) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ContactRow(contact: com.patbaumgartner.android.stringtelephone.core.ContactDto, presence: PresenceStatus, onCall: () -> Unit, onChat: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(contact.displayName, style = MaterialTheme.typography.titleMedium)
            Text(
                contact.username,
                color = StringTelephoneTheme.accents.textMuted,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        PresenceDot(presence)
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

@Composable
private fun PresenceDot(status: PresenceStatus) {
    val label = stringResource(
        when (status) {
            PresenceStatus.ONLINE -> R.string.presence_online
            PresenceStatus.OFFLINE -> R.string.presence_offline
            PresenceStatus.UNKNOWN -> R.string.presence_unknown
        },
    )
    val color = when (status) {
        PresenceStatus.ONLINE -> StringTelephoneTheme.accents.presence
        PresenceStatus.OFFLINE -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
        PresenceStatus.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f)
    }
    Surface(
        color = color,
        shape = CircleShape,
        modifier = Modifier.size(10.dp).semantics { contentDescription = label },
    ) {}
}
