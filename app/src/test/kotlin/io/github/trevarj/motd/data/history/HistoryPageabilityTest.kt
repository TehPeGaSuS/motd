package io.github.trevarj.motd.data.history

import io.github.trevarj.motd.data.db.HistoryGapEntity
import io.github.trevarj.motd.irc.client.ChatHistoryReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every terminal branch of [olderPageability] and [newerPageability], plus the [advancedFrom]
 * asymmetry in both directions.
 *
 * The `End(reason)` strings are a diagnostics contract (`end_reason`), so they are asserted as
 * literals here rather than referenced through a constant: a rename must break this file.
 */
class HistoryPageabilityTest {

    private fun gap(
        id: Long = 7,
        recoverable: Boolean = true,
        olderMsgid: String? = "older",
        olderServerTime: Long = 100,
        newerMsgid: String? = "newer",
        newerServerTime: Long = 500,
    ) = HistoryGapEntity(
        id = id,
        roomId = 1,
        olderMsgid = olderMsgid,
        olderServerTime = olderServerTime,
        newerMsgid = newerMsgid,
        newerServerTime = newerServerTime,
        recoverable = recoverable,
    )

    private fun ref(msgid: String?, serverTime: Long?) = ChatHistoryReference(msgid, serverTime)

    private fun older(
        focusedGap: HistoryGapEntity? = null,
        historyComplete: Boolean = false,
        cursorOldest: ChatHistoryReference? = null,
        oldestLocalRow: ChatHistoryReference? = null,
        progress: PageProgress? = null,
        gapFloor: ChatHistoryReference? = null,
    ) = olderPageability(focusedGap, historyComplete, cursorOldest, oldestLocalRow, progress, gapFloor)

    // --- older direction: terminal branches -----------------------------------------------------

    @Test
    fun olderEndsBeforeFetchingWhenTheFocusedGapIsAlreadyUnrecoverable() {
        val result = older(focusedGap = gap(recoverable = false), oldestLocalRow = ref("row", 900))

        assertEquals(Pageability.End("unrecoverable_focused_gap"), result)
    }

    @Test
    fun olderEndsAfterAPageWhenTheRemainingFocusedGapBecameUnrecoverable() {
        // Same condition, different classification: the fetch itself proved the remainder empty.
        val result = older(
            focusedGap = gap(recoverable = false),
            progress = PageProgress(previous = ref("row", 900), insertedCount = 1),
        )

        assertEquals(Pageability.End("exhausted_focused_gap"), result)
    }

    @Test
    fun olderEndsWhenHistoryIsCompleteAndNoFocusedGapRemains() {
        assertEquals(Pageability.End("history_complete"), older(historyComplete = true))
        assertEquals(
            Pageability.End("history_complete"),
            older(historyComplete = true, progress = PageProgress(ref("row", 900), insertedCount = 2)),
        )
    }

    @Test
    fun aRecoverableFocusedGapOutranksTheHistoryCompleteFlag() {
        // Reaching the start of history says nothing about an interior interval.
        val result = older(focusedGap = gap(), historyComplete = true)

        assertEquals(Pageability.Page(ref("newer", 500), focusedGapId = 7), result)
    }

    @Test
    fun anUnrecoverableGapOutranksProgressEvenWhenTheBoundaryReceded() {
        val result = older(
            focusedGap = gap(recoverable = false, newerServerTime = 200),
            progress = PageProgress(previous = ref("newer", 500), insertedCount = 3),
        )

        assertEquals(Pageability.End("exhausted_focused_gap"), result)
    }

    // --- older direction: the boundary ladder ---------------------------------------------------

    @Test
    fun theFocusedGapsNewerEdgeOutranksTheCursorAndTheOldestLocalRow() {
        val result = older(
            focusedGap = gap(),
            cursorOldest = ref("cursor", 50),
            oldestLocalRow = ref("row", 10),
        )

        assertEquals(Pageability.Page(ref("newer", 500), focusedGapId = 7), result)
    }

    @Test
    fun theUngappedLadderTakesTheOldestOfTheCursorAndTheOldestLocalRow() {
        // Not a preference order: whichever names the older point wins, in both directions. A
        // reconnect LATEST page unions its own oldest row into the stored cursor, so on a store that
        // was empty before the disconnect the cursor can end up NEWER than rows already held, and
        // paging BEFORE it would re-request an interval that is already durable.
        assertEquals(
            Pageability.Page(ref("row", 10), focusedGapId = null),
            older(cursorOldest = ref("cursor", 50), oldestLocalRow = ref("row", 10)),
        )
        assertEquals(
            Pageability.Page(ref("cursor", 5), focusedGapId = null),
            older(cursorOldest = ref("cursor", 5), oldestLocalRow = ref("row", 10)),
        )
    }

    @Test
    fun anEmptyCursorFallsThroughToTheOldestLocalRow() {
        // A cursor row can exist with neither field set; it is not a usable selector.
        val result = older(cursorOldest = ref(null, null), oldestLocalRow = ref("row", 10))

        assertEquals(Pageability.Page(ref("row", 10), focusedGapId = null), result)
    }

    @Test
    fun aCursorWithOnlyATimestampIsStillUsable() {
        val result = older(cursorOldest = ref(null, 50), oldestLocalRow = ref("row", 90))

        assertEquals(Pageability.Page(ref(null, 50), focusedGapId = null), result)
    }

    @Test
    fun aCursorWithNoServerTimeCannotBeOrderedAndYields() {
        // A bare msgid names an event whose position only the server knows, so it cannot be compared
        // against a timestamped boundary; the orderable one is taken rather than guessing.
        val result = older(cursorOldest = ref("cursor", null), oldestLocalRow = ref("row", 10))

        assertEquals(Pageability.Page(ref("row", 10), focusedGapId = null), result)
    }

    @Test
    fun noBoundaryAtAllSeedsTheNewestPage() {
        // Fresh or cleared store: with SKIP_INITIAL_REFRESH this is where backfill actually starts.
        assertEquals(Pageability.SeedLatest, older())
    }

    // --- older direction: the gap floor ----------------------------------------------------------

    @Test
    fun theGapFloorClampsAnUngappedLadderStrictlyBelowTheGap() {
        // The reconnect shape: the cursor sits exactly ON the gap's newer edge, so the ungapped
        // ladder would issue the identical request the gap's own fill issues. Clamping to the gap's
        // older edge puts it strictly below the interval instead — BEFORE is strictly-older-than, so
        // the two demand sources can no longer name the same rows.
        val result = older(
            cursorOldest = ref("newer", 500),
            oldestLocalRow = ref("newer", 500),
            gapFloor = ref("older", 100),
        )

        assertEquals(Pageability.Page(ref("older", 100), focusedGapId = null), result)
    }

    @Test
    fun theGapFloorNeverRaisesTheLadderAboveDeeperLocalHistory() {
        // A deep island below the gap: clamping UP to the gap's older edge would re-request rows the
        // client already holds, whose zero inserts the anti-livelock rule then reads as terminal.
        val result = older(oldestLocalRow = ref("deep", 5), gapFloor = ref("older", 100))

        assertEquals(Pageability.Page(ref("deep", 5), focusedGapId = null), result)
    }

    @Test
    fun theGapFloorDoesNotApplyToAGapDirectedCaller() {
        // The fill IS the gap-directed caller; clamping it below its own gap would fetch the one
        // interval it is not there for.
        val result = older(focusedGap = gap(), gapFloor = ref("older", 100))

        assertEquals(Pageability.Page(ref("newer", 500), focusedGapId = 7), result)
    }

    @Test
    fun theGapFloorIsTheOldestOlderEdgeAcrossEveryOpenGap() {
        assertEquals(null, openGapFloor(emptyList()))
        assertEquals(
            ref("older", 100),
            openGapFloor(
                listOf(
                    gap(id = 1, olderMsgid = "mid", olderServerTime = 300, newerServerTime = 400),
                    gap(id = 2, olderMsgid = "older", olderServerTime = 100),
                ),
            ),
        )
        // Recoverability is not filtered on: a server-proven-empty interval still belongs to its gap
        // and re-requesting it would return nothing, which the ungapped ladder reads as terminal.
        assertEquals(
            ref("older", 100),
            openGapFloor(listOf(gap(recoverable = false))),
        )
    }

    // --- older direction: the progress rule ------------------------------------------------------

    @Test
    fun olderEndsWhenNothingLandedAndTheBoundaryDidNotMove() {
        val boundary = ref("newer", 500)
        val result = older(
            focusedGap = gap(),
            progress = PageProgress(previous = boundary, insertedCount = 0),
        )

        assertEquals(Pageability.End("no_append_progress"), result)
    }

    @Test
    fun olderContinuesWhenRowsLandedEvenThoughTheBoundaryDidNotMove() {
        val result = older(
            focusedGap = gap(),
            progress = PageProgress(previous = ref("newer", 500), insertedCount = 1),
        )

        assertEquals(Pageability.Page(ref("newer", 500), focusedGapId = 7), result)
    }

    @Test
    fun olderContinuesWhenTheBoundaryRecededEvenThoughNothingLanded() {
        // A saturated equal-timestamp page edge stops THIS fetch, not the direction: the next
        // request is different, so the ambiguity that stopped this page no longer applies.
        val result = older(
            focusedGap = gap(newerMsgid = null, newerServerTime = 300),
            progress = PageProgress(previous = ref(null, 500), insertedCount = 0),
        )

        assertEquals(Pageability.Page(ref(null, 300), focusedGapId = 7), result)
    }

    @Test
    fun olderEndsWhenBothTheOldAndNewBoundariesAreAbsent() {
        val result = older(progress = PageProgress(previous = null, insertedCount = 0))

        assertEquals(Pageability.End("no_append_progress"), result)
    }

    @Test
    fun losingAKnownBoundaryEntirelyCountsAsAChangedRequest() {
        // null vs non-null is a genuine difference, so the direction stays alive and the next load
        // seeds the newest page instead of repeating a BEFORE it can no longer build.
        val result = older(progress = PageProgress(previous = ref("row", 900), insertedCount = 0))

        assertEquals(Pageability.SeedLatest, result)
    }

    // --- newer direction ------------------------------------------------------------------------

    @Test
    fun newerEndsWhenNoFocusedGapExists() {
        // Covers both "Recent focus selects nothing" before a fetch and "the page closed the gap"
        // after one; either way there is no interval left to catch up on.
        assertEquals(Pageability.End("newer_gap_closed"), newerPageability(null, progress = null))
        assertEquals(
            Pageability.End("newer_gap_closed"),
            newerPageability(null, PageProgress(ref("older", 100), insertedCount = 4)),
        )
    }

    @Test
    fun newerEndsOnAnUnrecoverableGapWithTheSamePrePostSplitAsTheOlderDirection() {
        assertEquals(
            Pageability.End("unrecoverable_focused_gap"),
            newerPageability(gap(recoverable = false), progress = null),
        )
        assertEquals(
            Pageability.End("exhausted_focused_gap"),
            newerPageability(gap(recoverable = false), PageProgress(ref("older", 100), insertedCount = 0)),
        )
    }

    @Test
    fun newerPagesAfterTheFocusedGapsOlderEdge() {
        assertEquals(
            Pageability.Page(ref("older", 100), focusedGapId = 7),
            newerPageability(gap(), progress = null),
        )
    }

    @Test
    fun newerEndsWhenNothingLandedAndTheBoundaryDidNotMove() {
        val result = newerPageability(
            gap(),
            PageProgress(previous = ref("older", 100), insertedCount = 0),
        )

        assertEquals(Pageability.End("no_prepend_progress"), result)
    }

    @Test
    fun newerContinuesWhenRowsLandedEvenThoughTheBoundaryDidNotMove() {
        val result = newerPageability(
            gap(),
            PageProgress(previous = ref("older", 100), insertedCount = 2),
        )

        assertEquals(Pageability.Page(ref("older", 100), focusedGapId = 7), result)
    }

    @Test
    fun newerContinuesWhenTheBoundaryAdvancedEvenThoughNothingLanded() {
        val result = newerPageability(
            gap(olderMsgid = null, olderServerTime = 150),
            PageProgress(previous = ref(null, 100), insertedCount = 0),
        )

        assertEquals(Pageability.Page(ref(null, 150), focusedGapId = 7), result)
    }

    @Test
    fun newerNeverSeedsTheNewestPage() {
        // Unlike the older direction, a missing boundary is never a reason to pull LATEST: without a
        // gap edge there is no interval to catch up on at all.
        listOf(null, gap(), gap(recoverable = false)).forEach { focused ->
            listOf(null, PageProgress(ref("older", 100), 0), PageProgress(null, 3)).forEach { progress ->
                assertNotEquals(Pageability.SeedLatest, newerPageability(focused, progress))
            }
        }
    }

    // --- advancedFrom: the asymmetry -------------------------------------------------------------

    @Test
    fun gainingAMsgidAtAnUnchangedTimestampIsAnAdvance() {
        assertTrue(ref("m", 500).advancedFrom(ref(null, 500)))
    }

    @Test
    fun changingTheMsgidAtAnUnchangedTimestampIsAnAdvance() {
        assertTrue(ref("b", 500).advancedFrom(ref("a", 500)))
    }

    @Test
    fun losingAMsgidAtAnUnchangedTimestampIsNotAnAdvance() {
        // The asymmetric case. A timestamp-only wire strips advertised msgid references, so both
        // boundaries serialize to the same timestamp selector and the next request would repeat the
        // identical interval. A plain `!=` here livelocks the direction.
        assertFalse(ref(null, 500).advancedFrom(ref("a", 500)))
    }

    @Test
    fun aChangedTimestampIsAlwaysAnAdvance() {
        assertTrue(ref(null, 400).advancedFrom(ref("a", 500)))
        assertTrue(ref("a", 400).advancedFrom(ref("a", 500)))
    }

    @Test
    fun anIdenticalBoundaryIsNeverAnAdvance() {
        assertFalse(ref("a", 500).advancedFrom(ref("a", 500)))
        assertFalse(ref(null, 500).advancedFrom(ref(null, 500)))
        assertFalse(ref(null, null).advancedFrom(ref(null, null)))
    }

    @Test
    fun aNullBoundaryOnEitherSideIsComparedByPresenceAlone() {
        val absent: ChatHistoryReference? = null

        assertTrue(absent.advancedFrom(ref("a", 500)))
        assertTrue(ref("a", 500).advancedFrom(null))
        assertFalse(absent.advancedFrom(null))
    }

    @Test
    fun theMsgidStripAsymmetryHoldsInBothPagingDirections() {
        // Same wire event, both directions: the boundary keeps its timestamp and loses its msgid, so
        // neither direction may call it progress.
        val strippedOlder = older(
            focusedGap = gap(newerMsgid = null, newerServerTime = 500),
            progress = PageProgress(previous = ref("newer", 500), insertedCount = 0),
        )
        val strippedNewer = newerPageability(
            gap(olderMsgid = null, olderServerTime = 100),
            PageProgress(previous = ref("older", 100), insertedCount = 0),
        )

        assertEquals(Pageability.End("no_append_progress"), strippedOlder)
        assertEquals(Pageability.End("no_prepend_progress"), strippedNewer)

        // The mirror image — a msgid APPEARING at the same timestamp — keeps both directions alive.
        assertEquals(
            Pageability.Page(ref("newer", 500), focusedGapId = 7),
            older(
                focusedGap = gap(newerMsgid = "newer", newerServerTime = 500),
                progress = PageProgress(previous = ref(null, 500), insertedCount = 0),
            ),
        )
        assertEquals(
            Pageability.Page(ref("older", 100), focusedGapId = 7),
            newerPageability(
                gap(olderMsgid = "older", olderServerTime = 100),
                PageProgress(previous = ref(null, 100), insertedCount = 0),
            ),
        )
    }
}
