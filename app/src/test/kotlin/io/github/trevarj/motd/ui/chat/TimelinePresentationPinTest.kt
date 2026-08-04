package io.github.trevarj.motd.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A scroll correction that fires wrongly IS the defect it exists to remove, so most of this file is
 * about the cases that must decline. Every test names the situation in the timeline that produces
 * it.
 *
 * Convention throughout: index 0 is the newest row (the list is `reverseLayout` over a newest-first
 * query), and ids are bare identities with no ordering meaning — the only ordering that matters is
 * presentation position, which is what conservation is measured in.
 */
class TimelinePresentationPinTest {

    private fun window(itemCount: Int, placeholdersBefore: Int, ids: List<Long>) =
        TimelineWindow(itemCount, placeholdersBefore, ids)

    private fun anchor(index: Int, key: Long?, offset: Int = 24) =
        TimelineViewportAnchor(index = index, offset = offset, key = key)

    /**
     * The reconnect catch-up, with the numbers the real thing produces.
     *
     * A viewport parked at index 300 of 500, its window covering 200..399. A hundred rows newer than
     * the whole viewport land, and the regenerated source re-places a 150-row window around Paging's
     * now-stale anchor position — which lands it 100 rows NEWER than where the reader is. The anchor
     * row is a placeholder, so its key has left the list and `LazyListState` keeps index 300; the
     * row the reader was looking at is at 400.
     */
    @Test
    fun catchUpThatShiftsEveryRowAndUnloadsTheAnchorIsPinnedToTheAnchorsNewIndex() {
        val previous = window(itemCount = 500, placeholdersBefore = 200, ids = (200L..399L).toList())
        val current = window(
            itemCount = 600,
            placeholdersBefore = 225,
            // 75 rows that were placeholders before (unknown identities), then the newest 75 rows the
            // old window did hold. Old index i is new index i + 100 throughout.
            ids = (1125L..1199L).toList() + (200L..274L).toList(),
        )

        val pin = timelinePresentationPin(anchor(index = 300, key = 300L), previous, current)

        assertEquals(TimelinePin(index = 400, offset = 24), pin)
    }

    @Test
    fun theRestoredOffsetIsTheOffsetTheViewportHeld() {
        val previous = window(itemCount = 500, placeholdersBefore = 200, ids = (200L..399L).toList())
        val current = window(
            itemCount = 600,
            placeholdersBefore = 225,
            ids = (1125L..1199L).toList() + (200L..274L).toList(),
        )

        val pin = timelinePresentationPin(anchor(index = 300, key = 300L, offset = 137), previous, current)

        assertEquals(137, pin?.offset)
    }

    /**
     * The safety property, stated as a test.
     *
     * An interior gap fill puts 50 rows BETWEEN the nearest conserved reference and the anchor, so
     * the shift measured at the reference is 10 when the anchor's own is 60. The pin lands at 260
     * rather than the true 310 — but 260 is strictly closer than the 250 the list holds today, and
     * it cannot overshoot: the changes newer than the reference are a subset of the changes newer
     * than the anchor, so the measured shift can only understate.
     */
    @Test
    fun aShiftMeasuredAcrossAnInteriorFillUndershootsAndNeverOvershoots() {
        val previous = window(itemCount = 300, placeholdersBefore = 0, ids = (0L..299L).toList())
        // Ten rows newer than everything, plus fifty filled into the interior below the window's
        // older edge — which is where the reference stops and the unmeasured span begins.
        val current = window(
            itemCount = 360,
            placeholdersBefore = 0,
            ids = (900L..909L).toList() + (0L..199L).toList(),
        )
        val trueIndex = 310
        val heldToday = 250

        val pin = timelinePresentationPin(anchor(index = heldToday, key = 250L), previous, current)!!

        assertEquals(260, pin.index)
        assertTrue("a pin never moves past the anchor", pin.index <= trueIndex)
        assertTrue(
            "a pin is never further from the anchor than doing nothing",
            trueIndex - pin.index < trueIndex - heldToday,
        )
    }

    /**
     * The nearest conserved row is the reference, because every row between it and the anchor is a
     * row that could have been inserted between them. Here the outermost conserved row would have
     * reported a shift of 10; the nearest one reports 60, which is the truth.
     */
    @Test
    fun theNearestConservedRowMeasuresTheShift() {
        val previous = window(itemCount = 300, placeholdersBefore = 0, ids = (0L..299L).toList())
        val current = window(
            itemCount = 360,
            placeholdersBefore = 0,
            ids = (900L..909L).toList() + (0L..59L).toList() + (700L..749L).toList() + (60L..199L).toList(),
        )

        val pin = timelinePresentationPin(anchor(index = 250, key = 250L), previous, current)

        assertEquals(TimelinePin(index = 310, offset = 24), pin)
    }

    // --- everything that must decline ------------------------------------------------------------

    @Test
    fun aViewportAlreadyOnAPlaceholderIsNotPinned() {
        val previous = window(itemCount = 500, placeholdersBefore = 200, ids = (200L..399L).toList())
        val current = window(itemCount = 600, placeholdersBefore = 225, ids = (1125L..1274L).toList())

        assertNull(timelinePresentationPin(anchor(index = 10, key = null), previous, current))
    }

    /**
     * The transient empty snapshot. There is no index to pin to, and the next presentation
     * re-establishes the list; moving the viewport to 0 here would BE the flicker, not the fix.
     */
    @Test
    fun anEmptyPresentationIsNotPinned() {
        val previous = window(itemCount = 500, placeholdersBefore = 200, ids = (200L..399L).toList())
        val current = window(itemCount = 0, placeholdersBefore = 0, ids = emptyList())

        assertNull(timelinePresentationPin(anchor(index = 300, key = 300L), previous, current))
    }

    @Test
    fun aPresentationWithNothingLoadedYetIsNotPinned() {
        val previous = window(itemCount = 500, placeholdersBefore = 200, ids = (200L..399L).toList())
        val current = window(itemCount = 600, placeholdersBefore = 0, ids = emptyList())

        assertNull(timelinePresentationPin(anchor(index = 300, key = 300L), previous, current))
    }

    /**
     * The overwhelmingly common case: a page loads, the anchor stays loaded, and `LazyListState`'s
     * own key map moves the viewport at measure time. Acting here would fight a correct mechanism.
     */
    @Test
    fun aStillLoadedAnchorIsLeftToComposesOwnKeyAnchoring() {
        val previous = window(itemCount = 500, placeholdersBefore = 200, ids = (200L..399L).toList())
        val current = window(itemCount = 500, placeholdersBefore = 250, ids = (250L..399L).toList())

        assertNull(timelinePresentationPin(anchor(index = 300, key = 300L), previous, current))
    }

    /**
     * A history backfill lands only OLDER rows, so no retained row moves. The anchor is a
     * placeholder purely because the window shrank around a different part of the timeline, and no
     * scroll can fix that — the index the list holds is already the right one.
     */
    @Test
    fun anOlderOnlyBackfillThatMovesNothingIsNotPinned() {
        val previous = window(itemCount = 260, placeholdersBefore = 0, ids = (0L..259L).toList())
        val current = window(itemCount = 310, placeholdersBefore = 0, ids = (0L..149L).toList())

        assertNull(timelinePresentationPin(anchor(index = 200, key = 200L), previous, current))
    }

    /** Nothing survived — a room switch, or a window re-placed with no overlap at all. */
    @Test
    fun aPresentationSharingNoRowWithTheLastOneIsNotPinned() {
        val previous = window(itemCount = 500, placeholdersBefore = 200, ids = (200L..399L).toList())
        val current = window(itemCount = 600, placeholdersBefore = 0, ids = (900L..1049L).toList())

        assertNull(timelinePresentationPin(anchor(index = 300, key = 300L), previous, current))
    }

    /**
     * Only rows OLDER than the anchor survive, so the window was re-placed deeper into history than
     * the reader is. A shift measured below the anchor would count rows inserted between the two as
     * the anchor's own and could push the viewport further from the row than leaving it alone does,
     * so this side is deliberately not used at all.
     */
    @Test
    fun aWindowRePlacedEntirelyOlderThanTheViewportIsNotPinned() {
        val previous = window(itemCount = 500, placeholdersBefore = 200, ids = (200L..399L).toList())
        val current = window(
            itemCount = 600,
            placeholdersBefore = 420,
            ids = (320L..399L).toList() + (1400L..1469L).toList(),
        )

        assertNull(timelinePresentationPin(anchor(index = 300, key = 300L), previous, current))
    }

    /**
     * The anchor row was DELETED, not unloaded — its key is gone from a fully loaded region. The
     * restored index would then name whichever row moved up into its slot, which is a different
     * message, so the correction is refused. A loaded target is the observable signature.
     */
    @Test
    fun anAnchorThatWasDeletedRatherThanUnloadedIsNotPinned() {
        val previous = window(itemCount = 300, placeholdersBefore = 0, ids = (0L..299L).toList())
        val current = window(
            itemCount = 309,
            placeholdersBefore = 0,
            ids = (900L..909L).toList() + (0L..249L).toList() + (251L..299L).toList(),
        )

        assertNull(timelinePresentationPin(anchor(index = 250, key = 250L), previous, current))
    }

    /** A target the new presentation cannot address is not a pin. */
    @Test
    fun aTargetPastTheEndOfTheNewPresentationIsRejected() {
        val previous = window(itemCount = 300, placeholdersBefore = 0, ids = (0L..299L).toList())
        // The presentation shrank and its window sits at the very end, so the only conserved row
        // newer than the anchor puts the anchor past the last addressable index.
        val current = window(itemCount = 300, placeholdersBefore = 290, ids = (100L..109L).toList())

        assertNull(timelinePresentationPin(anchor(index = 250, key = 250L), previous, current))
    }

    @Test
    fun anAnchorIndexOutsideTheMeasuredPresentationIsRejected() {
        val previous = window(itemCount = 100, placeholdersBefore = 0, ids = (0L..99L).toList())
        val current = window(
            itemCount = 150,
            placeholdersBefore = 0,
            ids = (900L..949L).toList() + (0L..49L).toList(),
        )

        assertNull(timelinePresentationPin(anchor(index = 500, key = 300L), previous, current))
    }
}
