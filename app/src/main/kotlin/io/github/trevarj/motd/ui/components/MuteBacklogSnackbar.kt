package io.github.trevarj.motd.ui.components

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.res.stringResource
import io.github.trevarj.motd.R
import kotlinx.coroutines.flow.Flow

/**
 * Unmuting marks the backlog that arrived while muted as read (see `BufferDao.setMuted`): a
 * deliberate choice, but a lossy one, so every screen that can unmute pairs its Scaffold snackbar
 * host with this effect to announce the dismissal and offer a way back.
 *
 * [suppressions] carries whatever the screen's ViewModel needs to reverse the advance, and is passed
 * back to [onUndo] only when the user takes the snackbar action.
 */
@Composable
fun <T> MuteBacklogUndoEffect(
    suppressions: Flow<T>,
    hostState: SnackbarHostState,
    onUndo: (T) -> Unit,
) {
    val message = stringResource(R.string.mute_backlog_dismissed)
    val undoLabel = stringResource(R.string.mute_backlog_undo)
    // The undo target can change while the snackbar is up; always call the latest lambda.
    val currentOnUndo by rememberUpdatedState(onUndo)
    LaunchedEffect(suppressions, hostState) {
        suppressions.collect { suppression ->
            val result = hostState.showSnackbar(
                message = message,
                actionLabel = undoLabel,
                duration = SnackbarDuration.Long,
            )
            if (result == SnackbarResult.ActionPerformed) currentOnUndo(suppression)
        }
    }
}
