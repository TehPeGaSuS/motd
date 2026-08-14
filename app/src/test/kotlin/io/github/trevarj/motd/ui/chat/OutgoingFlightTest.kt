package io.github.trevarj.motd.ui.chat

import io.github.trevarj.motd.data.db.MessageEntity
import io.github.trevarj.motd.data.db.MessageKind
import io.github.trevarj.motd.service.SendAcceptance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The flight reducer decides how long a real timeline row stays hidden behind the ghost that is
 * standing in for it, so every transition here has a row's visibility riding on it.
 */
class OutgoingFlightTest {
    @Test
    fun `a launched flight has no target until the send is accepted`() {
        val flight = launchOutgoingFlight(token = 1, text = "hello", replyTo = null, nowMs = 0)

        assertEquals("hello", flight.text)
        assertTrue(flight.eventIds.isEmpty())
    }

    @Test
    fun `an accepted send adopts the row identity it produced`() {
        val flight = launchOutgoingFlight(token = 1, text = "hello", replyTo = null, nowMs = 0)

        val attached = attachOutgoingFlightEvents(flight, token = 1, eventIds = listOf(7L))

        assertEquals(setOf(7L), attached?.eventIds)
    }

    @Test
    fun `a stale accept cannot retarget the ghost of a newer tap`() {
        val newer = launchOutgoingFlight(token = 2, text = "second", replyTo = null, nowMs = 0)

        val attached = attachOutgoingFlightEvents(newer, token = 1, eventIds = listOf(4L))

        assertEquals(newer, attached)
    }

    @Test
    fun `an accept with no rows settles the flight rather than aiming it at nothing`() {
        val flight = launchOutgoingFlight(token = 1, text = "hello", replyTo = null, nowMs = 0)

        assertNull(attachOutgoingFlightEvents(flight, token = 1, eventIds = emptyList()))
    }

    @Test
    fun `an unaccepted flight recognises its pending row by what it says`() {
        val flight = launchOutgoingFlight(token = 1, text = "hello", replyTo = null, nowMs = 0)

        // The row exists in Room before the send is accepted. Matching it only once event ids
        // arrive would let it appear, animate, and then be pulled back under the ghost.
        assertTrue(flight.matches(row(id = 5, text = "hello", pendingLabel = "motd-1")))
        assertFalse(flight.matches(row(id = 5, text = "hello", pendingLabel = null)))
        assertFalse(flight.matches(row(id = 5, text = "different", pendingLabel = "motd-1")))
        assertFalse(flight.matches(row(id = 5, text = "hello", pendingLabel = "motd-1", isSelf = false)))
    }

    @Test
    fun `a repeat of the same text cannot capture the previous send's pending row`() {
        // Sending "lol" twice before the first echo returns leaves two identical pending rows.
        // Without the launch stamp the second ghost claims the first row, slamming it shut.
        val first = row(id = 5, text = "lol", pendingLabel = "motd-1", serverTime = 100)
        val second = launchOutgoingFlight(token = 2, text = "lol", replyTo = null, nowMs = 200)

        assertFalse(second.matches(first))
        assertTrue(second.matches(row(id = 6, text = "lol", pendingLabel = "motd-2", serverTime = 200)))
    }

    @Test
    fun `a flight claims an accepted row only when the pipeline stored what it shows`() {
        val sent = "hello"

        // The ordinary case: one row, stored verbatim.
        assertTrue(accepted(listOf(1L), listOf(sent)).let { acceptedRowMatchesFlight(it, sent) })
        // A reply without the client tag is stored with a visible prefix.
        assertFalse(
            acceptedRowMatchesFlight(accepted(listOf(1L), listOf("alice: hello")), sent),
        )
        // Newlines split one submission across rows; a single ghost cannot stand in for several.
        assertFalse(
            acceptedRowMatchesFlight(accepted(listOf(1L, 2L), listOf("hello", "there")), sent),
        )
        // An unreporting seam is treated as matching, so a single row still flies.
        assertTrue(acceptedRowMatchesFlight(accepted(listOf(1L), emptyList()), sent))
    }

    @Test
    fun `an accepted flight trusts its event ids over a lookalike row`() {
        val flight = attachOutgoingFlightEvents(
            launchOutgoingFlight(token = 1, text = "hello", replyTo = null, nowMs = 0),
            token = 1,
            eventIds = listOf(5L),
        )!!

        assertTrue(flight.matches(row(id = 5, text = "anything", pendingLabel = null)))
        assertFalse(flight.matches(row(id = 6, text = "hello", pendingLabel = "motd-1")))
    }

    @Test
    fun `settling clears only the flight it names`() {
        val flight = launchOutgoingFlight(token = 3, text = "hello", replyTo = null, nowMs = 0)

        assertEquals(flight, settleOutgoingFlight(flight, token = 2))
        assertNull(settleOutgoingFlight(flight, token = 3))
    }

    private fun accepted(eventIds: List<Long>, storedTexts: List<String>) =
        SendAcceptance.Accepted(eventIds = eventIds, storedTexts = storedTexts)

    private fun row(
        id: Long,
        text: String,
        pendingLabel: String?,
        isSelf: Boolean = true,
        serverTime: Long = 0,
    ) = MessageEntity(
        id = id,
        bufferId = 1,
        msgid = null,
        serverTime = serverTime,
        sender = "me",
        kind = MessageKind.PRIVMSG,
        text = text,
        isSelf = isSelf,
        pendingLabel = pendingLabel,
        dedupKey = "d$id",
    )
}
