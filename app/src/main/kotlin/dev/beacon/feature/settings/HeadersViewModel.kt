package dev.beacon.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.beacon.core.data.CustomHeaders
import dev.beacon.core.data.SessionStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject

/**
 * M4 headers editor (agent.md "custom headers + proxy-cookie": the editor is
 * the simple form — a pasted `Cookie` header covers reverse-proxy flows; the
 * full WebView capture stays a follow-up).
 *
 * Rows live in [UiState.drafts] as a working copy; Save runs [HeadersEditorState.validate]
 * (pure, table-tested) and writes the whole map through [SessionStore.setCustomHeaders]
 * — one encrypted blob in SecureStore, no partial states on disk.
 */
@HiltViewModel
class HeadersViewModel @Inject constructor(
    private val session: SessionStore,
) : ViewModel() {

    data class Draft(
        val id: Long,
        val name: String = "",
        val value: String = "",
    )

    data class UiState(
        val drafts: List<Draft> = emptyList(),
        /** Row id → first failing rule's message. */
        val errors: Map<Long, String> = emptyMap(),
        val saved: Boolean = false,
    )

    private val nextId = AtomicLong(1L)

    private val _state = MutableStateFlow(loadInitial())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private fun loadInitial(): UiState {
        val drafts = session.customHeadersSnapshot()
            .map { (name, value) -> Draft(id = nextId.getAndIncrement(), name = name, value = value) }
        return UiState(drafts = drafts)
    }

    fun addDraft() = _state.update { it.copy(drafts = it.drafts + Draft(nextId.getAndIncrement()), errors = emptyMap(), saved = false) }

    fun removeDraft(id: Long) = _state.update { it.copy(drafts = it.drafts.filterNot { d -> d.id == id }, errors = it.errors - id, saved = false) }

    fun setName(id: Long, value: String) = _state.update { st ->
        st.copy(
            drafts = st.drafts.map { if (it.id == id) it.copy(name = value) else it },
            errors = st.errors - id,
            saved = false,
        )
    }

    fun setValue(id: Long, value: String) = _state.update { st ->
        st.copy(
            drafts = st.drafts.map { if (it.id == id) it.copy(value = value) else it },
            errors = st.errors - id,
            saved = false,
        )
    }

    fun save() {
        val drafts = _state.value.drafts
        val errors = HeadersEditorState.validate(drafts)
        if (errors.isNotEmpty()) {
            _state.update { it.copy(errors = errors) }
            return
        }
        viewModelScope.launch {
            session.setCustomHeaders(HeadersEditorState.toHeaders(drafts))
            _state.update { it.copy(saved = true) }
        }
    }
}

/**
 * Pure editor rules (JVM table-tested in HeadersEditorStateTest). Mirrors
 * CustomHeaders + Conduit's api_auth_interceptor.dart reserved-name check.
 */
object HeadersEditorState {

    fun validate(drafts: List<HeadersViewModel.Draft>): Map<Long, String> {
        val errors = LinkedHashMap<Long, String>()
        val seen = HashSet<String>()
        for (draft in drafts) {
            val message = when {
                draft.name.trim().isEmpty() -> "Required"
                !CustomHeaders.isValidName(draft.name.trim()) ->
                    "Letters, digits and - . _ ~ only"
                CustomHeaders.isReserved(draft.name.trim()) ->
                    "Authorization is managed by sign-in"
                !seen.add(draft.name.trim().lowercase()) -> "Already added"
                draft.value.trim().isEmpty() -> "Required"
                !CustomHeaders.isValidValue(draft.value.trim()) -> "No line breaks"
                else -> null
            }
            if (message != null) errors[draft.id] = message
        }
        return errors
    }

    /** Order preserved (the interceptor iterates the map in order). */
    fun toHeaders(drafts: List<HeadersViewModel.Draft>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (draft in drafts) {
            val name = draft.name.trim()
            if (name.isNotEmpty()) out[name] = draft.value.trim()
        }
        return out
    }
}
