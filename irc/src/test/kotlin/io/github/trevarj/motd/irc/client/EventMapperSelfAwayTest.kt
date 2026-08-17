package io.github.trevarj.motd.irc.client

import io.github.trevarj.motd.irc.event.IrcEvent
import io.github.trevarj.motd.irc.proto.IrcMessage
import io.github.trevarj.motd.irc.proto.Isupport
import org.junit.Assert.assertEquals
import org.junit.Test

class EventMapperSelfAwayTest {
    private val mapper = EventMapper({ "me" }, { Isupport() })

    @Test fun `306 marks us away and 305 marks us back`() {
        assertEquals(
            IrcEvent.SelfAwayChanged(isAway = true, text = "You have been marked as being away"),
            mapper.map(IrcMessage.parse(":server 306 me :You have been marked as being away")),
        )
        assertEquals(
            IrcEvent.SelfAwayChanged(isAway = false, text = "You are no longer marked as being away"),
            mapper.map(IrcMessage.parse(":server 305 me :You are no longer marked as being away")),
        )
    }

    @Test fun `our nick is dropped and remaining params keep the rendered line intact`() {
        // Same text the SERVER_INFO whitelist used to render for these numerics.
        assertEquals(
            IrcEvent.SelfAwayChanged(isAway = true, text = "extra you are away"),
            mapper.map(IrcMessage.parse(":server 306 me extra :you are away")),
        )
        assertEquals(
            IrcEvent.SelfAwayChanged(isAway = false, text = ""),
            mapper.map(IrcMessage.parse(":server 305 me")),
        )
    }

    @Test fun `away-notify echoes stay AwayChanged`() {
        assertEquals(
            IrcEvent.AwayChanged("me", "brb"),
            mapper.map(IrcMessage.parse(":me!u@h AWAY :brb")),
        )
    }
}
