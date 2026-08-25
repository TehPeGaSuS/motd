package io.github.trevarj.motd.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How a catch-up pass decides what to fetch, in what order, and what to leave alone.
 *
 * Every case here is a claim about wire traffic or about what the user watches happen, so they are
 * expressed against the plan rather than against a pass: the pass cannot be observed making these
 * decisions without also standing up a transport.
 */
class CatchUpWavePlanTest {
    private fun target(
        id: Long?,
        name: String,
        latest: Long? = null,
        pinned: Boolean = false,
    ) = SyncTarget(id, name, latest, pinned)

    @Test
    fun `a room with no cursor is always changed`() {
        // A fresh JOIN, a first sync, a room that has only ever seen live messages: nothing has ever
        // been fetched for it, so skipping it would leave it permanently empty.
        assertTrue(targetChanged(advertisedLatest = null, cursorNewest = null, hasCursor = false))
        assertTrue(targetChanged(advertisedLatest = 500, cursorNewest = null, hasCursor = false))
    }

    @Test
    fun `a room discovery did not mention has not changed`() {
        // Discovery enumerated the whole window and said nothing about it.
        assertFalse(targetChanged(advertisedLatest = null, cursorNewest = 400, hasCursor = true))
    }

    @Test
    fun `changed compares the advertisement against the cursor with second tolerance`() {
        assertTrue(targetChanged(advertisedLatest = 5_000, cursorNewest = 3_000, hasCursor = true))
        assertFalse(targetChanged(advertisedLatest = 3_000, cursorNewest = 5_000, hasCursor = true))
        // Stored server-time tags can carry second precision while TARGETS advertises milliseconds:
        // the same second means the page that would be fetched is already local.
        assertFalse(targetChanged(advertisedLatest = 3_999, cursorNewest = 3_000, hasCursor = true))
        assertTrue(targetChanged(advertisedLatest = 4_000, cursorNewest = 3_999, hasCursor = true))
    }

    @Test
    fun `unchanged rooms are settled instead of fetched`() {
        val plan =
            planCatchUpWaves(
                candidates =
                    listOf(
                        CatchUpCandidate(target(1, "#quiet"), changed = false),
                        CatchUpCandidate(target(2, "#busy", latest = 900), changed = true),
                        // Never mentioned by discovery and never fetched by this device: nothing to settle,
                        // because a room the pass does not know cannot be wearing a status.
                        CatchUpCandidate(target(null, "stranger"), changed = false),
                    ),
                foregroundBufferId = null,
            )

        assertEquals(listOf("#busy"), plan.waveOne.map { it.name })
        assertEquals(emptyList<String>(), plan.waveTwo.map { it.name })
        assertEquals(listOf(1L), plan.settledUnchanged)
    }

    @Test
    fun `wave one leads with the visible chat then pinned then the most recent advertisement`() {
        val plan =
            planCatchUpWaves(
                candidates =
                    listOf(
                        CatchUpCandidate(target(1, "#old", latest = 100), changed = true),
                        CatchUpCandidate(target(2, "#newest", latest = 900), changed = true),
                        CatchUpCandidate(target(3, "#pinned", latest = 200, pinned = true), changed = true),
                        CatchUpCandidate(target(4, "#visible", latest = 50), changed = true),
                        // No advertisement at all (an open buffer with no cursor): it still has to be
                        // fetched, but it sorts behind everything discovery actually described.
                        CatchUpCandidate(target(5, "#unknown"), changed = true),
                    ),
                foregroundBufferId = 4,
            )

        assertEquals(
            listOf("#visible", "#pinned", "#newest", "#old", "#unknown"),
            plan.waveOne.map { it.name },
        )
    }

    @Test
    fun `massive account keeps the visible wave bounded and the overflow ordered`() {
        val candidates =
            (1..10_000).map { index ->
                CatchUpCandidate(
                    target(index.toLong(), "#chan$index", latest = (10_001 - index).toLong()),
                    changed = true,
                )
            }

        val plan = planCatchUpWaves(candidates, foregroundBufferId = null)

        assertEquals(WAVE_ONE_LIMIT, plan.waveOne.size)
        assertEquals(10_000 - WAVE_ONE_LIMIT, plan.waveTwo.size)
        assertEquals("#chan${WAVE_ONE_LIMIT + 1}", plan.waveTwo.first().name)
        assertEquals("#chan10000", plan.waveTwo.last().name)
        assertEquals(emptyList<Long>(), plan.settledUnchanged)
    }

    @Test
    fun `the default bound is two rounds of the starting fan-out width`() {
        assertEquals(2 * AdaptiveFanOut.INITIAL_WIDTH, WAVE_ONE_LIMIT)
    }
}
