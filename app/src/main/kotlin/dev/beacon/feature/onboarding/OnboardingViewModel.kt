package dev.beacon.feature.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.beacon.core.api.OwuiApi
import dev.beacon.core.api.OwuiApiException
import dev.beacon.core.api.dto.OwuiModel
import dev.beacon.core.data.AuthMethod
import dev.beacon.core.data.SessionStore
import dev.beacon.core.data.normalizeInstanceUrl
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.IOException
import javax.inject.Inject

/**
 * M0 onboarding: instance URL → auth (API key or password) → model pick.
 * All network probes fail soft: they surface text, never crash, and reconnect
 * freely once connectivity returns (agent.md §15 M0 AC).
 */
@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val api: OwuiApi,
    private val session: SessionStore,
) : ViewModel() {

    sealed interface Step {
        data object Url : Step
        data object Auth : Step
        data object Models : Step
    }

    data class UiState(
        val step: Step = Step.Url,
        val urlDraft: String = "",
        val probing: Boolean = false,
        val error: String? = null,
        val authMode: AuthMethod = AuthMethod.ApiKey,
        val apiKeyDraft: String = "",
        val emailDraft: String = "",
        val passwordDraft: String = "",
        val connecting: Boolean = false,
        val models: List<OwuiModel> = emptyList(),
        val chosenModelId: String? = null,
        val finishing: Boolean = false,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    /** Bridge set from the nav host when onboarding completes. */
    var onFinished: () -> Unit = {}

    fun setUrlDraft(value: String) = mutate { copy(urlDraft = value, error = null) }
    fun setApiKeyDraft(value: String) = mutate { copy(apiKeyDraft = value, error = null) }
    fun setEmailDraft(value: String) = mutate { copy(emailDraft = value, error = null) }
    fun setPasswordDraft(value: String) = mutate { copy(passwordDraft = value, error = null) }
    fun setAuthMode(mode: AuthMethod) = mutate { copy(authMode = mode, error = null) }

    /** Step 1: resolve + prove the instance lives at the given URL. */
    fun probeServer() {
        val url = normalizeInstanceUrl(_state.value.urlDraft)
        if (url.isBlank()) {
            mutate { copy(error = "Enter your instance URL, e.g. https://ai.example.com") }
            return
        }

        viewModelScope.launch {
            _state.value = _state.value.copy(probing = true, error = null, urlDraft = url)
            try {
                val version = api.probeVersion(url)
                session.update {
                    it.copy(serverUrl = url, serverVersion = version.version ?: it.serverVersion)
                }
                _state.value = _state.value.copy(probing = false, step = Step.Auth)
            } catch (e: Exception) {
                _state.value = _state.value.copy(probing = false, error = describe(e))
            }
        }
    }

    /** Step 2a: API-key auth — store the key in Keystore, then load the model list. */
    fun submitApiKey() {
        val key = _state.value.apiKeyDraft.trim()
        if (key.isEmpty()) {
            mutate { copy(error = "Paste the API key from Settings → Account on your instance") }
            return
        }

        viewModelScope.launch {
            _state.value = _state.value.copy(connecting = true, error = null)
            try {
                session.adoptCredentials(AuthMethod.ApiKey, key)
                val page = api.models()
                _state.value = UiState(step = Step.Models, models = page.items)
            } catch (e: Exception) {
                _state.value = _state.value.copy(connecting = false, error = describe(e))
            }
        }
    }

    /** Step 2b: password auth → JWT in Keystore → model list. */
    fun submitPassword() {
        val email = _state.value.emailDraft.trim()
        val password = _state.value.passwordDraft
        if (email.isEmpty() || password.isEmpty()) {
            mutate { copy(error = "Enter your email and password") }
            return
        }

        val url = normalizeInstanceUrl(_state.value.urlDraft)
        viewModelScope.launch {
            _state.value = _state.value.copy(connecting = true, error = null)
            try {
                val result = api.signIn(url, email, password)
                when {
                    result.isMfaChallenge -> {
                        _state.value = _state.value.copy(
                            connecting = false,
                            error = "This account requires multi-factor sign-in — not supported yet in Beacon (M4).",
                        )
                        return@launch
                    }
                    result.isPendingUser -> {
                        _state.value = _state.value.copy(
                            connecting = false,
                            error = "Your account is pending activation by an admin.",
                        )
                        return@launch
                    }
                    result.token == null -> {
                        _state.value = _state.value.copy(connecting = false, error = "Sign-in failed (no token)")
                        return@launch
                    }
                    else -> {}
                }

                session.update { it.copy(signedInEmail = result.email ?: email) }
                session.adoptCredentials(AuthMethod.Password, result.token)
                val page = api.models()
                _state.value = UiState(step = Step.Models, models = page.items)
            } catch (e: Exception) {
                _state.value = _state.value.copy(connecting = false, error = describe(e))
            }
        }
    }

    /** Step 3: pick the starting model — the M0 "model list rendered" AC. */
    fun chooseModel(modelId: String) = mutate { copy(chosenModelId = modelId) }

    fun finish() {
        val chosen = _state.value.chosenModelId ?: return
        viewModelScope.launch {
            mutate { copy(finishing = true, error = null) }
            session.update { it.copy(selectedModelId = chosen) }
            _state.value = _state.value.copy(finishing = false)
            onFinished()
        }
    }

    private fun mutate(reducer: UiState.() -> UiState) {
        _state.value = _state.value.reducer()
    }

    private fun describe(e: Exception): String = when (e) {
        is OwuiApiException -> when (e.code) {
            0 -> e.message ?: "Not connected"
            401, 403 -> "The server rejected these credentials (${e.code})."
            else -> "Server error ${e.code}: ${e.bodyExcerpt?.take(160) ?: "no details"}"
        }
        is IOException -> "Couldn't reach the server — check the URL and your connection."
        else -> e.message ?: e.javaClass.simpleName
    }
}