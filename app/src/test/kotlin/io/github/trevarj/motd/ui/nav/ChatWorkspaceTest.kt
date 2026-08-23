package io.github.trevarj.motd.ui.nav

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatWorkspaceTest {
    @Test
    fun `single pane remains active below the fit threshold`() {
        assertEquals(ChatWorkspaceMode.SINGLE, chatWorkspacePolicy(719f).mode)
    }

    @Test
    fun `medium width uses a stable compact list pane`() {
        assertEquals(
            ChatWorkspacePolicy(ChatWorkspaceMode.DUAL_FIXED, CHAT_LIST_MIN_DP),
            chatWorkspacePolicy(720f),
        )
        assertEquals(ChatWorkspaceMode.DUAL_FIXED, chatWorkspacePolicy(839f).mode)
    }

    @Test
    fun `expanded width permits resizing without starving detail`() {
        assertEquals(
            ChatWorkspacePolicy(ChatWorkspaceMode.DUAL_RESIZABLE, CHAT_LIST_INITIAL_DP),
            chatWorkspacePolicy(840f),
        )
        assertEquals(408f, chatWorkspacePolicy(840f, requestedListWidthDp = 420f).listWidthDp)
        assertEquals(420f, chatWorkspacePolicy(1_000f, requestedListWidthDp = 500f).listWidthDp)
        assertEquals(280f, chatWorkspacePolicy(1_000f, requestedListWidthDp = 100f).listWidthDp)
    }
}
