package io.github.trevarj.motd.service

import io.github.trevarj.motd.irc.event.IrcEvent
import io.github.trevarj.motd.irc.event.MessageContext
import io.github.trevarj.motd.irc.event.ServerTimeSource
import io.github.trevarj.motd.irc.proto.IrcIdentityRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelJoinOutcomeTest {
    @Test
    fun `473 produces a target keyed join rejection while ready`() {
        val outcome = channelJoinOutcome(
            networkId = 7,
            event = IrcEvent.ServerError("473", listOf("me", "#locked"), "Cannot join channel (+i)"),
            identityRules = IrcIdentityRules(),
        )

        assertTrue(outcome is ChannelJoinOutcome.Rejected)
        outcome as ChannelJoinOutcome.Rejected
        assertEquals(7L, outcome.networkId)
        assertEquals("#locked", outcome.channel)
        assertEquals("Cannot join channel (+i)", outcome.reason)
    }

    @Test
    fun `ircv3 fail join produces a target keyed rejection`() {
        val outcome = channelJoinOutcome(
            networkId = 7,
            event = IrcEvent.StandardReply(
                ctx = MessageContext(null, 0, null, null, null, ServerTimeSource.LOCAL),
                severity = IrcEvent.StandardReplySeverity.FAIL,
                commandName = "JOIN",
                code = "CANNOT_JOIN",
                context = listOf("#locked"),
                description = "Cannot join channel",
            ),
            identityRules = IrcIdentityRules(),
        )

        assertEquals("#locked", (outcome as ChannelJoinOutcome.Rejected).channel)
    }
}
