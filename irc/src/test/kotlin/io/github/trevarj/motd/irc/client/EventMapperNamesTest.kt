package io.github.trevarj.motd.irc.client

import io.github.trevarj.motd.irc.event.IrcEvent
import io.github.trevarj.motd.irc.proto.IrcMessage
import io.github.trevarj.motd.irc.proto.Isupport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EventMapperNamesTest {
    @Test fun `userhost in names retains all prefixes username and host`() {
        val isupport = Isupport().apply { update(listOf("PREFIX=(qaohv)~&@%+")) }
        val mapper = EventMapper({ "me" }, { isupport }, now = { 1L })

        assertEquals(
            IrcEvent.NamesStarted("#Room"),
            mapper.map(
                IrcMessage.parse(
                    ":irc.example 353 me = #Room :~@Nick!~user@host.example +Plain Broken!user !bad@host",
                ),
            ),
        )
        assertEquals(
            null,
            mapper.map(IrcMessage.parse(":irc.example 353 me = #Room :Another")),
        )
        val names = mapper.map(IrcMessage.parse(":irc.example 366 me #Room :End of NAMES")) as IrcEvent.Names

        assertEquals("#Room", names.channel)
        assertEquals(
            IrcEvent.Names.Member("Nick", "~@", "~user", "host.example"),
            names.members[0],
        )
        assertEquals(IrcEvent.Names.Member("Plain", "+", null, null), names.members[1])
        assertEquals(IrcEvent.Names.Member("Broken", "", null, null), names.members[2])
        assertEquals(IrcEvent.Names.Member("Another", "", null, null), names.members[3])
        assertEquals(4, names.members.size)
    }

    @Test fun `reset removes incomplete names before next generation`() {
        val mapper = EventMapper({ "me" }, { Isupport() }, now = { 1L })
        mapper.map(IrcMessage.parse(":srv 353 me = #room :stale"))

        mapper.reset()
        mapper.map(IrcMessage.parse(":srv 353 me = #room :fresh"))
        val names = mapper.map(IrcMessage.parse(":srv 366 me #room :done")) as IrcEvent.Names

        assertEquals(listOf("fresh"), names.members.map { it.nick })
    }

    @Test fun `names member limit is exact and overflow is explicit`() {
        val mapper =
            EventMapper(
                selfNick = { "me" },
                isupport = { Isupport() },
                now = { 1L },
                maxPendingNamesMembers = 2,
            )
        mapper.map(IrcMessage.parse(":srv 353 me = #room :one two"))

        val failure = runCatching { mapper.map(IrcMessage.parse(":srv 353 me = #room :three")) }.exceptionOrNull()

        assertTrue(failure is IrcProtocolException)
        mapper.reset()
    }

    @Test fun `names channel limit rejects another incomplete channel`() {
        val mapper =
            EventMapper(
                selfNick = { "me" },
                isupport = { Isupport() },
                now = { 1L },
                maxPendingNamesChannels = 1,
            )
        mapper.map(IrcMessage.parse(":srv 353 me = #one :nick"))

        val failure = runCatching { mapper.map(IrcMessage.parse(":srv 353 me = #two :other")) }.exceptionOrNull()

        assertTrue(failure is IrcProtocolException)
    }
}
