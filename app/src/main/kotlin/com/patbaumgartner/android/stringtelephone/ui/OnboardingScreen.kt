package com.patbaumgartner.android.stringtelephone.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.patbaumgartner.android.stringtelephone.R
import com.patbaumgartner.android.stringtelephone.ui.theme.StringTelephoneTheme

@Composable
internal fun OnboardingScreen(viewModel: AppViewModel) {
    val state by viewModel.signInState().collectAsState()

    Column(
        modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(48.dp))
        Text(
            stringResource(R.string.onboarding_welcome),
            style = MaterialTheme.typography.displayLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.onboarding_tagline),
            style = MaterialTheme.typography.bodyLarge,
            color = StringTelephoneTheme.accents.textMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 40.dp),
        )
        Spacer(Modifier.height(40.dp))
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp)) {
            SetupField(stringResource(R.string.onboarding_server_url), viewModel.serverUrlInput) {
                viewModel.onServerUrlChanged(it)
            }
            if (viewModel.serverUrlIsInsecure()) {
                Text(
                    stringResource(R.string.onboarding_insecure_url_warning),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }
            SetupField(stringResource(R.string.onboarding_username), viewModel.usernameInput) {
                viewModel.onUsernameChanged(it)
            }
            SetupField(
                stringResource(R.string.onboarding_password),
                viewModel.passwordInput,
                secret = true,
            ) { viewModel.onPasswordChanged(it) }
            if (state.error != null) {
                Spacer(Modifier.height(16.dp))
                Text(
                    state.error.orEmpty(),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Spacer(Modifier.height(32.dp))
            Button(
                onClick = viewModel::signIn,
                enabled = !state.inProgress,
                modifier = Modifier.fillMaxWidth().height(54.dp),
                shape = RoundedCornerShape(16.dp),
            ) {
                if (state.inProgress) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Text(stringResource(R.string.onboarding_sign_in))
                }
            }
            Spacer(Modifier.height(40.dp))
            Text(
                stringResource(R.string.onboarding_credentials_note),
                style = MaterialTheme.typography.bodyMedium,
                color = StringTelephoneTheme.accents.textMuted,
            )
        }
    }
}


@Composable
private fun SetupField(label: String, value: String, secret: Boolean = false, onValueChange: (String) -> Unit) {
    androidx.compose.material3.OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (secret) KeyboardType.Password else KeyboardType.Ascii,
        ),
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
        shape = RoundedCornerShape(14.dp),
    )
}
