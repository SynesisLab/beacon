package dev.beacon

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.beacon.core.api.OwuiApi
import dev.beacon.core.data.ServerSettings
import dev.beacon.core.data.SessionStore
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Root-level view state: decides start destination (onboarding vs home). */
@HiltViewModel
class MainViewModel @Inject constructor(
    session: SessionStore,
    api: OwuiApi,
) : ViewModel() {
    val settings: StateFlow<ServerSettings?> = session.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init {
        // The displayed name/role/email are NEVER hardcoded: refresh them from
        // the OWUI system (GET /api/v1/auths/, auths.py:178) on every boot, so a
        // server-side rename shows up here. Boot paint still uses the cached
        // value until this lands (fail-soft).
        viewModelScope.launch {
            runCatching {
                val user = api.sessionUser()
                session.update {
                    it.copy(
                        userName = user.name ?: it.userName,
                        userRole = user.role ?: it.userRole,
                        signedInEmail = user.email ?: it.signedInEmail,
                    )
                }
            }
        }
    }
}