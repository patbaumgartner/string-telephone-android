package com.patbaumgartner.android.stringtelephone.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.patbaumgartner.android.stringtelephone.R
import com.patbaumgartner.android.stringtelephone.ui.theme.StringTelephoneTheme

@Composable
internal fun SettingsScreen(viewModel: AppViewModel) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState()),
    ) {
        PageHeader(stringResource(R.string.settings_title), viewModel::backToContacts)
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp)) {
            Spacer(Modifier.height(12.dp))
            val profileName = viewModel.signedInDisplayName()?.takeIf { it.isNotBlank() }
                ?: viewModel.signedInUsername()
            if (profileName != null) {
                Text(profileName, style = MaterialTheme.typography.headlineSmall)
                viewModel.signedInUsername()
                    ?.takeIf { it != profileName }
                    ?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodyMedium,
                            color = StringTelephoneTheme.accents.textMuted,
                        )
                    }
                Spacer(Modifier.height(24.dp))
            }
            Button(
                onClick = viewModel::logout,
                modifier = Modifier.fillMaxWidth().height(54.dp),
                shape = RoundedCornerShape(16.dp),
            ) {
                Text(stringResource(R.string.settings_logout))
            }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = viewModel::clearLocalData,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) {
                Text(stringResource(R.string.settings_clear_data))
            }
            Spacer(Modifier.height(40.dp))
            Text(
                stringResource(R.string.settings_credentials_note),
                style = MaterialTheme.typography.bodyMedium,
                color = StringTelephoneTheme.accents.textMuted,
            )
            Spacer(Modifier.height(32.dp))
        }
    }
}
