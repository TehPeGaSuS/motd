package io.github.trevarj.motd.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.trevarj.motd.R
import io.github.trevarj.motd.ui.theme.MotdTheme

/** Test tag for the recoverable (tappable) history seam. */
const val CHAT_GAP_DIVIDER_TAG: String = "chat_gap_divider"

/** Test tag for the permanent seam left where the server no longer holds that history. */
const val CHAT_GAP_DIVIDER_UNRECOVERABLE_TAG: String = "chat_gap_divider_unrecoverable"

/**
 * Presentation state of one history seam. The caller owns which gap this is and whether a fill is
 * in flight; the composable only renders the state it is handed.
 */
sealed interface HistoryGapState {
    /** The gap can still be filled from the server; the seam is tappable. */
    data object Recoverable : HistoryGapState

    /** A fill for this gap is in flight: progress is shown and the seam stops accepting taps. */
    data object Loading : HistoryGapState

    /** The server has expired that history. Permanent, non-interactive, never dismissible. */
    data object Unrecoverable : HistoryGapState
}

/**
 * Full-width seam marking a hole in the timeline, rendered inline above the newest row on the far
 * side of the gap (like [NewMessagesDivider] and [DaySeparator], not as its own list item).
 *
 * Two shapes share one visual language: hairline rules flanking a centered low-emphasis label.
 * [HistoryGapState.Recoverable] reads and behaves as a button and invokes [onLoad];
 * [HistoryGapState.Unrecoverable] is a plain statement that the messages above it are gone from the
 * server, which is what makes the user's own older stored messages visible below it.
 */
@Composable
fun HistoryGapDivider(
    state: HistoryGapState,
    onLoad: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactive = state != HistoryGapState.Unrecoverable
    val label = stringResource(
        when (state) {
            HistoryGapState.Recoverable -> R.string.chat_history_gap_load
            HistoryGapState.Loading -> R.string.chat_history_gap_loading
            HistoryGapState.Unrecoverable -> R.string.chat_history_gap_unavailable
        },
    )
    val loadAction = stringResource(R.string.chat_history_gap_load_action)
    val busy = stringResource(R.string.chat_history_gap_loading)

    // The tag identifies the variant, so it is applied after the caller's modifier: E2E must be able
    // to tell a fillable seam from an expired one no matter who composes it.
    val tag = if (interactive) CHAT_GAP_DIVIDER_TAG else CHAT_GAP_DIVIDER_UNRECOVERABLE_TAG
    // Only the tappable variant reserves a 48 dp touch target; the permanent seam stays as slim as
    // the other dividers because nothing aims at it.
    val sizing = if (interactive) Modifier.heightIn(min = 48.dp) else Modifier
    val interaction = if (interactive) {
        Modifier
            .semantics {
                role = Role.Button
                contentDescription = label
                if (state == HistoryGapState.Loading) stateDescription = busy
            }
            // Disabled rather than absent while filling: the control keeps its identity for screen
            // readers but a second tap cannot queue another fetch.
            .clickable(
                enabled = state == HistoryGapState.Recoverable,
                onClickLabel = loadAction,
                onClick = onLoad,
            )
    } else {
        // Merged into a single non-clickable node; nothing here advertises an action.
        Modifier.semantics(mergeDescendants = true) { contentDescription = label }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .testTag(tag)
            .then(sizing)
            .then(interaction)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HorizontalDivider(
            modifier = Modifier.weight(1f).clearAndSetSemantics {},
            thickness = Dp.Hairline,
            color = MaterialTheme.colorScheme.outlineVariant,
        )
        if (state == HistoryGapState.Loading) {
            CircularProgressIndicator(
                modifier = Modifier.padding(start = 12.dp).size(12.dp),
                strokeWidth = 1.5.dp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        HorizontalDivider(
            modifier = Modifier.weight(1f).clearAndSetSemantics {},
            thickness = Dp.Hairline,
            color = MaterialTheme.colorScheme.outlineVariant,
        )
    }
}

@PreviewLightDark
@Composable
private fun HistoryGapDividerRecoverablePreview() {
    MotdTheme {
        HistoryGapDivider(state = HistoryGapState.Recoverable, onLoad = {})
    }
}

@PreviewLightDark
@Composable
private fun HistoryGapDividerLoadingPreview() {
    MotdTheme {
        HistoryGapDivider(state = HistoryGapState.Loading, onLoad = {})
    }
}

@PreviewLightDark
@Composable
private fun HistoryGapDividerUnrecoverablePreview() {
    MotdTheme {
        HistoryGapDivider(state = HistoryGapState.Unrecoverable, onLoad = {})
    }
}
