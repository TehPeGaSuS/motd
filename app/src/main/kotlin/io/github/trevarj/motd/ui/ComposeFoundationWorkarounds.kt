package io.github.trevarj.motd.ui

import androidx.compose.foundation.ComposeFoundationFlags
import androidx.compose.foundation.ExperimentalFoundationApi

/**
 * Process-wide opt-outs from broken Compose foundation behavior. Applied once at startup and by
 * the Robolectric UI tests that pin the behavior.
 */
object ComposeFoundationWorkarounds {
    /**
     * Foundation 1.11's skip-placement-animation "fix" (b/493183465) freezes the lazy-list item
     * animator's key/index bookkeeping for the whole duration of `animateScrollToItem`. When the
     * scroll lands exactly on target nothing resets that stale state, and the next user scroll
     * classifies every row whose index changed while it was off-screen (chat-list rows re-sort
     * with activity constantly) as "moving in": rows are initialized stacked outside the viewport
     * and visibly spring into place. Repro and coverage: ChatListScrollPlacementTest. Re-evaluate
     * when the Compose BOM is upgraded; this assignment stops compiling once the flag is removed.
     */
    @OptIn(ExperimentalFoundationApi::class)
    fun apply() {
        ComposeFoundationFlags.isSkipItemPlacementAnimationFixEnabled = false
    }
}
