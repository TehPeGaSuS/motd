package io.github.trevarj.motd.ui.settings

import io.github.trevarj.motd.R
import io.github.trevarj.motd.irc.proto.IrcMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OperatorCommandsTest {
    @Test fun kill_preview_is_the_exact_wire_line() {
        // serialize() already excludes the trailing CRLF the transport appends, and only reaches
        // for the trailing ':' form when the reason contains a space.
        assertEquals("KILL spammer Flooding", killMessage(" spammer ", " Flooding ").previewLine())
        assertEquals(
            "KILL spammer :Being disruptive",
            killMessage("spammer", "Being disruptive").previewLine(),
        )
    }

    @Test fun squit_and_connect_previews_carry_every_argument() {
        assertEquals(
            "SQUIT hub.example.net :routing loop",
            squitMessage("hub.example.net", "routing loop").previewLine(),
        )
        assertEquals(
            "CONNECT leaf.example.net 6667 hub.example.net",
            connectMessage("leaf.example.net", "6667", "hub.example.net").previewLine(),
        )
    }

    @Test fun optional_connect_and_rehash_arguments_are_dropped_when_blank() {
        assertEquals("CONNECT leaf.example.net", connectMessage("leaf.example.net", " ", "").previewLine())
        assertEquals("REHASH", rehashMessage("  ").previewLine())
        assertEquals("REHASH hub.example.net", rehashMessage("hub.example.net").previewLine())
    }

    @Test fun mode_splits_arguments_on_whitespace() {
        assertEquals(
            listOf("#room", "+ov", "alice", "bob"),
            modeMessage(" #room ", " +ov ", "  alice   bob ").params,
        )
    }

    @Test fun preview_is_null_for_a_line_that_cannot_be_serialized() {
        // A newline would split the wire stream, so serialize() refuses it and there is nothing
        // honest to show the user in the confirmation.
        assertNull(IrcMessage(command = "KILL", params = listOf("nick", "one\r\ntwo")).previewLine())
    }

    @Test fun status_maps_to_resources_without_any_viewmodel_owned_wording() {
        assertEquals(
            NetworkToolsStatusText(R.string.network_tools_status_ignore_added, emptyArray()),
            networkToolsStatusText(NetworkToolsStatus.IgnoreAdded),
        )
        assertEquals(
            NetworkToolsStatusText(R.string.network_tools_status_ignore_failed, arrayOf<Any>("bad mask")),
            networkToolsStatusText(NetworkToolsStatus.IgnoreFailed("bad mask")),
        )
        assertEquals(
            NetworkToolsStatusText(R.string.network_tools_status_not_connected, emptyArray()),
            networkToolsStatusText(NetworkToolsStatus.NotConnected),
        )
        assertEquals(
            NetworkToolsStatusText(R.string.network_tools_status_sent, arrayOf<Any>("KILL")),
            networkToolsStatusText(NetworkToolsStatus.CommandSent("KILL")),
        )
        assertEquals(
            NetworkToolsStatusText(R.string.network_tools_status_failed, arrayOf<Any>("SQUIT", "boom")),
            networkToolsStatusText(NetworkToolsStatus.CommandFailed("SQUIT", "boom")),
        )
        assertEquals(
            NetworkToolsStatusText(R.string.network_tools_status_missing_fields, emptyArray()),
            networkToolsStatusText(NetworkToolsStatus.MissingFields),
        )
    }
}
