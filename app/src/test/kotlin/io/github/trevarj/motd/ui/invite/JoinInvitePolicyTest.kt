package io.github.trevarj.motd.ui.invite

import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.invite.JoinInviteV1
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JoinInvitePolicyTest {
    private val invite = JoinInviteV1(networkName = "Ergo", host = "IRC.Example.", port = 6697, channel = "#friends")
    private val direct =
        NetworkEntity(
            name = "Existing",
            role = NetworkRole.DIRECT,
            host = "irc.example",
            port = 6697,
            nick = "alice",
            username = "alice",
            realname = "Alice",
        )

    @Test
    fun `compatible direct endpoint reuses existing identity and credentials`() {
        assertTrue(compatibleInviteNetwork(direct.copy(saslUser = "alice", saslPassword = "private"), invite))
    }

    @Test
    fun `bouncer selectors and different transport do not collapse`() {
        assertFalse(compatibleInviteNetwork(direct.copy(saslUser = "alice/libera"), invite))
        assertFalse(compatibleInviteNetwork(direct.copy(tls = false, port = 6667), invite))
        assertFalse(compatibleInviteNetwork(direct.copy(role = NetworkRole.BOUNCER_ROOT), invite))
    }
}
