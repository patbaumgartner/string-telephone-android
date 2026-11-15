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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.patbaumgartner.android.stringtelephone.R
import com.patbaumgartner.android.stringtelephone.ui.theme.StringTelephoneTheme

@Composable
internal fun SettingsScreen(viewModel: AppViewModel) {
    val resources = LocalResources.current
    var licensesShown by remember { mutableStateOf(false) }
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
            Spacer(Modifier.height(24.dp))
            AuthorNote(
                build = viewModel.buildInfo(),
                onShowTypefaceLicense = { licensesShown = true },
            )
            Spacer(Modifier.height(32.dp))
        }
    }

    if (licensesShown) {
        val licenseText = remember(resources) {
            resources.openRawResource(R.raw.manrope_license).bufferedReader().use { it.readText() }
        }
        AlertDialog(
            onDismissRequest = { licensesShown = false },
            title = { Text(stringResource(R.string.about_open_source_licenses), color = StringTelephoneTheme.accents.brand) },
            text = {
                Text(
                    text = licenseText,
                    modifier = Modifier.height(360.dp).verticalScroll(rememberScrollState()),
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            confirmButton = {
                TextButton(onClick = { licensesShown = false }) { Text(stringResource(R.string.about_close)) }
            },
        )
    }
}
