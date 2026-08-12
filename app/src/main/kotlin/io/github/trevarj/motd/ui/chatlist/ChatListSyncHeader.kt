package io.github.trevarj.motd.ui.chatlist

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
/** The transition key: content within a kind updates in place, only kind changes animate. */
private enum class SyncHeaderKind { HIDDEN, WAITING, SYNCING }

@Composable
fun ChatListSyncHeader(
    chrome: ChatListSyncChrome,
    modifier: Modifier = Modifier,
) {
    val kind = when (chrome) {
        ChatListSyncChrome.Hidden -> SyncHeaderKind.HIDDEN
        is ChatListSyncChrome.Waiting -> SyncHeaderKind.WAITING
        is ChatListSyncChrome.Syncing -> SyncHeaderKind.SYNCING
    }
    // The exiting SYNCING content keeps composing after chrome has moved on; hold the last Syncing
    // value so it renders real counts through the transition instead of collapsing to nothing.
    var lastSyncing by remember { mutableStateOf<ChatListSyncChrome.Syncing?>(null) }
    if (chrome is ChatListSyncChrome.Syncing) lastSyncing = chrome
    AnimatedContent(
        targetState = kind,
        transitionSpec = {
            val contentTransform = when {
                initialState == SyncHeaderKind.HIDDEN ->
                    (fadeIn(MotdMotion.fadeIn) +
                        expandVertically(animationSpec = MotdMotion.contentSize)) togetherWith
                        ExitTransition.None
                targetState == SyncHeaderKind.HIDDEN ->
                    EnterTransition.None togetherWith
                        (fadeOut(MotdMotion.fadeOut) +
                            shrinkVertically(animationSpec = MotdMotion.contentSize))
                else -> fadeIn(MotdMotion.microFadeIn) togetherWith fadeOut(MotdMotion.microFadeOut)
            }
            // expand/shrink already own the hidden <-> content size change. Disable
            // AnimatedContent's default SizeTransform so the same height is not animated twice.
            contentTransform.using(null)
        },
        modifier = modifier,
        label = "chatlist_sync_header",
    ) { current ->
        when (current) {
            SyncHeaderKind.HIDDEN -> Unit
            SyncHeaderKind.WAITING -> SyncHeaderSurface {
                SyncHeaderLabel(
                    label = stringResource(R.string.chatlist_sync_header_waiting),
                    tag = "chatlist_sync_header_waiting",
                )
            }
            SyncHeaderKind.SYNCING -> {
                val syncing = (chrome as? ChatListSyncChrome.Syncing) ?: lastSyncing
                if (syncing != null) SyncHeaderSurface {
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
                            text = stringResource(R.string.chatlist_sync_header_count, syncing.done, syncing.total),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("chatlist_sync_header_count"),
                        )
                    }
                    // Targets can be discovered mid-pass, so the fraction can move backwards; animating it
                    // keeps that from reading as a glitch.
                    val fraction by animateFloatAsState(
                        targetValue = if (syncing.total > 0) (syncing.done.toFloat() / syncing.total).coerceIn(0f, 1f) else 0f,
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
