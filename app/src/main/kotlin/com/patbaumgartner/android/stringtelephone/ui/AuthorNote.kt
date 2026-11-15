package com.patbaumgartner.android.stringtelephone.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.patbaumgartner.android.stringtelephone.R
import com.patbaumgartner.android.stringtelephone.ui.theme.StringTelephoneTheme

@Composable
internal fun AuthorNote() {
    val uriHandler = LocalUriHandler.current
    val resources = LocalResources.current
    var licensesShown by remember { mutableStateOf(false) }
    val authorUrl = stringResource(R.string.about_author_url)
    val authorName = stringResource(R.string.about_author_name)
    val authorNote = stringResource(R.string.about_author)
    val authorStart = authorNote.indexOf(authorName)
    val styledAuthorNote = buildAnnotatedString {
        append(authorNote)
        if (authorStart >= 0) {
            addStyle(SpanStyle(fontWeight = FontWeight.Bold), authorStart, authorStart + authorName.length)
        }
    }
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
        TextButton(onClick = { licensesShown = true }, contentPadding = PaddingValues(0.dp)) {
            Text(stringResource(R.string.about_open_source_licenses), color = StringTelephoneTheme.accents.brand)
        }
        Text(
            text = stringResource(R.string.about_app_license),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(
            onClick = { uriHandler.openUri(authorUrl) },
            contentPadding = PaddingValues(0.dp),
        ) {
            Text(styledAuthorNote, style = MaterialTheme.typography.bodyMedium, color = StringTelephoneTheme.accents.brand)
        }
        Text(
            text = stringResource(R.string.about_crafted),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
