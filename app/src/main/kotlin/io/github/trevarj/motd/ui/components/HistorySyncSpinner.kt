package io.github.trevarj.motd.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * The small indeterminate in-progress cue, shared by chat-list rows, the chat title bar, and the
 * chat-list title's connectivity indicator so they all report background work with the same mark.
 *
 * Deliberately unannounced beyond its content description: rows churn during a resync pass and a
 * chat's own sync restarts on every reconnect, so a live region here would spam TalkBack. Callers
 * supply their own test tag through [modifier].
 */
@Composable
fun HistorySyncSpinner(contentDescription: String, modifier: Modifier = Modifier) {
    CircularProgressIndicator(
        strokeWidth = 2.dp,
        modifier = modifier
            .size(12.dp)
            .semantics { this.contentDescription = contentDescription },
    )
}
