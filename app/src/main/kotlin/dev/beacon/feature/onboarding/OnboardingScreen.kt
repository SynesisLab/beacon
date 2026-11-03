package dev.beacon.feature.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.autofill.contentType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.beacon.core.data.AuthMethod
import dev.beacon.ui.theme.Paddings

/** State+callbacks hoisted for screenshot determinism (agent.md §16.4). */
data class OnboardingActions(
    val setUrlDraft: (String) -> Unit,
    val probeServer: () -> Unit,
    val setAuthMode: (AuthMethod) -> Unit,
    val setApiKeyDraft: (String) -> Unit,
    val submitApiKey: () -> Unit,
    val setEmailDraft: (String) -> Unit,
    val setPasswordDraft: (String) -> Unit,
    val submitPassword: () -> Unit,
    val setMfaCodeDraft: (String) -> Unit,
    val setMfaRecoveryMode: (Boolean) -> Unit,
    val submitMfaCode: () -> Unit,
    val submitMfaEnrollConfirm: () -> Unit,
    val continueAfterRecoveryCodes: () -> Unit,
    val chooseModel: (String) -> Unit,
    val finish: () -> Unit,
)

@Composable
fun OnboardingScreen(
    state: OnboardingViewModel.UiState,
    actions: OnboardingActions,
    modifier: Modifier = Modifier,
) {
    // Password-manager save hook (agent.md §9.3-parity; ADR-0011): when the user
    // finished password sign-in (step transitioned to Models with a password present),
    // commit the Android autofill save — Google Password Manager prompts to save.
    val context = LocalContext.current
    LaunchedEffect(state.step) {
        if (state.step == OnboardingViewModel.Step.Models && state.passwordDraft.isNotEmpty()) {
            try {
                context.getSystemService(
                    android.view.autofill.AutofillManager::class.java,
                )?.commit()
            } catch (_: Exception) {
                // Some OEMs lack the autofill service — never block sign-in on it.
            }
        }
    }

    when (state.step) {
        OnboardingViewModel.Step.Url -> UrlStep(state, actions, modifier)
        OnboardingViewModel.Step.Auth -> AuthStep(state, actions, modifier)
        OnboardingViewModel.Step.MfaVerify -> MfaVerifyStep(state, actions, modifier)
        OnboardingViewModel.Step.MfaEnroll -> MfaEnrollStep(state, actions, modifier)
        OnboardingViewModel.Step.MfaRecovery -> MfaRecoveryStep(state, actions, modifier)
        OnboardingViewModel.Step.Models -> ModelsStep(state, actions, modifier)
    }
}

@Composable
private fun UrlStep(state: OnboardingViewModel.UiState, actions: OnboardingActions, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .semantics { testTag = "onboarding_url" }
            .padding(horizontal = Paddings.screenEdge.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        androidx.compose.foundation.layout.Spacer(Modifier.fillMaxWidth().height(40.dp))
        Text("Beacon", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Connect to your own Open WebUI instance. All intelligence stays on your server — Beacon is a client.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        androidx.compose.foundation.layout.Spacer(Modifier.fillMaxWidth().height(8.dp))
        OutlinedTextField(
            value = state.urlDraft,
            onValueChange = actions.setUrlDraft,
            label = { Text("Instance URL") },
            placeholder = { Text("https://ai.example.com") },
            singleLine = true,
            isError = state.error != null,
            shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth(),
        )
        ErrorText(state.error)
        Button(
            onClick = actions.probeServer,
            enabled = !state.probing && state.urlDraft.isNotBlank(),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Continue", style = MaterialTheme.typography.labelLarge)
        }
        if (state.probing) {
            androidx.compose.foundation.layout.Spacer(Modifier.height(6.dp))
            CircularProgressIndicator(modifier = Modifier.size(28.dp))
        }
        androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AuthStep(state: OnboardingViewModel.UiState, actions: OnboardingActions, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .semantics { testTag = "onboarding_auth" }
            .padding(horizontal = Paddings.screenEdge.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Connect", style = MaterialTheme.typography.headlineSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = state.authMode == AuthMethod.ApiKey,
                onClick = { actions.setAuthMode(AuthMethod.ApiKey) },
                label = { Text("API key") },
            )
            FilterChip(
                selected = state.authMode == AuthMethod.Password,
                onClick = { actions.setAuthMode(AuthMethod.Password) },
                label = { Text("Email + password") },
            )
        }

        when (state.authMode) {
            AuthMethod.ApiKey -> {
                OutlinedTextField(
                    value = state.apiKeyDraft,
                    onValueChange = actions.setApiKeyDraft,
                    label = { Text("API key") },
                    placeholder = { Text("sk-…") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Settings → Account → API keys on your instance. Stored in Android Keystore, never logged.",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AuthMethod.Password, AuthMethod.None -> {
                OutlinedTextField(
                    value = state.emailDraft,
                    onValueChange = actions.setEmailDraft,
                    label = { Text("Email") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier
                        .fillMaxWidth()
                        .contentType(ContentType.EmailAddress),
                )
                OutlinedTextField(
                    value = state.passwordDraft,
                    onValueChange = actions.setPasswordDraft,
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier
                        .fillMaxWidth()
                        .contentType(ContentType.Password),
                )
            }
        }

        ErrorText(state.error)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = if (state.authMode == AuthMethod.ApiKey) actions.submitApiKey else actions.submitPassword,
                enabled = !state.connecting,
            ) { Text("Connect") }
            if (state.connecting) {
                CircularProgressIndicator(modifier = Modifier.size(40.dp))
            }
        }
    }
}

/** MFA verify (mfa.py:147): the TOTP (or recovery code) entry after a
 *  password sign-in that returned {next_step: 'verify'}. */
@Composable
private fun MfaVerifyStep(state: OnboardingViewModel.UiState, actions: OnboardingActions, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .semantics { testTag = "onboarding_mfa_verify" }
            .padding(horizontal = Paddings.screenEdge.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        androidx.compose.foundation.layout.Spacer(Modifier.fillMaxWidth().height(40.dp))
        Text("Two-factor sign-in", style = MaterialTheme.typography.headlineSmall)
        Text(
            if (state.mfaRecoveryMode) {
                "Enter one of your one-time recovery codes."
            } else {
                "Enter the code from your authenticator app."
            },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = state.mfaCodeDraft,
            onValueChange = actions.setMfaCodeDraft,
            label = { Text(if (state.mfaRecoveryMode) "Recovery code" else "Code") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = if (state.mfaRecoveryMode) KeyboardType.Password else KeyboardType.NumberPassword,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        ErrorText(state.error)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = actions.submitMfaCode, enabled = !state.connecting) {
                Text("Verify", style = MaterialTheme.typography.labelLarge)
            }
            if (state.connecting) {
                CircularProgressIndicator(modifier = Modifier.size(28.dp))
            }
        }
        androidx.compose.material3.TextButton(onClick = { actions.setMfaRecoveryMode(!state.mfaRecoveryMode) }) {
            Text(if (state.mfaRecoveryMode) "Use the authenticator app instead" else "Use a recovery code instead")
        }
        androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
    }
}

/** First-time enrollment (mfa.py:129-148): QR/manual key + the confirm code.
 *  The qr_code payload is an svg+xml base64 data URI (utils/mfa.py:259-266) —
 *  painted by a tiny JS-off local WebView; the manual key is always shown too. */
@Composable
private fun MfaEnrollStep(state: OnboardingViewModel.UiState, actions: OnboardingActions, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .semantics { testTag = "onboarding_mfa_enroll" }
            .padding(horizontal = Paddings.screenEdge.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Set up two-factor sign-in", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Scan the code with an authenticator app (or paste the key), then enter the code it shows.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val qr = state.qrCode
        if (qr != null) {
            androidx.compose.ui.viewinterop.AndroidView(
                modifier = Modifier.fillMaxWidth().height(230.dp),
                factory = { ctx ->
                    android.webkit.WebView(ctx).apply {
                        settings.javaScriptEnabled = false // data: SVG never runs code (§11)
                    }
                },
                update = { view -> runCatching { view.loadData(qr, "image/svg+xml", "utf-8") } },
            )
        }
        state.manualKey?.let { key ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Manual key", style = MaterialTheme.typography.labelLarge)
                    Text(
                        key,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        maxLines = 2,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }
            }
        }
        OutlinedTextField(
            value = state.mfaCodeDraft,
            onValueChange = actions.setMfaCodeDraft,
            label = { Text("6-digit code") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier = Modifier.fillMaxWidth(),
        )
        ErrorText(state.error)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = actions.submitMfaEnrollConfirm, enabled = !state.connecting) {
                Text("Confirm", style = MaterialTheme.typography.labelLarge)
            }
            if (state.connecting) {
                CircularProgressIndicator(modifier = Modifier.size(28.dp))
            }
        }
    }
}

/** The one-time recovery codes issued at enroll (MfaEnrollmentResponse). */
@Composable
private fun MfaRecoveryStep(state: OnboardingViewModel.UiState, actions: OnboardingActions, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .semantics { testTag = "onboarding_mfa_recovery" }
            .padding(horizontal = Paddings.screenEdge.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Save your recovery codes", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Each code works once in place of the authenticator code. Keep them somewhere safe.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        androidx.compose.material3.Surface(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                state.recoveryCodes.forEach { code ->
                    Text(
                        code,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    )
                }
            }
        }
        Button(
            onClick = actions.continueAfterRecoveryCodes,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Continue") }
    }
}

@Composable
private fun ModelsStep(state: OnboardingViewModel.UiState, actions: OnboardingActions, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .semantics { testTag = "onboarding_models" }
            .padding(horizontal = Paddings.screenEdge.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Pick a model", style = MaterialTheme.typography.headlineSmall)
        if (state.models.isEmpty()) {
            Text(
                "No models are exposed by this server yet.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
            items(state.models, key = { it.id }) { model ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                ) {
                    RadioButton(
                        selected = state.chosenModelId == model.id,
                        onClick = { actions.chooseModel(model.id) },
                    )
                    Spacer(Modifier.padding(4.dp))
                    Text(model.name, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
        Button(
            onClick = actions.finish,
            enabled = state.chosenModelId != null && !state.finishing,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Start using Beacon") }
    }
}

@Composable
private fun ErrorText(message: String?) {
    if (message != null) {
        Text(
            message,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}