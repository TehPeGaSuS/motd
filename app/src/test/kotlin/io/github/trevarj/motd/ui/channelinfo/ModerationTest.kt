package io.github.trevarj.motd.ui.channelinfo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModerationTest {
    @Test fun op_and_above_can_moderate() {
        assertTrue(canModerate("@", DEFAULT_PREFIX_ORDER))   // op
        assertTrue(canModerate("&", DEFAULT_PREFIX_ORDER))   // admin
        assertTrue(canModerate("~", DEFAULT_PREFIX_ORDER))   // owner
    }

    @Test fun voice_and_halfop_cannot_moderate() {
        assertFalse(canModerate("+", DEFAULT_PREFIX_ORDER))  // voice
        assertFalse(canModerate("%", DEFAULT_PREFIX_ORDER))  // halfop excluded (Confirmed #7)
        assertFalse(canModerate("", DEFAULT_PREFIX_ORDER))   // no prefix
    }

    @Test fun highest_held_prefix_is_honored() {
        // Holding both op and voice: op qualifies.
        assertTrue(canModerate("@+", DEFAULT_PREFIX_ORDER))
        // Holding halfop and voice only: neither qualifies.
        assertFalse(canModerate("%+", DEFAULT_PREFIX_ORDER))
    }

    @Test fun empty_prefix_order_falls_back_to_default() {
        assertTrue(canModerate("@", ""))
        assertFalse(canModerate("+", ""))
    }

    @Test fun ban_mask_is_nick_wildcard() {
        assertEquals("bob!*@*", banMask("bob"))
    }

    @Test fun host_mask_matches_any_nick_at_that_address() {
        assertEquals("*!*@host.example.net", hostMask("host.example.net"))
    }

    @Test fun mask_preview_follows_the_selected_scope() {
        assertEquals(
            "bob!*@*",
            composeBanMask(BanScope.NICK, "bob", "host.example.net", "ignored"),
        )
        assertEquals(
            "*!*@host.example.net",
            composeBanMask(BanScope.HOST, "bob", "host.example.net", "ignored"),
        )
        assertEquals(
            "*!user@example.net",
            composeBanMask(BanScope.CUSTOM, "bob", "host.example.net", "  *!user@example.net  "),
        )
    }

    @Test fun mask_preview_is_blank_until_the_scope_can_be_satisfied() {
        assertEquals("", composeBanMask(BanScope.NICK, null, "host", "mask"))
        assertEquals("", composeBanMask(BanScope.NICK, "  ", "host", "mask"))
        assertEquals("", composeBanMask(BanScope.HOST, "bob", null, "mask"))
        assertEquals("", composeBanMask(BanScope.CUSTOM, "bob", "host", "   "))
    }

    @Test fun cached_hostmask_yields_only_the_host_part() {
        assertEquals("example.net", hostFromUserHost("user@example.net"))
        assertNull(hostFromUserHost("noathere"))
        assertNull(hostFromUserHost(null))
        assertNull(hostFromUserHost("user@"))
    }
}
