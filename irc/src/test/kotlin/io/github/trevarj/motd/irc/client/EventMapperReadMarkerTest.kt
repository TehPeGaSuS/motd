package io.github.trevarj.motd.irc.client

import io.github.trevarj.motd.irc.event.IrcEvent
import io.github.trevarj.motd.irc.proto.IrcMessage
import io.github.trevarj.motd.irc.proto.Isupport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class EventMapperReadMarkerTest {
    private val mapper = EventMapper({ "me" }, { Isupport() })
    private val ts = "2026-07-25T12:00:00.000Z"
    private val tsMs = Instant.parse(ts).toEpochMilli()

    @Test
    fun `MARKREAD maps to ReadMarker with parsed timestamp`() {
        val event = mapper.map(IrcMessage.parse(":srv MARKREAD #motd timestamp=$ts"))
        assertEquals(IrcEvent.ReadMarker("#motd", tsMs), event)
    }

    @Test
    fun `MARKREAD with star maps to ReadMarker with null timestamp`() {
        val event = mapper.map(IrcMessage.parse(":srv MARKREAD #motd timestamp=*"))
        assertEquals(IrcEvent.ReadMarker("#motd", null), event)
    }

    @Test
    fun `READ maps to ReadMarker when soju im read is negotiated`() {
        val withCap = EventMapper({ "me" }, { Isupport() }, sojuReadCap = { true })
        val event = withCap.map(IrcMessage.parse(":srv READ #motd timestamp=$ts"))
        assertEquals(IrcEvent.ReadMarker("#motd", tsMs), event)
    }

    @Test
    fun `READ with star maps to null timestamp under soju im read`() {
        val withCap = EventMapper({ "me" }, { Isupport() }, sojuReadCap = { true })
        val event = withCap.map(IrcMessage.parse(":srv READ #motd timestamp=*"))
        assertEquals(IrcEvent.ReadMarker("#motd", null), event)
    }

    @Test
    fun `READ without a timestamp param maps to null timestamp under soju im read`() {
        val withCap = EventMapper({ "me" }, { Isupport() }, sojuReadCap = { true })
        val event = withCap.map(IrcMessage.parse(":srv READ #motd"))
        assertEquals(IrcEvent.ReadMarker("#motd", null), event)
    }

    @Test
    fun `READ is dropped when soju im read is not negotiated`() {
        val withoutCap = EventMapper({ "me" }, { Isupport() }, sojuReadCap = { false })
        val event = withoutCap.map(IrcMessage.parse(":srv READ #motd timestamp=$ts"))
        assertNull(event)
    }

    @Test
    fun `default mapper maps READ so unit tests without explicit cap wiring stay permissive`() {
        // Production wires sojuReadCap to the real cap; the mapper default stays permissive so the
        // pure mapping logic is exercised without a transport.
        val event = mapper.map(IrcMessage.parse(":srv READ #motd timestamp=$ts"))
        assertTrue(event is IrcEvent.ReadMarker)
    }
}