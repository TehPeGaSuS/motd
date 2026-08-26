package io.github.trevarj.motd.ui.invite

import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class InviteEndpointTest {
    private val direct =
        NetworkEntity(
            id = 1,
            name = "Ergo",
            role = NetworkRole.DIRECT,
            host = "irc.example",
            port = 6697,
            nick = "alice",
            username = "alice",
            realname = "Alice",
        )

    @Test
    fun `direct endpoint excludes identity credentials`() {
        assertEquals(InviteEndpoint("irc.example", 6697, true), resolveDirectInviteEndpoint(direct.copy(saslUser = "alice", saslPassword = "secret"), false))
    }

    @Test
    fun `znc cloak and server pass endpoints are refused`() {
        assertThrows(IllegalStateException::class.java) { resolveDirectInviteEndpoint(direct, true) }
        assertThrows(IllegalStateException::class.java) { resolveDirectInviteEndpoint(direct.copy(saslUser = "alice/libera"), false) }
        assertThrows(IllegalStateException::class.java) { resolveDirectInviteEndpoint(direct.copy(serverPassword = "network-pass"), false) }
    }
}
