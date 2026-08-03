package io.github.trevarj.motd.ui.chat

import androidx.paging.PagingSource
import androidx.sqlite.db.SimpleSQLiteQuery
import io.github.trevarj.motd.data.db.MessageEntity
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.HistoryGapEntity
import io.github.trevarj.motd.data.db.buffer
import io.github.trevarj.motd.data.db.inMemoryDb
import io.github.trevarj.motd.data.db.message
import io.github.trevarj.motd.data.db.network
import io.github.trevarj.motd.data.repo.ChatHistoryMediatorFactory
import io.github.trevarj.motd.data.repo.HistoryWindowFocus
import io.github.trevarj.motd.data.repo.MessageRepositoryImpl
import io.github.trevarj.motd.data.visibility.MessageVisibilityPolicy
import io.github.trevarj.motd.data.visibility.MessageVisibilityReader
import io.github.trevarj.motd.data.visibility.MessageVisibilitySpec
import io.github.trevarj.motd.data.visibility.MessageWindowBounds
import io.github.trevarj.motd.data.visibility.messagePagingQuery
import io.github.trevarj.motd.service.resolveAndAdvanceCurrentReadTarget
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Regression cover for the viewport mark-read defect: `ChatContent`'s mark-read effect reads
 * `isAtEffectiveBottom` from the CURRENT paging window but acknowledges `rawNewestAnchor`, the
 * newest row of the whole room. Inside a bounded `HistoryWindowFocus.Around` island (any deep jump
 * — notification tap, search hit, permalink — that lands below a retained history gap) the island's
 * index 0 is not the room's newest row, so reaching the island's bottom used to mark every newer
 * unread message read and upload a MARKREAD.
 *
 * Both tests run the real gate ([shouldMarkReadFromViewport]) over bounds derived from the real
 * repository and a window materialized by the real PagingSource, then let the real
 * [resolveAndAdvanceCurrentReadTarget] run exactly when the gate permits it.
 */
@RunWith(RobolectricTestRunner::class)
@OptIn(androidx.paging.ExperimentalPagingApi::class)
class BoundedIslandMarkReadTest {
    private lateinit var db: MotdDatabase
    private var networkId = 0L
    private var roomId = 0L
    private val spec = MessageVisibilitySpec()
    private val policy = MessageVisibilityPolicy(spec)

    // Older island (below the gap).
    private var older1 = 0L
    private var older2 = 0L

    // Newer island (above the gap) — genuinely unread, never displayed in the Around window.
    private var newer1 = 0L
    private var newest = 0L

    @Before
    fun setUp() = runTest {
        db = inMemoryDb()
        networkId = db.networkDao().insert(network())
        roomId = db.bufferDao().insert(buffer(networkId, "#chan"))
        older1 = insert("old-1", serverTime = 1_000)
        older2 = insert("old-2", serverTime = 1_010)
        newer1 = insert("new-1", serverTime = 5_000)
        newest = insert("new-2", serverTime = 5_010)
        // A retained gap between the two islands, exactly as reconnect catch-up records one.
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
    private suspend fun loadWindow(bounds: MessageWindowBounds): Pair<List<MessageEntity>, Int> {
        val page = db.messageDao().pagingSource(
            messagePagingQuery(
                roomId,
                spec,
                lowerBoundary = bounds.lowerBoundary,
                upperBoundary = bounds.upperBoundary,
            ),
        ).load(
            PagingSource.LoadParams.Refresh(key = null, loadSize = 50, placeholdersEnabled = true),
        ) as PagingSource.LoadResult.Page<Int, MessageEntity>
        val itemCount = page.itemsBefore.coerceAtLeast(0) + page.data.size +
            page.itemsAfter.coerceAtLeast(0)
        return page.data to itemCount
    }

    private suspend fun unreadCount(): Int = db.messageDao().rawCount(
        SimpleSQLiteQuery(
            "SELECT COUNT(*) FROM buffers b JOIN messages m ON m.bufferId = b.id " +
                "WHERE b.id = ? AND m.serverTime > COALESCE(b.localReadAnchorTime, 0)",
            arrayOf(roomId),
        ),
    )

    /** Same derivation the screen collects from ChatViewModel.hasNewerHistoryIsland. */
    private fun hasNewerHistoryIsland(focus: HistoryWindowFocus, bounds: MessageWindowBounds) =
        focus is HistoryWindowFocus.Around && bounds.upperBoundary != null

    @Test
    fun `island bottom leaves the newer island unread`() = runTest {
        val repository = repository()
        val focus: HistoryWindowFocus = HistoryWindowFocus.Around(
            serverTime = 1_000,
            eventId = older1,
            timelineOrder = older1,
        )
        val bounds = repository.historyWindowBounds(roomId, focus)

        // The Around window is capped by the gap: newer rows are NOT part of this paging window.
        // This is exactly the state ChatViewModel.hasNewerHistoryIsland reports as true, which the
        // UI already uses to keep the scroll-to-newest FAB visible (ChatScreen.kt:1636).
        assertNotNull("Around window must be capped by the gap", bounds.upperBoundary)
        assertTrue(hasNewerHistoryIsland(focus, bounds))

        val (islandRows, itemCount) = loadWindow(bounds)
        assertEquals(listOf("old-2", "old-1"), islandRows.map { it.text })

        // The user scrolls to the visual bottom of the island: index 0, offset 0.
        val atBottom = isAtEffectiveBottom(
            firstVisibleIndex = 0,
            firstVisibleOffset = 0,
            itemCount = itemCount,
            peek = { index -> islandRows.getOrNull(index) },
            policy = policy,
        )
        assertTrue("island bottom still reads as at-bottom to the window", atBottom)

        // ...but the anchor the effect would mark read with is the room's newest row, above the gap.
        val rawNewest = MessageVisibilityReader(db).latestRawAnchor(roomId)
        assertEquals(newest, rawNewest?.eventId)

        // Run the production sequence: the gate decides, then the real read-target advance.
        val acknowledges = shouldMarkReadFromViewport(
            atBottom = atBottom,
            hasNewerHistoryIsland = hasNewerHistoryIsland(focus, bounds),
            initialPositionSettled = true,
            viewportReadEnabled = true,
        )
        assertFalse(
            "the bottom of a bounded island must not acknowledge the room's newest row",
            acknowledges,
        )
        if (acknowledges) resolveAndAdvanceCurrentReadTarget(db, roomId, rawNewest!!)

        assertNull(
            "durable read anchor must not advance past the gap",
            db.bufferDao().observeById(roomId)?.localReadAnchorTime,
        )
        assertEquals("nothing in the room is silently marked read", 4, unreadCount())
    }

    @Test
    fun `recent window bottom still marks the whole room read`() = runTest {
        val repository = repository()
        val focus: HistoryWindowFocus = HistoryWindowFocus.Recent
        val bounds = repository.historyWindowBounds(roomId, focus)

        // The Recent window is open at the top, so its index 0 really is the room's newest row.
        assertNull("Recent window must not be capped", bounds.upperBoundary)
        assertFalse(hasNewerHistoryIsland(focus, bounds))

        val (recentRows, itemCount) = loadWindow(bounds)
        assertEquals(listOf("new-2", "new-1"), recentRows.map { it.text })

        val atBottom = isAtEffectiveBottom(
            firstVisibleIndex = 0,
            firstVisibleOffset = 0,
            itemCount = itemCount,
            peek = { index -> recentRows.getOrNull(index) },
            policy = policy,
        )
        assertTrue(atBottom)

        val rawNewest = MessageVisibilityReader(db).latestRawAnchor(roomId)
        val acknowledges = shouldMarkReadFromViewport(
            atBottom = atBottom,
            hasNewerHistoryIsland = hasNewerHistoryIsland(focus, bounds),
            initialPositionSettled = true,
            viewportReadEnabled = true,
        )
        assertTrue("an unbounded window at bottom still acknowledges", acknowledges)

        val resolved = resolveAndAdvanceCurrentReadTarget(db, roomId, rawNewest!!)
        assertEquals(newest, resolved?.anchor?.eventId)
        assertEquals(5_010L, db.bufferDao().observeById(roomId)?.localReadAnchorTime)
        assertEquals("reaching the real bottom clears unread", 0, unreadCount())
    }

    /**
     * The resume leg of the same defect: the mark-read effect is keyed on `viewportReadEnabled`, so
     * coming back restarts it, and by then the backlog that arrived while the screen was paused is
     * already in `rawNewestAnchor` and in the Paging snapshot while nothing has measured it.
     */
    @Test
    fun `a resumed viewport acknowledges the rendered bottom, not the paused backlog`() = runTest {
        val repository = repository()
        val focus: HistoryWindowFocus = HistoryWindowFocus.Recent
        val bounds = repository.historyWindowBounds(roomId, focus)
        val (renderedRows, itemCount) = loadWindow(bounds)

        // What the timeline was showing, proved by the laid-out row's own key rather than by an
        // index that a later prepend would silently reassign.
        val rendered = renderedBottomAnchor(
            renderedIndex = 0,
            renderedKey = renderedRows[0].id,
            itemCount = itemCount,
            peek = { index -> renderedRows.getOrNull(index) },
            policy = policy,
        )
        assertEquals(newest, rendered?.eventId)

        // Two messages land while the screen is paused. No measure pass runs for them.
        insert("paused-1", serverTime = 6_000)
        val pausedNewest = insert("paused-2", serverTime = 6_010)
        val rawNewest = MessageVisibilityReader(db).latestRawAnchor(roomId)
        assertEquals(pausedNewest, rawNewest?.eventId)

        // At-bottom is still measured against the pre-pause layout, so the gate itself still opens:
        // the anchor, not the gate, is what stops the unseen backlog being acknowledged.
        val atBottom = isAtEffectiveBottom(
            firstVisibleIndex = 0,
            firstVisibleOffset = 0,
            itemCount = itemCount,
            peek = { index -> renderedRows.getOrNull(index) },
            policy = policy,
        )
        assertTrue(
            shouldMarkReadFromViewport(
                atBottom = atBottom,
                hasNewerHistoryIsland = hasNewerHistoryIsland(focus, bounds),
                initialPositionSettled = true,
                viewportReadEnabled = true,
            ),
        )
        val acknowledged = checkNotNull(
            viewportMarkReadAnchor(rawNewest = rawNewest, renderedNewest = rendered, resumed = true),
        )
        assertEquals(newest, acknowledged.eventId)

        resolveAndAdvanceCurrentReadTarget(db, roomId, acknowledged)
        assertEquals(5_010L, db.bufferDao().observeById(roomId)?.localReadAnchorTime)
        assertEquals("the backlog that arrived while paused stays unread", 2, unreadCount())
    }
}
