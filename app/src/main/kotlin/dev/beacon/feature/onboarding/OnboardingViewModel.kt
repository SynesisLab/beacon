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
 * freely once connectivity returns .
 */
@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val api: OwuiApi,
    private val session: SessionStore,
) : ViewModel() {

    sealed interface Step {
        data object Url : Step
        data object Auth : Step
        /** M4 MFA: TOTP/recovery-code challenge (mfa.py:147 verify). */
        data object MfaVerify : Step
        /** M4 MFA: first-Time enrollment — QR/manual key + confirm (mfa.py:129-148). */
        data object MfaEnroll : Step
        /** M4 MFA: the one-time recovery codes issued at enroll (mfa.py:146). */
        data object MfaRecovery : Step
        data object Models : Step
    }

    data class UiState(
        val step: Step = Step.Url,
        val urlDraft: String = "",
        val probing: Boolean = false,
        /** True once GET /api/version succeeded for the urlDraft (one-page probe line, mockup 11). */
        val probed: Boolean = false,
        val error: String? = null,
        val authMode: AuthMethod = AuthMethod.ApiKey,
        val apiKeyDraft: String = "",
        val emailDraft: String = "",
        val passwordDraft: String = "",
        val connecting: Boolean = false,
        val models: List<OwuiModel> = emptyList(),
        val chosenModelId: String? = null,
        val finishing: Boolean = false,
        // MFA challenge state (sign-in = password → challenge → verify/enroll).
        val challengeToken: String? = null,
        val mfaCodeDraft: String = "",
        val mfaRecoveryMode: Boolean = false,
        val manualKey: String? = null,
        val qrCode: String? = null,
        val recoveryCodes: List<String> = emptyList(),
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
    fun setMfaCodeDraft(value: String) = mutate { copy(mfaCodeDraft = value, error = null) }
    fun setMfaRecoveryMode(on: Boolean) = mutate { copy(mfaRecoveryMode = on, error = null) }

    /** The recovery-codes card's Continue → done (model auto-chosen). */
    fun continueAfterRecoveryCodes() = finish()

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
                _state.value = _state.value.copy(probing = false, probed = true, step = Step.Auth)
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
                adoptUserName()
                val page = api.models()
                completeOnboarding(page.items)
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
                    result.next_step == "pending" -> {
                        _state.value = _state.value.copy(
                            connecting = false,
                            error = "Your account is pending activation by an admin.",
                        )
                        return@launch
                    }
                    // M4 MFA (mfa.py): the challenge token rides to verify/enroll.
                    result.next_step == "verify" -> {
                        _state.value = _state.value.copy(
                            connecting = false,
                            step = Step.MfaVerify,
                            challengeToken = result.challenge_token,
                            mfaCodeDraft = "",
                            mfaRecoveryMode = false,
                        )
                        return@launch
                    }
                    result.next_step == "enroll" -> {
                        _state.value = _state.value.copy(
                            connecting = false,
                            step = Step.MfaEnroll,
                            challengeToken = result.challenge_token,
                            manualKey = null,
                            qrCode = null,
                        )
                        fetchMfaSetup(url, result.challenge_token)
                        return@launch
                    }
                    result.next_step == "recover" -> {
                        _state.value = _state.value.copy(
                            connecting = false,
                            error = "Your account needs an MFA recovery token — " +
                                "regenerate it in your instance's admin panel, then finish the reset in the web UI once.",
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
                adoptUserName()
                val page = api.models()
                completeOnboarding(page.items)
            } catch (e: Exception) {
                _state.value = _state.value.copy(connecting = false, error = describe(e))
            }
        }
    }

    /** GET the QR/manual key for the enroll step (mfa.py:129-134). */
    private fun fetchMfaSetup(url: String, challengeToken: String?) {
        viewModelScope.launch {
            try {
                val setup = api.startMfaEnroll(url, challengeToken.orEmpty())
                _state.value = _state.value.copy(manualKey = setup.manual_key, qrCode = setup.qr_code)
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = describe(e))
            }
        }
    }

    /** The verify/enroll-confirm steps share the success pipeline. */
    private fun adoptMfaResult(result: dev.beacon.core.api.dto.SignInData) {
        viewModelScope.launch {
            _state.value = _state.value.copy(connecting = true, error = null)
            try {
                if (result.token == null) {
                    _state.value = _state.value.copy(connecting = false, error = "Multi-factor step failed (no token)")
                    return@launch
                }
                session.update { it.copy(signedInEmail = result.email ?: it.signedInEmail) }
                session.adoptCredentials(AuthMethod.Password, result.token)
                adoptUserName()
                val page = api.models()
                if (!result.recovery_codes.isNullOrEmpty()) {
                    // Enroll path: show the one-time codes BEFORE leaving onboarding.
                    _state.value = _state.value.copy(
                        connecting = false,
                        step = Step.MfaRecovery,
                        models = page.items,
                        chosenModelId = page.items.firstOrNull()?.id,
                        recoveryCodes = result.recovery_codes,
                    )
                } else {
                    completeOnboarding(page.items)
                }
            } catch (e: Exception) {
                _state.value = _state.value.copy(connecting = false, error = describe(e))
            }
        }
    }

    fun submitMfaCode() {
        val code = _state.value.mfaCodeDraft.trim()
        val challenge = _state.value.challengeToken
        if (code.isEmpty()) {
            mutate { copy(error = "Enter the code from your authenticator app") }
            return
        }
        if (challenge == null) {
            mutate { copy(step = Step.Auth, error = "Challenge expired — sign in again") }
            return
        }
        val url = normalizeInstanceUrl(_state.value.urlDraft)
        viewModelScope.launch {
            _state.value = _state.value.copy(connecting = true, error = null)
            try {
                val result = api.verifyMfa(url, challenge, code, recovery = _state.value.mfaRecoveryMode)
                _state.value = _state.value.copy(connecting = false)
                adoptMfaResult(result)
            } catch (e: Exception) {
                _state.value = _state.value.copy(connecting = false, error = describe(e))
            }
        }
    }

    fun submitMfaEnrollConfirm() {
        val code = _state.value.mfaCodeDraft.trim()
        val challenge = _state.value.challengeToken
        if (code.isEmpty()) {
            mutate { copy(error = "Enter the code your authenticator shows for this key") }
            return
        }
        if (challenge == null) {
            mutate { copy(step = Step.Auth, error = "Challenge expired — sign in again") }
            return
        }
        val url = normalizeInstanceUrl(_state.value.urlDraft)
        viewModelScope.launch {
            _state.value = _state.value.copy(connecting = true, error = null)
            try {
                val result = api.confirmMfaEnroll(url, challenge, code)
                _state.value = _state.value.copy(connecting = false)
                adoptMfaResult(result)
            } catch (e: Exception) {
                _state.value = _state.value.copy(connecting = false, error = describe(e))
            }
        }
    }

    /** Step 3: pick the starting model — the M0 "model list rendered" AC. */
    fun chooseModel(modelId: String) = mutate { copy(chosenModelId = modelId) }

    /**
     * One-page onboarding (mockup 11): the model pick is dropped — the FIRST
     * listed model becomes the default (change later via the top chip).
     * Falls back to the Models step only when the server lists none.
     */
    private fun completeOnboarding(models: List<OwuiModel>) {
        val chosen = models.firstOrNull()?.id
        if (chosen != null) {
            _state.value = _state.value.copy(step = Step.Models, models = models, chosenModelId = chosen)
            finish()
        } else {
            _state.value = _state.value.copy(step = Step.Models, models = models)
        }
    }

    fun finish() {
        val chosen = _state.value.chosenModelId ?: return
        viewModelScope.launch {
            mutate { copy(finishing = true, error = null) }
            session.update { it.copy(selectedModelId = chosen) }
            _state.value = _state.value.copy(finishing = false)
            onFinished()
        }
    }

    /** Greeting name (GET /api/v1/auths/); failure is non-fatal. */
    private suspend fun adoptUserName() {
        runCatching {
            val user = api.sessionUser()
            session.update {
                it.copy(
                    userName = user.name,
                    signedInEmail = user.email ?: it.signedInEmail,
                    userRole = user.role,
                )
            }
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