package io.github.trevarj.motd.data.history

import io.github.trevarj.motd.data.db.HistoryGapEntity
import io.github.trevarj.motd.data.db.MessageEntity
import io.github.trevarj.motd.data.db.MessageKind
import io.github.trevarj.motd.data.db.TimelineAnchor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Placement rules for [timelineSeams] and [seamAbove].
 *
 * Three invariants dominate this file:
 *  1. A seam's position is the WINDOW lower-bound projection of the gap's newer edge, never the
 *     focus-selection one. An unidentifiable edge therefore sits at its cohort FLOOR: below every
 *     equal-timestamp row, so the seam is drawn under the whole cohort and cannot vanish off the
 *     top of the buffer.
 *  2. `(olderNeighbor, row]` is half-open at the top: a seam exactly on a row's anchor belongs
 *     above THAT row.
 *  3. A null older neighbor is a Paging placeholder, not the start of history. The answer is
 *     undecidable, so [seamAbove] abstains rather than guessing.
 */
class TimelineSeamsTest {

    private fun row(serverTime: Long, id: Long, timelineOrder: Long = id) = MessageEntity(
        id = id,
        bufferId = 1,
        serverTime = serverTime,
        sender = "alice",
        kind = MessageKind.PRIVMSG,
        text = "t",
        dedupKey = "dedup-$id",
        timelineOrder = timelineOrder,
    )

    private fun resolvedGap(
        id: Long,
        newer: GapEdgeAnchor,
        recoverable: Boolean = true,
        older: GapEdgeAnchor = GapEdgeAnchor.Exact(TimelineAnchor(0, 0)),
    ) = ResolvedGap(
        gap = HistoryGapEntity(
            id = id,
            roomId = 1,
            olderMsgid = null,
            olderServerTime = 0,
            newerMsgid = null,
            newerServerTime = 0,
            recoverable = recoverable,
        ),
        older = older,
        newer = newer,
    )

    /**
     * Walks [rows] the way the reversed list renders them (index 0 newest, the older neighbor one
     * index further down) and returns the row id each seam actually attached to.
     *
     * The last row's neighbor is null, matching a real page whose next item has not materialized.
     */
    private fun placements(
        rows: List<MessageEntity>,
        seams: List<TimelineSeam>,
    ): List<Pair<Long, Long>> = rows.mapIndexedNotNull { index, r ->
        seamAbove(r, rows.getOrNull(index + 1), seams)?.let { r.id to it.gapId }
    }

    // --- projection choice ----------------------------------------------------------------------

    @Test
    fun seamPositionIsTheWindowLowerBoundProjectionNotTheFocusOne() {
        val edge = GapEdgeAnchor.TimeOnly(500)

        val position = timelineSeams(listOf(resolvedGap(id = 7, newer = edge))).single().position

        assertEquals(edge.asInclusiveLowerBound(), position)
        // Same edge, opposite sentinel. Harmonizing the two roles re-opens the e91698a0 defect.
        assertNotEquals(edge.asFocusNewerPosition(), position)
    }

    @Test
    fun exactEdgeCarriesItsAnchorThroughUnchanged() {
        val anchor = TimelineAnchor(500, 42, 7)

        assertEquals(
            listOf(TimelineSeam(gapId = 3, position = anchor, recoverable = true)),
            timelineSeams(listOf(resolvedGap(id = 3, newer = GapEdgeAnchor.Exact(anchor)))),
        )
    }

    // --- cohort placement -----------------------------------------------------------------------

    @Test
    fun timeOnlyEdgePlacesTheSeamBelowItsWholeEqualTimeCohort() {
        // Three rows share serverTime 500; the gap's newer edge is unidentifiable at that same
        // timestamp. The seam must land under all three, not between any two of them.
        val rows = listOf(row(500, 12), row(500, 11), row(500, 10), row(400, 9))
        val seams = timelineSeams(listOf(resolvedGap(id = 1, newer = GapEdgeAnchor.TimeOnly(500))))

        assertEquals(listOf(10L to 1L), placements(rows, seams))
    }

    @Test
    fun aTimeOnlySeamAtTheNewestCohortStaysVisible() {
        // The failure mode a ceiling sentinel produces: the cohort is the newest thing in the
        // buffer, so a ceiling position would sit above every materialized row, match no interval,
        // and silently disappear. The floor keeps it attached to the oldest row of the cohort.
        val rows = listOf(row(500, 12), row(500, 11), row(400, 10), row(300, 9))
        val gap = resolvedGap(id = 1, newer = GapEdgeAnchor.TimeOnly(500))

        assertEquals(listOf(11L to 1L), placements(rows, timelineSeams(listOf(gap))))
    }

    @Test
    fun exactEdgeSitsExactlyBetweenTheTwoAdjacentRows() {
        val rows = listOf(row(600, 11), row(500, 10), row(400, 9))
        val seams = timelineSeams(
            listOf(resolvedGap(id = 4, newer = GapEdgeAnchor.Exact(TimelineAnchor(500, 10, 10)))),
        )

        // Above row 10 (the seam's own anchor), and nowhere else.
        assertEquals(listOf(10L to 4L), placements(rows, seams))
        assertNull(seamAbove(rows[0], rows[1], seams))
    }

    // --- half-open boundary ---------------------------------------------------------------------

    @Test
    fun aSeamExactlyOnARowAnchorBelongsAboveThatRowNotTheNextOne() {
        val older = row(400, 9)
        val onIt = row(500, 10)
        val newer = row(600, 11)
        val seams = timelineSeams(
            listOf(resolvedGap(id = 2, newer = GapEdgeAnchor.Exact(TimelineAnchor(500, 10, 10)))),
        )

        assertEquals(2L, seamAbove(onIt, older, seams)?.gapId)
        assertNull(seamAbove(newer, onIt, seams))
    }

    @Test
    fun aSeamStrictlyAboveTheOlderNeighborIsNotClaimedByThatNeighbor() {
        // Mirror of the rule at the bottom of the interval: the lower end is exclusive, so a seam
        // sitting on the older neighbor's anchor belongs to the neighbor's own slot, not this one.
        val evenOlder = row(300, 8)
        val older = row(400, 9)
        val newer = row(500, 10)
        val seams = timelineSeams(
            listOf(resolvedGap(id = 5, newer = GapEdgeAnchor.Exact(TimelineAnchor(400, 9, 9)))),
        )

        assertNull(seamAbove(newer, older, seams))
        assertEquals(5L, seamAbove(older, evenOlder, seams)?.gapId)
    }

    // --- placeholder abstention -----------------------------------------------------------------

    @Test
    fun aPlaceholderOlderNeighborAbstainsInsteadOfGuessing() {
        val target = row(500, 10)
        val seams = timelineSeams(
            listOf(resolvedGap(id = 6, newer = GapEdgeAnchor.Exact(TimelineAnchor(500, 10, 10)))),
        )

        // The seam WOULD attach here once the neighbor materializes...
        assertEquals(6L, seamAbove(target, row(400, 9), seams)?.gapId)
        // ...but with an unloaded placeholder below, the interval has no known lower end.
        assertNull(seamAbove(target, null, seams))
    }

    @Test
    fun aPlaceholderNeighborAbstainsEvenWhenNoSeamCouldPossiblyMatch() {
        // Abstention is unconditional: it is a statement about the missing neighbor, not a search
        // that happened to come up empty.
        assertNull(seamAbove(row(500, 10), null, emptyList()))
    }

    // --- clamp into the presented list ----------------------------------------------------------

    @Test
    fun aSeamAboveEveryPresentedRowIsClampedOntoTheNewestOne() {
        // The rows on this gap's newer side exist in Room but the visibility filter removed them —
        // a reconnect catch-up page that is all JOIN/QUIT under smart presence. Raw geometry puts
        // the seam at a row the reader cannot see, so it matches no interval and vanishes.
        val presented = listOf(row(400, 9), row(300, 8))
        val gap = resolvedGap(id = 1, newer = GapEdgeAnchor.Exact(TimelineAnchor(500, 12, 12)))

        assertEquals(emptyList<Pair<Long, Long>>(), placements(presented, timelineSeams(listOf(gap))))

        val clamped = timelineSeams(listOf(gap), newestPresented = TimelineAnchor(400, 9, 9))

        assertEquals(TimelineAnchor(400, 9, 9), clamped.single().position)
        assertEquals(listOf(9L to 1L), placements(presented, clamped))
    }

    @Test
    fun aSeamTheFilterDidNotStrandKeepsItsExactPosition() {
        // The clamp is a minimum, not a projection: a seam with any presented row at or above it is
        // already placeable and must not be dragged down into the wrong slot.
        val presented = listOf(row(600, 11), row(500, 10), row(400, 9))
        val gap = resolvedGap(id = 4, newer = GapEdgeAnchor.Exact(TimelineAnchor(500, 10, 10)))

        val clamped = timelineSeams(listOf(gap), newestPresented = TimelineAnchor(600, 11, 11))

        assertEquals(TimelineAnchor(500, 10, 10), clamped.single().position)
        assertEquals(listOf(10L to 4L), placements(presented, clamped))
    }

    @Test
    fun theClampDoesNotSwitchAnUnidentifiableEdgeToTheFocusConvention() {
        // Clamping answers "where can this be drawn", which is a different question from which
        // sentinel an unlocatable edge takes. A ceiling here would split the equal-time cohort.
        val edge = GapEdgeAnchor.TimeOnly(500)

        val position = timelineSeams(
            listOf(resolvedGap(id = 7, newer = edge)),
            newestPresented = TimelineAnchor(900, 20, 20),
        ).single().position

        assertEquals(edge.asInclusiveLowerBound(), position)
        assertNotEquals(edge.asFocusNewerPosition(), position)
    }

    @Test
    fun anAbsentPresentedCeilingLeavesTheRawGeometryAlone() {
        // A caller that cannot say what is presented gets exactly what it always got.
        val gap = resolvedGap(id = 3, newer = GapEdgeAnchor.Exact(TimelineAnchor(500, 12, 12)))

        assertEquals(
            timelineSeams(listOf(gap)),
            timelineSeams(listOf(gap), newestPresented = null),
        )
    }

    // --- multiple gaps --------------------------------------------------------------------------

    @Test
    fun multipleGapsProduceOrderedSeamsAndEachRowMatchesAtMostOne() {
        val rows = listOf(row(900, 14), row(800, 13), row(500, 12), row(400, 11), row(200, 10), row(100, 9))
        // Deliberately supplied newest-first so the ordering cannot come from the input.
        val seams = timelineSeams(
            listOf(
                resolvedGap(id = 2, newer = GapEdgeAnchor.Exact(TimelineAnchor(800, 13, 13))),
                resolvedGap(id = 1, newer = GapEdgeAnchor.Exact(TimelineAnchor(400, 11, 11))),
            ),
        )

        assertEquals(listOf(1L, 2L), seams.map { it.gapId })
        assertTrue(seams[0].position < seams[1].position)

        val placed = placements(rows, seams)
        assertEquals(listOf(13L to 2L, 11L to 1L), placed)
        // Non-overlapping: no row collected two seams, and no seam was drawn twice.
        assertEquals(placed.size, placed.map { it.first }.distinct().size)
        assertEquals(placed.size, placed.map { it.second }.distinct().size)
    }

    @Test
    fun twoGapsInOneSlotCollapseToTheLowerSeamDeterministically() {
        // Adjacent materialized rows with more than one gap between them: both describe the same
        // visual break, so the answer must not depend on the order the gaps were read.
        val rows = listOf(row(900, 14), row(100, 9))
        val a = resolvedGap(id = 8, newer = GapEdgeAnchor.Exact(TimelineAnchor(300, 3, 3)))
        val b = resolvedGap(id = 9, newer = GapEdgeAnchor.Exact(TimelineAnchor(600, 6, 6)))

        assertEquals(8L, seamAbove(rows[0], rows[1], timelineSeams(listOf(a, b)))?.gapId)
        assertEquals(8L, seamAbove(rows[0], rows[1], timelineSeams(listOf(b, a)))?.gapId)
    }

    // --- unrecoverable gaps ---------------------------------------------------------------------

    @Test
    fun anUnrecoverableGapStillProducesASeam() {
        // Suppressing it is the defect this model exists to fix: an unrecoverable gap used to clamp
        // the window and hide the user's own stored history behind a break that can never close.
        val rows = listOf(row(600, 11), row(500, 10), row(400, 9))
        val seams = timelineSeams(
            listOf(
                resolvedGap(
                    id = 1,
                    newer = GapEdgeAnchor.Exact(TimelineAnchor(500, 10, 10)),
                    recoverable = false,
                ),
            ),
        )

        assertEquals(1, seams.size)
        assertFalse(seams.single().recoverable)
        assertEquals(listOf(10L to 1L), placements(rows, seams))
    }

    @Test
    fun recoverabilityIsCarriedPerGapNotCollapsed() {
        val seams = timelineSeams(
            listOf(
                resolvedGap(id = 1, newer = GapEdgeAnchor.Exact(TimelineAnchor(400, 9, 9)), recoverable = false),
                resolvedGap(id = 2, newer = GapEdgeAnchor.Exact(TimelineAnchor(800, 13, 13))),
            ),
        )

        assertEquals(listOf(false, true), seams.map { it.recoverable })
    }

    // --- degenerate inputs ----------------------------------------------------------------------

    @Test
    fun noGapsProduceNoSeams() {
        assertEquals(emptyList<TimelineSeam>(), timelineSeams(emptyList()))
        assertNull(seamAbove(row(500, 10), row(400, 9), emptyList()))
    }
}
