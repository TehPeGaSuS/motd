package io.github.trevarj.motd.invite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class JoinInviteCodecTest {
    private val invite =
        JoinInviteV1(
            networkName = "Private Ergo",
            host = "irc.example.test",
            port = 6697,
            channel = "#friends",
            channelKey = "open-sesame",
            certSha256 = "ab".repeat(32),
        )

    @Test
    fun `canonical and install links round trip with payload in HTTPS fragment`() {
        assertEquals(invite, JoinInviteCodec.parse(JoinInviteCodec.appUri(invite)))
        assertEquals(invite, JoinInviteCodec.parseScanned(JoinInviteCodec.encode(invite)))
        val install = JoinInviteCodec.installUri(invite)
        assertEquals(invite, JoinInviteCodec.parse(install))
        assertTrue(install.startsWith("https://github.com/trevarj/motd/releases/latest#motd-invite="))
    }

    @Test
    fun `rejects wrong origin commands and unsafe fields`() {
        assertThrows(InvalidJoinInviteException::class.java) {
            JoinInviteCodec.parse("https://example.test/?v=${JoinInviteCodec.encode(invite)}")
        }
        assertThrows(InvalidJoinInviteException::class.java) {
            JoinInviteCodec.decode("a".repeat(2_049))
        }
        assertThrows(InvalidJoinInviteException::class.java) {
            JoinInviteCodec.encode(invite.copy(certSha256 = "not-a-pin"))
        }
        assertThrows(InvalidJoinInviteException::class.java) {
            JoinInviteCodec.encode(invite.copy(channel = "#ok\r\nJOIN #evil"))
        }
        assertThrows(InvalidJoinInviteException::class.java) {
            JoinInviteCodec.encode(invite.copy(channelKey = "two words"))
        }
        assertThrows(InvalidJoinInviteException::class.java) {
            JoinInviteCodec.encode(invite.copy(v = 2))
        }
        val install = JoinInviteCodec.installUri(invite)
        assertThrows(InvalidJoinInviteException::class.java) {
            JoinInviteCodec.parse(install.replace("github.com", "example.test"))
        }
        assertThrows(InvalidJoinInviteException::class.java) {
            JoinInviteCodec.parse(install.replace("/latest#", "/latest?download=1#"))
        }
    }

    @Test
    fun `plain text invitation remains representable for explicit warning path`() {
        val plaintext = invite.copy(tls = false, port = 6667, certSha256 = null)
        assertEquals(plaintext, JoinInviteCodec.decode(JoinInviteCodec.encode(plaintext)))
    }
}
