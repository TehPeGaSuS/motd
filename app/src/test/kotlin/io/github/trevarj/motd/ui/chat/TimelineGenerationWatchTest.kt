package io.github.trevarj.motd.ui.chat

import io.github.trevarj.motd.diagnostics.formatDiagnosticLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The generation watch is an arbitration instrument: it exists so a red run names which of the
 * three competing explanations for timeline churn actually happened. These pin the classification
 * and the journal's own constraints, because a field that is silently redacted or a fate that is
 * mislabelled makes the instrument worse than nothing.
 */
class TimelineGenerationWatchTest {
    private fun window(
        itemCount: Int,
        placeholdersBefore: Int,
        ids: List<Long>,
    ) = TimelineWindow(itemCount, placeholdersBefore, ids)

    @Test
    fun windowBoundsDescribeTheLoadedRunInPresentationIndices() {
        val w = window(itemCount = 500, placeholdersBefore = 100, ids = (100L..249L).toList())

        assertEquals(150, w.loadedCount)
        assertEquals(100, w.loadedFirstIndex)
        assertEquals(249, w.loadedLastIndex)
        assertEquals(120, w.indexOf(120L))
        assertEquals(120L, w.idAt(120))
        assertEquals("a row outside the window has no index", -1, w.indexOf(42L))
        assertEquals("a placeholder slot has no id", null, w.idAt(42))
        assertEquals("a slot past the window has no id", null, w.idAt(400))
    }

    @Test
    fun emptyWindowHasNoBounds() {
        val w = window(itemCount = 500, placeholdersBefore = 0, ids = emptyList())

        assertEquals(-1, w.loadedFirstIndex)
        assertEquals(-1, w.loadedLastIndex)
        assertEquals(null, w.idAt(0))
    }

    @Test
    fun anchorFateSeparatesTheThreeExplanations() {
        val loaded = window(itemCount = 500, placeholdersBefore = 100, ids = (100L..249L).toList())

        // The viewport was parked on a placeholder to begin with: there is no identity to follow,
        // and no conclusion to draw about the presentation.
        assertEquals(TimelineAnchorFate.NO_ANCHOR, timelineAnchorFate(null, loaded))
        // A transient empty snapshot. Distinct from "the anchor became a placeholder" because the
        // remedies are opposite: one is a Paging generation problem, the other a window placement.
        assertEquals(
            TimelineAnchorFate.EMPTY,
            timelineAnchorFate(120L, window(itemCount = 0, placeholdersBefore = 0, ids = emptyList())),
        )
        // Still loaded: Compose's own key map re-anchors, and nothing here needs to act.
        assertEquals(TimelineAnchorFate.LOADED, timelineAnchorFate(120L, loaded))
        // Loaded a moment ago, a placeholder now: key anchoring is dead for exactly this transition.
        assertEquals(TimelineAnchorFate.PLACEHOLDER, timelineAnchorFate(42L, loaded))
    }

    @Test
    fun generationFieldsReportWindowPlacementAnchorFateAndDrift() {
        val previous = window(itemCount = 260, placeholdersBefore = 0, ids = (0L..259L).toList())
        val current = window(itemCount = 310, placeholdersBefore = 100, ids = (100L..249L).toList())

        val fields =
            timelineGenerationFields(
                generation = 7,
                previous = previous,
                current = current,
                before = TimelineViewportAnchor(index = 40, offset = 12, key = 40L),
                after = TimelineViewportAnchor(index = 40, offset = 0, key = null),
                settled = true,
                scrolling = false,
                following = false,
            )

        assertEquals(7L, fields["generation"])
        assertEquals(310, fields["item_count"])
        assertEquals(100, fields["placeholders_before"])
        assertEquals(150, fields["loaded_count"])
        assertEquals(100, fields["loaded_first_index"])
        assertEquals(249, fields["loaded_last_index"])
        assertEquals(260, fields["prev_item_count"])
        assertEquals(0, fields["prev_placeholders_before"])
        assertEquals(260, fields["prev_loaded_count"])
        assertEquals(40, fields["before_index"])
        assertEquals(12, fields["before_offset"])
        assertEquals(40L, fields["before_key"])
        assertEquals(40, fields["after_index"])
        // The row the viewport is parked on has become a placeholder: no key, and the anchor is
        // nowhere in the new presentation's loaded window.
        assertEquals(-1L, fields["after_key"])
        assertEquals("placeholder", fields["anchor_fate"])
        assertEquals(-1, fields["anchor_index"])
        assertEquals("the viewport did not move; the window moved under it", 0, fields["anchor_drift"])
    }

    @Test
    fun generationFieldsReportDriftWhenTheAnchorIsStillLoaded() {
        val previous = window(itemCount = 260, placeholdersBefore = 0, ids = (0L..259L).toList())
        // Fifty newer rows arrived, so every retained row moved fifty slots older.
        val current = window(itemCount = 310, placeholdersBefore = 0, ids = (1000L..1049L).toList() + (0L..259L).toList())

        val fields =
            timelineGenerationFields(
                generation = 2,
                previous = previous,
                current = current,
                before = TimelineViewportAnchor(index = 40, offset = 12, key = 40L),
                after = TimelineViewportAnchor(index = 90, offset = 12, key = 40L),
                settled = true,
                scrolling = false,
                following = false,
            )

        assertEquals("loaded", fields["anchor_fate"])
        assertEquals("where the anchor row now is", 90, fields["anchor_index"])
        assertEquals("what the viewport actually did", 50, fields["anchor_drift"])
    }

    @Test
    fun noPriorPresentationReportsSentinelsRatherThanFabricatedHistory() {
        val current = window(itemCount = 50, placeholdersBefore = 0, ids = (0L..49L).toList())

        val fields =
            timelineGenerationFields(
                generation = 1,
                previous = null,
                current = current,
                before = TimelineViewportAnchor(index = 0, offset = 0, key = null),
                after = TimelineViewportAnchor(index = 0, offset = 0, key = 0L),
                settled = false,
                scrolling = false,
                following = true,
            )

        assertEquals(-1, fields["prev_item_count"])
        assertEquals(-1, fields["prev_loaded_count"])
        assertEquals(-1, fields["prev_loaded_first_index"])
        assertEquals("no_anchor", fields["anchor_fate"])
        assertEquals(-1L, fields["before_key"])
    }

    /**
     * The journal replaces the value of any field literally named `reason` with `[omitted]`, along
     * with a fixed list of user-data names. A watch field caught by that list would be silently
     * useless in the exported artifact, which is the failure mode this pins.
     */
    @Test
    fun everyWatchFieldSurvivesTheJournalsRedaction() {
        val fields =
            timelineGenerationFields(
                generation = 3,
                previous = window(itemCount = 10, placeholdersBefore = 0, ids = (0L..9L).toList()),
                current = window(itemCount = 10, placeholdersBefore = 0, ids = (0L..9L).toList()),
                before = TimelineViewportAnchor(index = 1, offset = 2, key = 1L),
                after = TimelineViewportAnchor(index = 1, offset = 2, key = 1L),
                settled = true,
                scrolling = false,
                following = false,
            )

        val line =
            formatDiagnosticLine(
                timestamp = "1970-01-01T00:00:00Z",
                sequence = 1,
                component = "chat_timeline",
                event = "generation_presented",
                fields = fields,
            )

        assertFalse("no watch field may be redacted", line.contains("[omitted]"))
        fields.keys.forEach { key ->
            assertTrue("field $key is in the line", line.contains(" $key="))
            assertEquals("field names are snake_case", key.lowercase(), key)
            assertFalse("field names carry no spaces", key.contains(' '))
        }
    }

    @Test
    fun tracePayloadFlattensEveryField() {
        val rendered = formatTimelineGenerationFields(mapOf("item_count" to 3, "anchor_fate" to "loaded"))

        assertEquals("item_count=3 anchor_fate=loaded", rendered)
    }
}
