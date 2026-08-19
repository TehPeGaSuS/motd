package io.github.trevarj.motd.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Truth table for [messageStatus], the pure status decision shared by every render site. Priority
 * is failed > pending > sent, and incoming messages ([isSelf] false) must always be [NONE] so no
 * check leaks onto other people's lines.
 */
class MessageStatusTest {

    @Test fun self_confirmed_is_sent() {
        assertEquals(MsgStatus.SENT, messageStatus(isSelf = true, pending = false, failed = false))
    }

    @Test fun self_pending_is_pending() {
        assertEquals(MsgStatus.PENDING, messageStatus(isSelf = true, pending = true, failed = false))
    }

    @Test fun self_failed_is_failed() {
        assertEquals(MsgStatus.FAILED, messageStatus(isSelf = true, pending = false, failed = true))
    }

    @Test fun failed_wins_over_pending() {
        assertEquals(MsgStatus.FAILED, messageStatus(isSelf = true, pending = true, failed = true))
    }

    @Test fun incoming_confirmed_is_none() {
        assertEquals(MsgStatus.NONE, messageStatus(isSelf = false, pending = false, failed = false))
    }

    @Test fun incoming_never_shows_a_check_even_if_flagged() {
        // Incoming messages are never pending/failed in practice, but the isSelf guard must hold
        // regardless of the other flags so a check can never appear on another user's line.
        assertEquals(MsgStatus.NONE, messageStatus(isSelf = false, pending = true, failed = false))
        assertEquals(MsgStatus.NONE, messageStatus(isSelf = false, pending = false, failed = true))
    }
}

/**
 * The one morph grammar with two endings: [statusMorph] decides which clock-out beat, if any, a
 * status change has earned. Both endings share the scrollback rule, which is the whole reason the
 * timeline does not flicker when a page of already-settled rows scrolls in.
 */
class StatusMorphTest {

    @Test fun pending_to_sent_draws_the_check() {
        assertEquals(StatusMorph.DELIVERED, statusMorph(MsgStatus.PENDING, MsgStatus.SENT))
    }

    @Test fun pending_to_failed_draws_the_cross() {
        assertEquals(StatusMorph.FAILED, statusMorph(MsgStatus.PENDING, MsgStatus.FAILED))
    }

    @Test fun a_retry_that_lands_still_earns_the_check() {
        assertEquals(StatusMorph.DELIVERED, statusMorph(MsgStatus.FAILED, MsgStatus.SENT))
    }

    @Test fun retrying_a_failure_back_to_pending_plays_nothing() {
        // The clock returns as a static glyph; there is no cross-to-clock beat to run backwards.
        assertNull(statusMorph(MsgStatus.FAILED, MsgStatus.PENDING))
    }

    @Test fun scrollback_never_replays_either_ending() {
        // A row first observed already settled has not transitioned into that state.
        assertNull(statusMorph(previous = null, current = MsgStatus.SENT))
        assertNull(statusMorph(previous = null, current = MsgStatus.FAILED))
        assertNull(statusMorph(previous = null, current = MsgStatus.PENDING))
    }

    @Test fun staying_settled_does_not_replay() {
        assertNull(statusMorph(MsgStatus.SENT, MsgStatus.SENT))
        assertNull(statusMorph(MsgStatus.FAILED, MsgStatus.FAILED))
    }

    @Test fun each_beat_names_its_own_asset_and_glyph_layer() {
        // The two assets are siblings, not one multi-beat file: the delivery asset keeps the exact
        // frame range LottieAssetsTest pins for it.
        assertEquals("check", StatusMorph.DELIVERED.glyphLayer)
        assertEquals("cross", StatusMorph.FAILED.glyphLayer)
        assertNotEquals(StatusMorph.DELIVERED.rawRes, StatusMorph.FAILED.rawRes)
    }
}
