package io.github.trevarj.motd.ui.settings.labs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.trevarj.motd.agentwire.AgentwirePrefs
import io.github.trevarj.motd.gesture.GesturePrefs
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Lab toggles; each lab keeps its own backup-excluded store, so this is just a shared surface. */
data class LabsUiState(
    val gesturesEnabled: Boolean = false,
    val agentwireEnabled: Boolean = false,
)

@HiltViewModel
class LabsViewModel @Inject constructor(
    private val gesturePrefs: GesturePrefs,
    private val agentwirePrefs: AgentwirePrefs,
) : ViewModel() {
    val state: StateFlow<LabsUiState> = combine(
        gesturePrefs.enabled,
        agentwirePrefs.enabled,
    ) { gestures, agentwire -> LabsUiState(gesturesEnabled = gestures, agentwireEnabled = agentwire) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LabsUiState())

    fun setGesturesEnabled(enabled: Boolean) {
        viewModelScope.launch { gesturePrefs.setEnabled(enabled) }
    }

    fun setAgentwireEnabled(enabled: Boolean) {
        viewModelScope.launch { agentwirePrefs.setEnabled(enabled) }
    }
}
