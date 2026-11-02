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
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        Text("Beacon", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Connect to your own Open WebUI instance. All intelligence stays on your server — Beacon is a client.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = state.urlDraft,
            onValueChange = actions.setUrlDraft,
            label = { Text("Instance URL") },
            placeholder = { Text("https://ai.example.com") },
            singleLine = true,
            isError = state.error != null,
            modifier = Modifier.fillMaxWidth(),
        )
        ErrorText(state.error)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = actions.probeServer,
                enabled = !state.probing && state.urlDraft.isNotBlank(),
            ) { Text("Continue") }
            if (state.probing) {
                CircularProgressIndicator(modifier = Modifier.size(40.dp))
            }
        }
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