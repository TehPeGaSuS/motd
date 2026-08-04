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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.trevarj.motd.R
import io.github.trevarj.motd.ui.theme.MotdTheme

/** Test tag for a seam history can still cross: loading, or a failed attempt offering its retry. */
const val CHAT_GAP_DIVIDER_TAG: String = "chat_gap_divider"

/** Test tag for the permanent seam left where the server no longer holds that history. */
const val CHAT_GAP_DIVIDER_UNRECOVERABLE_TAG: String = "chat_gap_divider_unrecoverable"

/**
 * Presentation state of one history seam. The caller owns which gap this is and what is happening to
 * it; the composable only renders the state it is handed.
 */
sealed interface HistoryGapState {
    /**
     * The ordinary state of a seam the user is looking at: history is being loaded across it.
     *
     * A seam behaves like the end of the list, so scrolling toward it loads more and shows a
     * spinner. There is deliberately no "tap to load" resting state — a recoverable seam is only
     * ever composed when the viewport has reached it, and reaching it is what loads it.
     */
    data object Loading : HistoryGapState

    /**
     * The last attempt to load across this seam did not work — a transport error, or no history
     * transport at all. This is the ONLY state that offers a tap, and the tap is a retry.
     */
    data object Failed : HistoryGapState

    /** The server has expired that history. Permanent, non-interactive, never dismissible. */
    data object Unrecoverable : HistoryGapState
}

/**
 * Full-width seam marking a hole in the timeline, rendered inline above the newest row on the far
 * side of the gap (like [NewMessagesDivider] and [DaySeparator], not as its own list item).
 *
 * Two shapes share one visual language: hairline rules flanking a centered low-emphasis label.
 * [HistoryGapState.Failed] reads and behaves as a button and invokes [onLoad];
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
            HistoryGapState.Failed -> R.string.chat_history_gap_failed
            HistoryGapState.Loading -> R.string.chat_history_gap_loading
            HistoryGapState.Unrecoverable -> R.string.chat_history_gap_unavailable
        },
    )
    val retryAction = stringResource(R.string.chat_history_gap_retry_action)

    // The tag identifies the variant, so it is applied after the caller's modifier: E2E must be able
    // to tell a seam history can still cross from an expired one no matter who composes it.
    val tag = if (interactive) CHAT_GAP_DIVIDER_TAG else CHAT_GAP_DIVIDER_UNRECOVERABLE_TAG
    // Only the RETRY reserves a 48 dp touch target. A loading seam is a progress statement, not a
    // control, so it stays as slim as the other dividers — as does the permanent one.
    val sizing = if (state == HistoryGapState.Failed) Modifier.heightIn(min = 48.dp) else Modifier
    val interaction = when (state) {
        // The one control the timeline still has, and it only exists because something broke.
        HistoryGapState.Failed -> Modifier
            .semantics {
                role = Role.Button
                contentDescription = label
            }
            .clickable(onClickLabel = retryAction, onClick = onLoad)
        // Merged into a single non-clickable node. Announcing a Button here would advertise an
        // action that does not exist: history is already loading, and a tap could only duplicate it.
        HistoryGapState.Loading,
        HistoryGapState.Unrecoverable,
        -> Modifier.semantics(mergeDescendants = true) { contentDescription = label }
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
private fun HistoryGapDividerFailedPreview() {
    MotdTheme {
        HistoryGapDivider(state = HistoryGapState.Failed, onLoad = {})
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
