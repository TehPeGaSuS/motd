package io.github.trevarj.motd.irc.client

import io.github.trevarj.motd.irc.event.IrcEvent
import io.github.trevarj.motd.irc.proto.IrcMessage
import io.github.trevarj.motd.irc.proto.Isupport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EventMapperDccTest {
    private val mapper = EventMapper({ "me" }, { Isupport() }, { 42L })

    @Test
    fun `dcc send with quoted filename maps to typed offer`() {
        val event = mapper.map(
            IrcMessage.parse(":alice!u@h PRIVMSG me :\u0001DCC SEND \"photo set.jpg\" 3232235777 49152 1048576\u0001"),
        ) as IrcEvent.DccSend

        assertEquals("alice", event.source.nick)
        assertEquals("me", event.target)
        assertEquals(IrcEvent.DccFileProtocol.SEND, event.offer.protocol)
        assertEquals("photo set.jpg", event.offer.filename)
        assertEquals("3232235777", event.offer.endpoint.address)
        assertEquals(49152, event.offer.endpoint.port)
        assertEquals(IrcEvent.DccAddressKind.IPV4_INTEGER, event.offer.endpoint.addressKind)
        assertEquals(1_048_576L, event.offer.sizeBytes)
        assertEquals(null, event.offer.token)
    }

    @Test
    fun `dcc ssend accepts ipv6 literal and passive token`() {
        val event = mapper.map(
            IrcMessage.parse(":alice!u@h PRIVMSG me :\u0001DCC SSEND archive.tar [2001:db8::1] 0 4096 token-7\u0001"),
        ) as IrcEvent.DccSend

        assertEquals(IrcEvent.DccFileProtocol.SSEND, event.offer.protocol)
        assertEquals("[2001:db8::1]", event.offer.endpoint.address)
        assertEquals(IrcEvent.DccAddressKind.IPV6_LITERAL, event.offer.endpoint.addressKind)
        assertEquals(0, event.offer.endpoint.port)
        assertEquals(4_096L, event.offer.sizeBytes)
        assertEquals("token-7", event.offer.token)
    }

    @Test
    fun `dcc resume and accept map to typed requests`() {
        val resume = mapper.map(
            IrcMessage.parse(":alice!u@h PRIVMSG me :\u0001DCC RESUME \"big file.bin\" 49152 65536 token-7\u0001"),
        ) as IrcEvent.DccResume
        val accept = mapper.map(
            IrcMessage.parse(":alice!u@h PRIVMSG me :\u0001DCC ACCEPT \"big file.bin\" 49152 65536 token-7\u0001"),
        ) as IrcEvent.DccAccept

        assertEquals("big file.bin", resume.request.filename)
        assertEquals(49_152, resume.request.port)
        assertEquals(65_536L, resume.request.positionBytes)
        assertEquals("token-7", resume.request.token)
        assertEquals("big file.bin", accept.accepted.filename)
        assertEquals(49_152, accept.accepted.port)
        assertEquals(65_536L, accept.accepted.positionBytes)
        assertEquals("token-7", accept.accepted.token)
    }

    @Test
    fun `unknown dcc command stays visible as unsupported dcc`() {
        val event = mapper.map(
            IrcMessage.parse(":alice!u@h PRIVMSG me :\u0001DCC VOICE chat 192.0.2.10 5000\u0001"),
        ) as IrcEvent.UnsupportedDcc

        assertEquals("VOICE", event.command)
        assertEquals(IrcEvent.DccUnsupportedReason.UNKNOWN_COMMAND, event.reason)
        assertEquals("DCC VOICE chat 192.0.2.10 5000", event.rawPayload)
    }

    @Test
    fun `malformed dcc command stays visible as unsupported dcc`() {
        val event = mapper.map(
            IrcMessage.parse(":alice!u@h PRIVMSG me :\u0001DCC SEND \"unterminated 192.0.2.10 5000\u0001"),
        ) as IrcEvent.UnsupportedDcc

        assertEquals(null, event.command)
        assertEquals(IrcEvent.DccUnsupportedReason.MALFORMED, event.reason)
    }

    @Test
    fun `passive dcc without token is malformed`() {
        val event = mapper.map(
            IrcMessage.parse(":alice!u@h PRIVMSG me :\u0001DCC SEND file.bin 192.0.2.10 0 100\u0001"),
        ) as IrcEvent.UnsupportedDcc

        assertEquals("SEND", event.command)
        assertEquals(IrcEvent.DccUnsupportedReason.MALFORMED, event.reason)
    }

    @Test
    fun `invalid ipv6 literal is malformed`() {
        val event = mapper.map(
            IrcMessage.parse(":alice!u@h PRIVMSG me :\u0001DCC SEND file.bin bad::address 5000 100\u0001"),
        ) as IrcEvent.UnsupportedDcc

        assertEquals("SEND", event.command)
        assertEquals(IrcEvent.DccUnsupportedReason.MALFORMED, event.reason)
    }

    @Test
    fun `non dcc ctcp is still ignored`() {
        val event = mapper.map(
            IrcMessage.parse(":alice!u@h PRIVMSG me :\u0001PING 123\u0001"),
        )

        assertTrue(event == null)
    }
}
