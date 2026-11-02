package dev.beacon

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.beacon.core.data.ServerSettings
import dev.beacon.core.data.SessionStore
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/** Root-level view state: decides start destination (onboarding vs home). */
@HiltViewModel
class MainViewModel @javax.inject.Inject constructor(
    session: SessionStore,
) : ViewModel() {
    val settings: StateFlow<ServerSettings?> = session.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
}