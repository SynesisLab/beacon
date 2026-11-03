package dev.beacon.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.beacon.core.data.AuthMethod
import dev.beacon.core.data.SessionStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val session: SessionStore,
) : ViewModel() {

    data class UiState(
        val serverUrl: String = "",
        val serverVersion: String? = null,
        val authMethod: AuthMethod = AuthMethod.None,
        val customHeaders: Map<String, String> = emptyMap(),
        val trustUserCa: Boolean = false,
        val signedInEmail: String? = null,
        val signingOut: Boolean = false,
        val themeMode: dev.beacon.core.data.ThemeMode = dev.beacon.core.data.ThemeMode.System,
    )

    val state: StateFlow<UiState> = session.settings
        .map {
            UiState(
                serverUrl = it.serverUrl,
                serverVersion = it.serverVersion,
                authMethod = it.authMethod,
                customHeaders = it.customHeaders,
                trustUserCa = it.trustUserCa,
                signedInEmail = it.signedInEmail,
                themeMode = it.themeMode,
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    /** Force light/dark or follow the system (applies instantly via the theme collect). */
    fun setThemeMode(mode: dev.beacon.core.data.ThemeMode) {
        viewModelScope.launch {
            session.update { it.copy(themeMode = mode) }
        }
    }

    /** Clears Keystore secrets + settings; the nav host returns to onboarding. */
    fun signOut() {
        viewModelScope.launch {
            session.signOut()
        }
    }
}