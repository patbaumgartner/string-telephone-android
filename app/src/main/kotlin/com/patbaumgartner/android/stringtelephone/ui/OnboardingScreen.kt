package com.patbaumgartner.android.stringtelephone.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.material3.Icon
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
import com.patbaumgartner.android.stringtelephone.data.SignInError
import com.patbaumgartner.android.stringtelephone.ui.theme.StringTelephoneTheme

@Composable
internal fun OnboardingScreen(viewModel: AppViewModel) {
    val state by viewModel.signInState().collectAsState()

    Column(
        modifier = Modifier.fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            StringTelephoneIcons.Call,
            contentDescription = null,
            tint = StringTelephoneTheme.accents.brand,
            modifier = Modifier.size(40.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(R.string.onboarding_welcome),
            style = MaterialTheme.typography.displayLarge,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.onboarding_tagline),
            style = MaterialTheme.typography.bodyLarge,
            color = StringTelephoneTheme.accents.textMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth(),
        )
        Spacer(Modifier.height(28.dp))
        Column(modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth()) {
            SetupField(
                stringResource(R.string.onboarding_server_url),
                viewModel.serverUrlInput,
                enabled = !state.inProgress,
                keyboardType = KeyboardType.Uri,
            ) {
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
            SetupField(
                stringResource(R.string.onboarding_username),
                viewModel.usernameInput,
                enabled = !state.inProgress,
            ) {
                viewModel.onUsernameChanged(it)
            }
            SetupField(
                stringResource(R.string.onboarding_password),
                viewModel.passwordInput,
                secret = true,
                enabled = !state.inProgress,
            ) { viewModel.onPasswordChanged(it) }
            SetupField(
                stringResource(R.string.onboarding_sip_secret),
                viewModel.sipSecretInput,
                secret = true,
                enabled = !state.inProgress,
            ) { viewModel.onSipSecretChanged(it) }
            state.error?.let { error ->
                Spacer(Modifier.height(16.dp))
                ErrorNotice(stringResource(signInErrorResource(error)))
            }
            Spacer(Modifier.height(32.dp))
            Button(
                onClick = viewModel::signIn,
                enabled = !state.inProgress,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(16.dp),
            ) {
                if (state.inProgress) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                } else {
                    Text(stringResource(R.string.onboarding_sign_in))
                }
            }
            Spacer(Modifier.height(24.dp))
            Text(
                stringResource(R.string.onboarding_credentials_note),
                style = MaterialTheme.typography.bodyMedium,
                color = StringTelephoneTheme.accents.textMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

internal fun signInErrorResource(error: SignInError): Int = when (error) {
    SignInError.INVALID_URL -> R.string.sign_in_invalid_url
    SignInError.INSECURE_URL -> R.string.sign_in_insecure_url
    SignInError.MISSING_CREDENTIALS -> R.string.sign_in_missing_credentials
    SignInError.AUTHENTICATION -> R.string.sign_in_authentication
    SignInError.NETWORK -> R.string.sign_in_network
    SignInError.SERVER -> R.string.sign_in_server
    SignInError.PROTOCOL -> R.string.sign_in_protocol
    SignInError.IDENTITY -> R.string.sign_in_identity
}

@Composable
private fun SetupField(
    label: String,
    value: String,
    secret: Boolean = false,
    enabled: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Ascii,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (secret) KeyboardType.Password else keyboardType,
            autoCorrectEnabled = false,
        ),
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
        shape = RoundedCornerShape(14.dp),
    )
}
