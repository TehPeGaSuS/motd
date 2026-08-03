package io.github.trevarj.motd.ui.chat

import androidx.paging.PagingSource
import io.github.trevarj.motd.data.db.HistoryGapEntity
import io.github.trevarj.motd.data.db.MessageEntity
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.buffer
import io.github.trevarj.motd.data.db.inMemoryDb
import io.github.trevarj.motd.data.db.message
import io.github.trevarj.motd.data.db.network
import io.github.trevarj.motd.data.repo.ChatHistoryMediatorFactory
import io.github.trevarj.motd.data.repo.HistoryWindowFocus
import io.github.trevarj.motd.data.repo.MessageRepositoryImpl
import io.github.trevarj.motd.data.visibility.MessageVisibilitySpec
import io.github.trevarj.motd.data.visibility.MessageWindowBounds
import io.github.trevarj.motd.data.visibility.messagePagingQuery
import io.github.trevarj.motd.ui.components.HistoryGapState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The seam pipeline is wired end to end — repository → ViewModel state → [rowSeam] → divider — and
 * is nevertheless DARK: under today's window bounds no seam can reach a rendered slot.
 *
 * Both halves are proved here against the same real store:
 *  - the dark property, over windows materialized by the real repository bounds and the real paging
 *    query, under both Recent and a deep-jump Around focus;
 *  - the lit property, over an unbounded snapshot of the identical rows, where the seam lands in
 *    exactly one slot and carries the state its gap implies.
 *
 * The mechanism is not a filter anywhere in the UI. It is geometry: today's bounds clamp the window
 * AT the gap, so the row adjacent to a seam has no materialized older neighbor, and `seamAbove`
 * abstains on an undecidable slot. Widening the bounds is the next stage; the day it lands, the lit
 * tests below are what the timeline starts doing.
 */
@RunWith(RobolectricTestRunner::class)
@OptIn(androidx.paging.ExperimentalPagingApi::class)
class TimelineSeamPresentationTest {
    private lateinit var db: MotdDatabase
    private var roomId = 0L

    private val spec = MessageVisibilitySpec()

    // Older island (below the gap).
    private var older1 = 0L
    private var older2 = 0L

    // Newer island (above the gap).
    private var newer1 = 0L
    private var newest = 0L

    @Before
    fun setUp() = runTest {
        db = inMemoryDb()
        val networkId = db.networkDao().insert(network())
        roomId = db.bufferDao().insert(buffer(networkId, "#chan"))
        older1 = insert("old-1", serverTime = 1_000)
        older2 = insert("old-2", serverTime = 1_010)
        newer1 = insert("new-1", serverTime = 5_000)
        newest = insert("new-2", serverTime = 5_010)
        // A retained gap between the two islands, exactly as reconnect catch-up records one. Both
        // edges resolve exactly, so nothing here depends on the cohort-sentinel rules.
        db.historyGapDao().insert(
            HistoryGapEntity(
                roomId = roomId,
                olderMsgid = null,
                olderServerTime = 1_010,
                olderEventId = older2,
                olderTimelineOrder = older2,
                newerMsgid = null,
                newerServerTime = 5_000,
                newerEventId = newer1,
                newerTimelineOrder = newer1,
            ),
        )
    }

    @After
    fun tearDown() = db.close()

    private suspend fun insert(text: String, serverTime: Long): Long =
        db.messageDao().insertAll(
            listOf(message(roomId, text, serverTime = serverTime, dedupKey = text)),
        ).single()

    private fun repository() = MessageRepositoryImpl(
        bufferDao = db.bufferDao(),
        networkIdentityDao = db.networkIdentityDao(),
        messageDao = db.messageDao(),
        reactionDao = db.reactionDao(),
        mediatorFactory = ChatHistoryMediatorFactory { _, _ -> error("paging not exercised") },
        historyGapDao = db.historyGapDao(),
    )

    /** Materialize a window exactly as the timeline does (reverse layout: index 0 = newest). */
    private suspend fun loadWindow(bounds: MessageWindowBounds): List<MessageEntity> =
        (
            db.messageDao().pagingSource(
                messagePagingQuery(
                    roomId,
                    spec,
                    lowerBoundary = bounds.lowerBoundary,
                    upperBoundary = bounds.upperBoundary,
                ),
            ).load(
                PagingSource.LoadParams.Refresh(key = null, loadSize = 50, placeholdersEnabled = true),
            ) as PagingSource.LoadResult.Page<Int, MessageEntity>
            ).data

    /**
     * Walk the window the way [MessageList] composes it: index 0 is newest, the older neighbor is
     * the NEXT index, and the last row's neighbor is null because nothing below it is materialized.
     * Returns `(row text, rendered seam)` for every slot that draws one.
     */
    private fun renderedSeams(
        rows: List<MessageEntity>,
        seams: TimelineSeamState,
    ): List<Pair<String, RowSeam>> = rows.mapIndexedNotNull { index, row ->
        rowSeam(row, rows.getOrNull(index + 1), seams)?.let { row.text to it }
    }

    private suspend fun seamState(filling: Set<Long> = emptySet()) =
        TimelineSeamState(repository().observeTimelineSeams(roomId).first(), filling)

    // --- the wiring exists ------------------------------------------------------------------------

    @Test
    fun `the repository publishes a seam for the stored gap`() = runTest {
        val state = seamState()

        // Everything below asserts about placement; without this the dark tests could pass simply
        // because nothing ever reached the UI.
        val seam = state.seams.single()
        assertEquals(newer1, seam.position.eventId)
        assertTrue("a fillable gap must publish a recoverable seam", seam.recoverable)
    }

    // --- dark: no seam can render under today's bounds ---------------------------------------------

    @Test
    fun `the recent window renders no seam`() = runTest {
        val bounds = repository().historyWindowBounds(roomId, HistoryWindowFocus.Recent)
        val rows = loadWindow(bounds)
        assertEquals(listOf("new-2", "new-1"), rows.map { it.text })

        assertEquals(emptyList<Pair<String, RowSeam>>(), renderedSeams(rows, seamState()))

        // Why, precisely: the window's lower bound IS the seam's position, so the only row the seam
        // could attach to is the oldest one in the window, whose older neighbor is not materialized.
        assertEquals(bounds.lowerBoundary, seamState().seams.single().position)
        assertNull(
            "an unmaterialized older neighbor makes the slot undecidable, so the seam abstains",
            rowSeam(rows.last(), null, seamState()),
        )
    }

    @Test
    fun `a deep-jump island renders no seam either`() = runTest {
        // A notification/search/permalink jump below the gap: the window is capped by the gap's
        // older edge, so the seam sits above everything the island materialized.
        val focus = HistoryWindowFocus.Around(serverTime = 1_000, eventId = older1, timelineOrder = older1)
        val bounds = repository().historyWindowBounds(roomId, focus)
        assertNotNull("the Around window must be capped by the gap", bounds.upperBoundary)

        val rows = loadWindow(bounds)
        assertEquals(listOf("old-2", "old-1"), rows.map { it.text })

        assertEquals(emptyList<Pair<String, RowSeam>>(), renderedSeams(rows, seamState()))
    }

    @Test
    fun `no rendered slot is added or moved by the seam wiring`() = runTest {
        val repository = repository()
        val bounds = repository.historyWindowBounds(roomId, HistoryWindowFocus.Recent)
        val rows = loadWindow(bounds)

        // A seam is drawn INSIDE its row's composition, never as its own list item, so the
        // "countNewerThan == list index" contract ChatJumpResolver depends on is untouched: every
        // row's index in the presented window is still exactly its count of strictly-newer rows.
        rows.forEachIndexed { index, row ->
            assertEquals(
                "row ${row.text} must keep its jump index",
                index,
                repository.countNewerThan(roomId, row.serverTime, row.id, spec, HistoryWindowFocus.Recent),
            )
        }
    }

    // --- lit: the same wiring over an unbounded snapshot -------------------------------------------

    @Test
    fun `an unbounded snapshot draws the seam above the first row after the gap`() = runTest {
        val rows = loadWindow(MessageWindowBounds())
        assertEquals(listOf("new-2", "new-1", "old-2", "old-1"), rows.map { it.text })

        val rendered = renderedSeams(rows, seamState())

        // Exactly one slot, and it is the row at the gap's newer edge — above its bubble, below the
        // last row on the far side of the gap.
        assertEquals(1, rendered.size)
        assertEquals("new-1", rendered.single().first)
        assertEquals(HistoryGapState.Recoverable, rendered.single().second.state)
    }

    @Test
    fun `a fill in flight renders that seam as loading`() = runTest {
        val rows = loadWindow(MessageWindowBounds())
        val gapId = seamState().seams.single().gapId

        val rendered = renderedSeams(rows, seamState(filling = setOf(gapId))).single()

        assertEquals("new-1", rendered.first)
        assertEquals(gapId, rendered.second.gapId)
        assertEquals(HistoryGapState.Loading, rendered.second.state)
    }

    @Test
    fun `an unrelated fill leaves this seam tappable`() = runTest {
        val rows = loadWindow(MessageWindowBounds())
        val gapId = seamState().seams.single().gapId

        val rendered = renderedSeams(rows, seamState(filling = setOf(gapId + 1))).single()

        assertEquals(HistoryGapState.Recoverable, rendered.second.state)
    }

    @Test
    fun `an unrecoverable gap renders the permanent seam`() = runTest {
        val stored = db.historyGapDao().forRoom(roomId).single()
        db.historyGapDao().update(stored.copy(recoverable = false))
        val rows = loadWindow(MessageWindowBounds())

        // Still a seam: suppressing it is what used to hide the user's own stored history behind a
        // break that can never close. It simply stops offering a fill.
        val rendered = renderedSeams(rows, seamState()).single()
        assertEquals("new-1", rendered.first)
        assertEquals(HistoryGapState.Unrecoverable, rendered.second.state)
    }

    @Test
    fun `an unrecoverable gap ignores a stale in-flight id`() = runTest {
        val stored = db.historyGapDao().forRoom(roomId).single()
        db.historyGapDao().update(stored.copy(recoverable = false))
        val rows = loadWindow(MessageWindowBounds())

        val rendered = renderedSeams(rows, seamState(filling = setOf(stored.id))).single()

        assertEquals(HistoryGapState.Unrecoverable, rendered.second.state)
    }

    @Test
    fun `a closed gap removes its seam`() = runTest {
        db.historyGapDao().delete(db.historyGapDao().forRoom(roomId).single().id)
        val rows = loadWindow(MessageWindowBounds())

        val state = seamState()
        assertTrue("a filled gap publishes no seam", state.seams.isEmpty())
        assertEquals(emptyList<Pair<String, RowSeam>>(), renderedSeams(rows, state))
    }
}
