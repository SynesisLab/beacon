package dev.beacon.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.beacon.core.data.AuthMethod
import dev.beacon.core.data.SessionStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
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
        /** Sidebar NAV rows the user toggled off (Settings → Sidebar). */
        val hiddenNavRows: Set<String> = emptySet(),
        /** Role badge on the account row (sessionUser().role, cached). */
        val userRole: String? = null,
        /** App lock (biometric/device-credential gate at app open). */
        val appLock: Boolean = false,
    )

    // Headers live in SecureStore (Keystore), not the settings DataStore — the
    // two flows combine for display only (names/count never enter settings.json).
    val state: StateFlow<UiState> = combine(session.settings, session.customHeaders) { settings, headers ->
        UiState(
            serverUrl = settings.serverUrl,
            serverVersion = settings.serverVersion,
            authMethod = settings.authMethod,
            customHeaders = headers,
            trustUserCa = settings.trustUserCa,
            signedInEmail = settings.signedInEmail,
            themeMode = settings.themeMode,
            hiddenNavRows = settings.hiddenNavRowsSet,
            userRole = settings.userRole,
            appLock = settings.appLock,
        )
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    /** Force light/dark or follow the system (applies instantly via the theme collect). */
    fun setThemeMode(mode: dev.beacon.core.data.ThemeMode) {
        viewModelScope.launch {
            session.update { it.copy(themeMode = mode) }
        }
    }

    /** Settings → App lock: the credential gate applies on the next app open. */
    fun setAppLock(enabled: Boolean) {
        viewModelScope.launch {
            session.update { it.copy(appLock = enabled) }
        }
    }

    /** Sidebar row visibility (user request 2026-11-06) — [rowKey] per SidebarState NAV keys. */
    fun setNavRowHidden(rowKey: String, hidden: Boolean) {
        viewModelScope.launch {
            session.update { current ->
                val next = current.hiddenNavRowsSet.let { set ->
                    if (hidden) set + rowKey else set - rowKey
                }
                current.copy(hiddenNavRows = next.sorted().ifEmpty { null } ?: next.toList())
            }
        }
    }

    /** Clears Keystore secrets + settings; the nav host returns to onboarding. */
    fun signOut() {
        viewModelScope.launch {
            session.signOut()
        }
    }
}