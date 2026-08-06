package io.github.trevarj.motd.ui.chatlist

import io.github.trevarj.motd.ui.theme.MotdMotion
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatListItemMotionTest {
    private fun settledGate(): ChatListPlacementGate = ChatListPlacementGate()
        .signal(ChatListPlacementSignal.PaneResumed)
        .signal(ChatListPlacementSignal.FrameRendered)

    private fun ChatListPlacementGate.signal(signal: ChatListPlacementSignal) =
        reduceChatListPlacement(this, signal)

    @Test
    fun `a watched chat list springs rows into place and keeps the shared micro fades`() {
        assertSame(MotdMotion.rowPlacement, ChatListItemMotion.placementSpec(settledGate()))
        assertSame(MotdMotion.microFadeIn, ChatListItemMotion.fadeInSpec)
        assertSame(MotdMotion.microFadeOut, ChatListItemMotion.fadeOutSpec)
    }

    @Test
    fun `an unwatched chat list applies reorders without placement animation`() {
        assertNull(ChatListItemMotion.placementSpec(ChatListPlacementGate()))
        assertNull(
            ChatListItemMotion.placementSpec(
                settledGate().signal(ChatListPlacementSignal.PaneHidden),
            ),
        )
    }

    @Test
    fun `becoming visible does not animate until a frame has carried the catch-up snapshot`() {
        val justResumed = ChatListPlacementGate().signal(ChatListPlacementSignal.PaneResumed)
        assertTrue(justResumed.visible)
        assertFalse(justResumed.animatesPlacement)
        assertTrue(justResumed.signal(ChatListPlacementSignal.FrameRendered).animatesPlacement)
    }

    @Test
    fun `a settled chat list stays armed across further resume and frame signals`() {
        val settled = settledGate()
        assertTrue(settled.signal(ChatListPlacementSignal.PaneResumed).animatesPlacement)
        assertTrue(settled.signal(ChatListPlacementSignal.FrameRendered).animatesPlacement)
    }

    @Test
    fun `hiding the pane disarms placement and re-showing it re-arms from scratch`() {
        val hidden = settledGate().signal(ChatListPlacementSignal.PaneHidden)
        assertFalse(hidden.visible)
        assertFalse(hidden.animatesPlacement)

        // Returning from a chat or from the background must replay nothing: the first application
        // after the pane comes back has to land unanimated, exactly as if it were never armed.
        val reshown = hidden.signal(ChatListPlacementSignal.PaneResumed)
        assertFalse(reshown.animatesPlacement)
        assertTrue(reshown.signal(ChatListPlacementSignal.FrameRendered).animatesPlacement)
    }

    @Test
    fun `frames rendered while hidden never arm placement`() {
        var gate = ChatListPlacementGate()
        repeat(5) { gate = gate.signal(ChatListPlacementSignal.FrameRendered) }
        assertFalse(gate.animatesPlacement)
        assertNull(ChatListItemMotion.placementSpec(gate))
    }
}
