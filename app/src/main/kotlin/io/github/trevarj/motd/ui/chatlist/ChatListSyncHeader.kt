package io.github.trevarj.motd.ui.chatlist

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import io.github.trevarj.motd.R
import io.github.trevarj.motd.ui.theme.MotdMotion
import io.github.trevarj.motd.ui.theme.MotdTheme

/**
 * Aggregate history-sync line, pinned above the list (outside the LazyColumn) so it neither scrolls
 * away nor participates in item animations. Rendered only in the normal chat-list mode; archive and
 * invitation modes are scoped views where a global sync line would be noise.
 *
 * A11y: only the static label is a polite live region, so TalkBack announces "syncing history" once
 * per episode. The changing count lives in a sibling node and therefore never re-announces.
 */
@Composable
fun ChatListSyncHeader(
    chrome: ChatListSyncChrome,
    modifier: Modifier = Modifier,
) {
    when (chrome) {
        ChatListSyncChrome.Hidden -> Unit
        is ChatListSyncChrome.Waiting -> SyncHeaderSurface(modifier) {
            SyncHeaderLabel(
                label = stringResource(R.string.chatlist_sync_header_waiting),
                tag = "chatlist_sync_header_waiting",
            )
        }
        is ChatListSyncChrome.Syncing -> SyncHeaderSurface(modifier) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SyncHeaderLabel(
                    label = stringResource(R.string.chatlist_sync_header_syncing),
                    tag = "chatlist_sync_header_label",
                )
                Text(
                    text = stringResource(R.string.chatlist_sync_header_count, chrome.done, chrome.total),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("chatlist_sync_header_count"),
                )
            }
            // Targets can be discovered mid-pass, so the fraction can move backwards; animating it
            // keeps that from reading as a glitch.
            val fraction by animateFloatAsState(
                targetValue = if (chrome.total > 0) (chrome.done.toFloat() / chrome.total).coerceIn(0f, 1f) else 0f,
                animationSpec = MotdMotion.fadeIn,
                label = "chatlist_sync_header_progress",
            )
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .testTag("chatlist_sync_header_progress"),
            )
        }
    }
}

@Composable
private fun SyncHeaderSurface(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier
            .fillMaxWidth()
            .testTag("chatlist_sync_header"),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            content()
        }
    }
}

@Composable
private fun SyncHeaderLabel(label: String, tag: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .testTag(tag)
            .semantics { liveRegion = LiveRegionMode.Polite },
    )
}

@PreviewLightDark
@Composable
private fun ChatListSyncHeaderPreview() {
    MotdTheme(dynamicColor = false) {
        Column {
            ChatListSyncHeader(ChatListSyncChrome.Syncing(done = 12, total = 42))
            ChatListSyncHeader(ChatListSyncChrome.Waiting(queued = 7))
        }
    }
}
