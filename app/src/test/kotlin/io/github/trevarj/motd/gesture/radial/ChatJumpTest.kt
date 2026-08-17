package io.github.trevarj.motd.gesture.radial

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which orb chat jumps actually navigate.
 *
 * Regression coverage for the swallowed jump: from inside a chat the host must replace the open
 * ChatRoute (a `launchSingleTop` re-navigation to an on-top ChatRoute never updates its
 * arguments), and only the jump to the chat already on top is skipped.
 */
class ChatJumpTest {
    @Test fun `jumping to another chat from inside a chat navigates`() {
        assertTrue(shouldPerformChatJump(currentChatBufferId = 5L, targetBufferId = 7L))
    }

    @Test fun `jumping to the chat already on top is skipped`() {
        assertFalse(shouldPerformChatJump(currentChatBufferId = 7L, targetBufferId = 7L))
    }

    @Test fun `jumping from a non-chat screen navigates`() {
        assertTrue(shouldPerformChatJump(currentChatBufferId = null, targetBufferId = 7L))
    }
}
