package hr.sting.hermes.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import hr.sting.hermes.R
import hr.sting.hermes.data.AppSettings
import hr.sting.hermes.data.HermesUiState

@Composable
fun OnboardingScreen(
    state: HermesUiState,
    onTest: (AppSettings) -> Unit,
    onSave: (AppSettings) -> Unit,
) {
    var draft by remember(state.settings) { mutableStateOf(state.settings.copy(hasCompletedOnboarding = true)) }
    var showAdvanced by remember { mutableStateOf(true) }
    var showPassword by remember { mutableStateOf(false) }
    var showAccessSecret by remember { mutableStateOf(false) }
    var showApiToken by remember { mutableStateOf(false) }
    val needsAccess = draft.serverUrl.contains("sting.hr", ignoreCase = true)
    val accessReady = !needsAccess ||
        (draft.accessClientId.isNotBlank() && draft.accessClientSecret.isNotBlank())
    val readyToConnect = draft.serverUrl.isNotBlank() && accessReady &&
        (draft.apiToken.isNotBlank() || (draft.username.isNotBlank() && draft.password.isNotBlank()))

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 24.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = stringResource(R.string.connect_title),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.connect_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = draft.serverUrl,
                onValueChange = { draft = draft.copy(serverUrl = it) },
                label = { Text(stringResource(R.string.server_url)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )

            OutlinedTextField(
                value = draft.apiToken,
                onValueChange = { draft = draft.copy(apiToken = it) },
                label = { Text(stringResource(R.string.api_token_recommended)) },
                singleLine = true,
                visualTransformation = if (showApiToken) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { showApiToken = !showApiToken }) {
                        Icon(
                            if (showApiToken) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = null,
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
            Text(
                stringResource(R.string.api_token_help),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text(
                stringResource(R.string.or_password_login),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = draft.username,
                onValueChange = { draft = draft.copy(username = it) },
                label = { Text(stringResource(R.string.username)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = draft.password,
                onValueChange = { draft = draft.copy(password = it) },
                label = { Text(stringResource(R.string.password)) },
                singleLine = true,
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { showPassword = !showPassword }) {
                        Icon(
                            if (showPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = null,
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )

            TextButton(onClick = { showAdvanced = !showAdvanced }) {
                Text(
                    if (showAdvanced) {
                        stringResource(R.string.hide_cloudflare)
                    } else {
                        stringResource(R.string.cloudflare_access)
                    },
                )
            }

            if (showAdvanced) {
                OutlinedTextField(
                    value = draft.accessClientId,
                    onValueChange = { draft = draft.copy(accessClientId = it) },
                    label = { Text(stringResource(R.string.access_client_id)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = draft.accessClientSecret,
                    onValueChange = { draft = draft.copy(accessClientSecret = it) },
                    label = { Text(stringResource(R.string.access_client_secret)) },
                    singleLine = true,
                    visualTransformation = if (showAccessSecret) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showAccessSecret = !showAccessSecret }) {
                            Icon(
                                if (showAccessSecret) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = null,
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                Text(
                    stringResource(R.string.service_token_note),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (needsAccess && !accessReady) {
                    Text(
                        stringResource(R.string.access_required),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            Spacer(Modifier.height(4.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = { onTest(draft) },
                    enabled = !state.onboardingBusy && readyToConnect,
                    modifier = Modifier.weight(1f),
                ) {
                    if (state.onboardingBusy) {
                        CircularProgressIndicator(modifier = Modifier.height(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.height(0.dp))
                    } else {
                        Text(stringResource(R.string.test_connection))
                    }
                }
                Button(
                    onClick = { onSave(draft) },
                    enabled = !state.onboardingBusy && readyToConnect,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.save_and_start))
                }
            }

            state.onboardingResult?.let { result ->
                val ok = state.onboardingOk
                Surface(
                    color = if (ok) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Text(
                        text = result,
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (ok) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }

            Text(
                stringResource(R.string.onboarding_tip),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
