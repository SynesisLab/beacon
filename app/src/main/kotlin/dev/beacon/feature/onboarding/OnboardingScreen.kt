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
        // The model pick is dropped (mockup 11): success auto-finishes with
        // the first model; Models only renders when the server lists none.
        OnboardingViewModel.Step.Models -> ModelsStep(state, actions, modifier)
        else -> OnePage(state, actions, modifier)
    }
}

/**
 * Mockup 11 — ONE page: critter + "One page. Three fields." + Instance
 * (with the probe line) + auth fields + the MFA block INLINE when the
 * challenge lands + one Sign in button.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OnePage(
    state: OnboardingViewModel.UiState,
    actions: OnboardingActions,
    modifier: Modifier = Modifier,
) {
    val inMfa = state.step is OnboardingViewModel.Step.MfaVerify ||
        state.step is OnboardingViewModel.Step.MfaEnroll ||
        state.step is OnboardingViewModel.Step.MfaRecovery
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .semantics { testTag = if (state.step == OnboardingViewModel.Step.Url) "onboarding_url" else "onboarding_auth" }
            .padding(horizontal = Paddings.screenEdge.dp, vertical = 22.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        dev.beacon.feature.chat.LandingCritter(size = 56.dp)
        Text(
            "One page. Three fields.",
            style = MaterialTheme.typography.headlineSmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Serif),
        )

        // ── Instance ────────────────────────────────────────────────────
        OutlinedTextField(
            value = state.urlDraft,
            onValueChange = actions.setUrlDraft,
            label = { Text("Instance") },
            placeholder = { Text("ai.example.com") },
            singleLine = true,
            isError = state.error != null && state.step == OnboardingViewModel.Step.Url,
            shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
        )
        if (state.probing) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                Text("Probing…", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else if (state.probed) {
            Text(
                "✓ detected — ready",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        // ── Auth ────────────────────────────────────────────────────────
        if (!inMfa || state.step is OnboardingViewModel.Step.MfaVerify || state.step is OnboardingViewModel.Step.MfaEnroll) {
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
                AuthMethod.ApiKey -> OutlinedTextField(
                    value = state.apiKeyDraft,
                    onValueChange = actions.setApiKeyDraft,
                    label = { Text("API key") },
                    placeholder = { Text("sk-…") },
                    singleLine = true,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                AuthMethod.Password, AuthMethod.None -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = state.emailDraft,
                        onValueChange = actions.setEmailDraft,
                        label = { Text("Email") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().contentType(ContentType.EmailAddress),
                    )
                    OutlinedTextField(
                        value = state.passwordDraft,
                        onValueChange = actions.setPasswordDraft,
                        label = { Text("Password") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().contentType(ContentType.Password),
                    )
                }
            }
        }

        // ── MFA inline (appears only when the challenge lands) ──────────
        when (state.step) {
            OnboardingViewModel.Step.MfaVerify -> {
                Text(
                    "Two-factor — appears only when the challenge lands",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                OutlinedTextField(
                    value = state.mfaCodeDraft,
                    onValueChange = actions.setMfaCodeDraft,
                    label = { Text(if (state.mfaRecoveryMode) "Recovery code" else "Code") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = if (state.mfaRecoveryMode) KeyboardType.Password else KeyboardType.NumberPassword,
                    ),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                androidx.compose.material3.TextButton(onClick = { actions.setMfaRecoveryMode(!state.mfaRecoveryMode) }) {
                    Text(if (state.mfaRecoveryMode) "Use the authenticator app instead" else "Use a recovery code instead")
                }
            }
            OnboardingViewModel.Step.MfaEnroll -> {
                Text(
                    "Two-factor — set up now",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                val qr = state.qrCode
                if (qr != null) {
                    androidx.compose.ui.viewinterop.AndroidView(
                        modifier = Modifier.fillMaxWidth().height(200.dp),
                        factory = { ctx ->
                            android.webkit.WebView(ctx).apply { settings.javaScriptEnabled = false }
                        },
                        update = { view -> runCatching { view.loadData(qr, "image/svg+xml", "utf-8") } },
                    )
                }
                state.manualKey?.let { key ->
                    Text(
                        "Manual key: $key",
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    )
                }
                OutlinedTextField(
                    value = state.mfaCodeDraft,
                    onValueChange = actions.setMfaCodeDraft,
                    label = { Text("6-digit code") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            OnboardingViewModel.Step.MfaRecovery -> {
                Text("Save your recovery codes", style = MaterialTheme.typography.titleMedium)
                androidx.compose.material3.Surface(
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        state.recoveryCodes.forEach { code ->
                            Text(code, style = MaterialTheme.typography.bodyMedium, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                        }
                    }
                }
            }
            else -> Unit
        }

        ErrorText(state.error)

        // ── The one button ──────────────────────────────────────────────
        Button(
            onClick = {
                when (state.step) {
                    OnboardingViewModel.Step.Url -> actions.probeServer()
                    OnboardingViewModel.Step.Auth ->
                        if (state.authMode == AuthMethod.ApiKey) actions.submitApiKey() else actions.submitPassword()
                    OnboardingViewModel.Step.MfaVerify -> actions.submitMfaCode()
                    OnboardingViewModel.Step.MfaEnroll -> actions.submitMfaEnrollConfirm()
                    OnboardingViewModel.Step.MfaRecovery -> actions.continueAfterRecoveryCodes()
                    OnboardingViewModel.Step.Models -> actions.finish()
                }
            },
            enabled = !state.connecting && !state.probing &&
                (state.step != OnboardingViewModel.Step.Url || state.urlDraft.isNotBlank()),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(999.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                when (state.step) {
                    OnboardingViewModel.Step.Url -> "Probe + continue"
                    OnboardingViewModel.Step.MfaVerify -> "Verify"
                    OnboardingViewModel.Step.MfaEnroll -> "Confirm"
                    OnboardingViewModel.Step.MfaRecovery -> "Continue"
                    else -> "Sign in"
                },
                style = MaterialTheme.typography.labelLarge,
            )
        }
        if (state.connecting) {
            CircularProgressIndicator(modifier = Modifier.size(28.dp))
        }
        Text(
            "2FA enroll is deferred — a one-time nudge appears on the home screen.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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