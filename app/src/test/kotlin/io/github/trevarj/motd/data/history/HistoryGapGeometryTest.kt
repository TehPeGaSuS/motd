package io.github.trevarj.motd.data.history

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.data.db.BufferEntity
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.HistoryGapEntity
import io.github.trevarj.motd.data.db.MessageEntity
import io.github.trevarj.motd.data.db.MessageKind
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.db.TimelineAnchor
import io.github.trevarj.motd.data.repo.HistoryWindowFocus
import io.github.trevarj.motd.data.repo.ResolvedHistoryGap
import io.github.trevarj.motd.data.repo.historyWindowBounds
import io.github.trevarj.motd.data.visibility.MessageWindowBounds
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Geometry coverage for [GapEdgeAnchor], [GapAnchorResolver], [windowBounds], [focusedOlderGap] and
 * [focusedNewerGap].
 *
 * Two invariants dominate this file and are asserted deliberately rather than incidentally:
 *  1. [GapEdgeAnchor.TimeOnly] CARRIES its serverTime. Its projections are
 *     `(serverTime, MIN/MAX, MIN/MAX)`, never a pure sentinel, so an unidentifiable edge is
 *     permissive/dominant only inside its equal-time cohort and orders truthfully everywhere else.
 *  2. The window role and the focus-selection role take OPPOSITE sentinels for the same edge, and
 *     both are correct. Anyone who "simplifies" them into one convention should fail here loudly.
 */
@RunWith(RobolectricTestRunner::class)
class HistoryGapGeometryTest {
    private lateinit var db: MotdDatabase
    private lateinit var resolver: GapAnchorResolver
    private var roomId = 0L
    private var otherRoomId = 0L

    @Before fun setUp() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MotdDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val networkId = db.networkDao().insert(
            NetworkEntity(
                name = "libera",
                role = NetworkRole.DIRECT,
                host = "h",
                port = 6697,
                nick = "me",
                username = "me",
                realname = "Me",
            ),
        )
        db.bufferDao().insert(
            BufferEntity(networkId = networkId, name = "#chan", displayName = "#chan", type = BufferType.CHANNEL),
        )
        db.bufferDao().insert(
            BufferEntity(networkId = networkId, name = "#other", displayName = "#other", type = BufferType.CHANNEL),
        )
        roomId = db.bufferDao().byName(networkId, "#chan")!!.id
        otherRoomId = db.bufferDao().byName(networkId, "#other")!!.id
        resolver = GapAnchorResolver(db.messageDao())
    }

    @After fun tearDown() { db.close() }

    private suspend fun insertRow(
        msgid: String?,
        serverTime: Long,
        bufferId: Long = roomId,
    ): MessageEntity {
        val id = db.messageDao().insertAll(
            listOf(
                MessageEntity(
                    bufferId = bufferId,
                    msgid = msgid,
                    serverTime = serverTime,
                    sender = "alice",
                    kind = MessageKind.PRIVMSG,
                    text = "t",
                    dedupKey = "dedup-$bufferId-$msgid-$serverTime",
                ),
            ),
        ).single()
        return checkNotNull(db.messageDao().byId(id))
    }

    private fun gap(
        id: Long = 1,
        olderMsgid: String? = null,
        olderServerTime: Long = 100,
        newerMsgid: String? = null,
        newerServerTime: Long = 500,
        olderEventId: Long? = null,
        olderTimelineOrder: Long? = null,
        newerEventId: Long? = null,
        newerTimelineOrder: Long? = null,
    ) = HistoryGapEntity(
        id = id,
        roomId = roomId,
        olderMsgid = olderMsgid,
        olderServerTime = olderServerTime,
        newerMsgid = newerMsgid,
        newerServerTime = newerServerTime,
        olderEventId = olderEventId,
        olderTimelineOrder = olderTimelineOrder,
        newerEventId = newerEventId,
        newerTimelineOrder = newerTimelineOrder,
    )

    private fun resolved(
        id: Long = 1,
        older: GapEdgeAnchor = GapEdgeAnchor.Exact(TimelineAnchor(100, 100)),
        newer: GapEdgeAnchor = GapEdgeAnchor.Exact(TimelineAnchor(500, 500)),
    ) = ResolvedGap(gap(id = id), older, newer)

    // --- GapEdgeAnchor projections -------------------------------------------------------------

    @Test
    fun exactEdgeProjectsToTheSameAnchorInEveryRole() {
        val anchor = TimelineAnchor(500, 42, 7)
        val edge = GapEdgeAnchor.Exact(anchor)

        assertEquals(anchor, edge.asInclusiveLowerBound())
        assertEquals(anchor, edge.asInclusiveUpperBound())
        assertEquals(anchor, edge.asFocusNewerPosition())
        assertEquals(anchor, edge.asFocusOlderPosition())
    }

    @Test
    fun timeOnlyEdgeCarriesItsServerTimeIntoEveryProjection() {
        // If any projection dropped serverTime for a pure sentinel this fails: the whole point of
        // the fallback is that it stays inside the edge's own timestamp.
        val edge = GapEdgeAnchor.TimeOnly(500)

        listOf(
            edge.asInclusiveLowerBound(),
            edge.asInclusiveUpperBound(),
            edge.asFocusNewerPosition(),
            edge.asFocusOlderPosition(),
        ).forEach { assertEquals(500L, it.serverTime) }
    }

    @Test
    fun timeOnlyProjectionsUseTheExactSentinelsTheOldCodeUsed() {
        val edge = GapEdgeAnchor.TimeOnly(500)
        val floor = TimelineAnchor(500, Long.MIN_VALUE, Long.MIN_VALUE)
        val ceiling = TimelineAnchor(500, Long.MAX_VALUE, Long.MAX_VALUE)

        // Window roles: MessageRepositoryImpl.resolveHistoryGaps passed MIN for the `newer` edge
        // (lowerBoundary) and MAX for the `older` edge (upperBoundary).
        assertEquals(floor, edge.asInclusiveLowerBound())
        assertEquals(ceiling, edge.asInclusiveUpperBound())
        // Focus roles: ChatHistoryRemoteMediator.gapNewerAnchor fell back to MAX and gapOlderAnchor
        // to MIN — the exact opposite pairing.
        assertEquals(ceiling, edge.asFocusNewerPosition())
        assertEquals(floor, edge.asFocusOlderPosition())
    }

    @Test
    fun theTwoRoleConventionsDisagreeOnPurposeAndMustNotBeHarmonized() {
        val edge = GapEdgeAnchor.TimeOnly(500)

        // Same edge, same timestamp, opposite answers. The window wants an unknown edge NOT to
        // clamp (fixed in e91698a0); focus selection wants the unlocatable gap to WIN selection.
        assertNotEquals(edge.asInclusiveLowerBound(), edge.asFocusNewerPosition())
        assertNotEquals(edge.asInclusiveUpperBound(), edge.asFocusOlderPosition())
    }

    @Test
    fun timeOnlyEdgeStillOrdersTruthfullyAgainstOtherTimestamps() {
        // A pure Long.MIN_VALUE/MAX_VALUE anchor would dominate the whole timeline. Carrying the
        // serverTime confines the ambiguity to the equal-time cohort.
        val edge = GapEdgeAnchor.TimeOnly(500)

        assertTrue(edge.asFocusNewerPosition() < TimelineAnchor(501, Long.MIN_VALUE, Long.MIN_VALUE))
        assertTrue(edge.asFocusNewerPosition() > TimelineAnchor(499, Long.MAX_VALUE, Long.MAX_VALUE))
        assertTrue(edge.asInclusiveLowerBound() > TimelineAnchor(499, Long.MAX_VALUE, Long.MAX_VALUE))
        assertTrue(edge.asInclusiveLowerBound() < TimelineAnchor(501, Long.MIN_VALUE, Long.MIN_VALUE))
    }

    // --- GapAnchorResolver ladder --------------------------------------------------------------

    @Test
    fun resolverPrefersTheRowCarryingTheEdgeMsgid() = runTest {
        val row = insertRow("edge", serverTime = 250)
        // The stored serverTime is deliberately wrong; the retained row must win outright.
        val gaps = resolver.resolve(roomId, listOf(gap(olderMsgid = "edge", olderServerTime = 999)))

        assertEquals(
            GapEdgeAnchor.Exact(TimelineAnchor(250, row.id, row.timelineOrder)),
            gaps.single().older,
        )
    }

    @Test
    fun resolverFallsBackToTheRetainedRowForTheEdgeEventId() = runTest {
        val row = insertRow("present", serverTime = 250)
        val gaps = resolver.resolve(
            roomId,
            listOf(gap(olderMsgid = "evicted", olderServerTime = 999, olderEventId = row.id)),
        )

        assertEquals(
            GapEdgeAnchor.Exact(TimelineAnchor(250, row.id, row.timelineOrder)),
            gaps.single().older,
        )
    }

    @Test
    fun resolverRejectsAnEventIdRowThatBelongsToAnotherRoom() = runTest {
        val foreign = insertRow("elsewhere", serverTime = 250, bufferId = otherRoomId)
        val gaps = resolver.resolve(
            roomId,
            listOf(gap(olderServerTime = 999, olderEventId = foreign.id, olderTimelineOrder = 3)),
        )

        // Falls through to the stored tuple rather than adopting a foreign room's position.
        assertEquals(GapEdgeAnchor.Exact(TimelineAnchor(999, foreign.id, 3)), gaps.single().older)
    }

    @Test
    fun resolverUsesTheStoredTupleWhenTheRowIsGone() = runTest {
        val gaps = resolver.resolve(
            roomId,
            listOf(gap(olderServerTime = 999, olderEventId = 4242, olderTimelineOrder = 17)),
        )

        assertEquals(GapEdgeAnchor.Exact(TimelineAnchor(999, 4242, 17)), gaps.single().older)
    }

    @Test
    fun resolverDefaultsAStoredTupleTimelineOrderToItsEventId() = runTest {
        val gaps = resolver.resolve(roomId, listOf(gap(olderServerTime = 999, olderEventId = 4242)))

        assertEquals(GapEdgeAnchor.Exact(TimelineAnchor(999, 4242, 4242)), gaps.single().older)
    }

    @Test
    fun resolverYieldsTimeOnlyWhenTheEdgeCannotBeIdentifiedAtAll() = runTest {
        val gaps = resolver.resolve(
            roomId,
            listOf(gap(olderMsgid = null, olderServerTime = 100, newerMsgid = "gone", newerServerTime = 500)),
        )

        assertEquals(GapEdgeAnchor.TimeOnly(100), gaps.single().older)
        assertEquals(GapEdgeAnchor.TimeOnly(500), gaps.single().newer)
        assertEquals(1L, gaps.single().gap.id)
    }

    // --- windowBounds: parity with the shipped repository implementation ------------------------

    /** The same two-gap fixture `HistoryWindowBoundsTest` uses, in both representations. */
    private val portedGaps = listOf(
        gap(id = 1, olderServerTime = 100, newerServerTime = 500),
        gap(id = 2, olderServerTime = 700, newerServerTime = 900),
    )

    private fun portedNew() = portedGaps.map {
        ResolvedGap(
            it,
            GapEdgeAnchor.Exact(TimelineAnchor(it.olderServerTime, it.olderServerTime)),
            GapEdgeAnchor.Exact(TimelineAnchor(it.newerServerTime, it.newerServerTime)),
        )
    }

    private fun portedOld() = portedGaps.map {
        ResolvedHistoryGap(
            it,
            TimelineAnchor(it.olderServerTime, it.olderServerTime),
            TimelineAnchor(it.newerServerTime, it.newerServerTime),
        )
    }

    @Test
    fun recentWindowNoLongerStartsAtTheNewestKnownIsland() {
        // The pinned inversion. The frozen reference still clamps at the newest gap's newer edge —
        // that is what it is for — and the module deliberately no longer does, because the timeline
        // is presented unbounded with a seam drawn at the gap instead of everything below it hidden.
        //
        // Asserted against the reference rather than against a bare `null` on purpose: a
        // windowBounds that stopped seeing gaps entirely would also return no boundary here, and
        // this pairing tells the two apart — the reference proves these fixtures DO contain a gap
        // that the old rule would have clamped on.
        val clamped = historyWindowBounds(HistoryWindowFocus.Recent, portedOld())
        assertEquals(MessageWindowBounds(lowerBoundary = TimelineAnchor(900, 900)), clamped)

        val presented = windowBounds(HistoryWindowFocus.Recent, portedNew())

        assertEquals(MessageWindowBounds(), presented)
        assertNotEquals(clamped, presented)
    }

    @Test
    fun focusedWindowIsBoundedByTheNearestGapInEachDirection() {
        val focus = HistoryWindowFocus.Around(600)
        val expected = MessageWindowBounds(
            lowerBoundary = TimelineAnchor(500, 500),
            upperBoundary = TimelineAnchor(700, 700),
        )

        assertEquals(expected, windowBounds(focus, portedNew()))
        assertEquals(historyWindowBounds(focus, portedOld()), windowBounds(focus, portedNew()))
    }

    @Test
    fun equalTimestampGapSeparatesOpaqueBoundaryAnchors() {
        // Both edges share serverTime 100 and are told apart only by their exact tuples. This is the
        // case a sloppy sentinel silently destroys, so it is ported verbatim.
        val entity = HistoryGapEntity(3, roomId, "older", 100, "newer", 100)
        val older = TimelineAnchor(100, 10, 10)
        val newer = TimelineAnchor(100, 20, 20)
        val new = listOf(ResolvedGap(entity, GapEdgeAnchor.Exact(older), GapEdgeAnchor.Exact(newer)))
        val old = listOf(ResolvedHistoryGap(entity, older, newer))
        val around = HistoryWindowFocus.Around(100, eventId = 10, timelineOrder = 10)

        assertEquals(MessageWindowBounds(upperBoundary = older), windowBounds(around, new))
        assertEquals(historyWindowBounds(around, old), windowBounds(around, new))
        // Recent diverges here too, and this fixture is the sharpest place to say so: the reference
        // separates the two equal-timestamp edges with an exact anchor, which is precisely the
        // clamp that used to hide the older edge's row. The module keeps both rows and lets the seam
        // fall between them.
        assertEquals(
            MessageWindowBounds(lowerBoundary = newer),
            historyWindowBounds(HistoryWindowFocus.Recent, old),
        )
        assertEquals(MessageWindowBounds(), windowBounds(HistoryWindowFocus.Recent, new))
    }

    @Test
    fun noGapsLeavesTheWindowUnbounded() {
        assertEquals(MessageWindowBounds(), windowBounds(HistoryWindowFocus.Recent, emptyList()))
        assertEquals(MessageWindowBounds(), windowBounds(HistoryWindowFocus.Around(600), emptyList()))
    }

    // --- windowBounds: the permissive (non-clamping) role of an unidentifiable edge -------------

    @Test
    fun unidentifiableNewerEdgeDoesNotClampItsEqualTimeCohortOutOfAFocusedWindow() {
        val cohortRow = TimelineAnchor(500, 42, 42)
        val gap = listOf(resolved(newer = GapEdgeAnchor.TimeOnly(500)))
        val bounds = windowBounds(HistoryWindowFocus.Around(900), gap)

        // Inclusive at the anchor, so a row AT the boundary is still presented. A MAX sentinel here
        // would exclude every equal-time row and could empty the window outright.
        assertTrue(checkNotNull(bounds.lowerBoundary) <= cohortRow)
        // Recent cannot be starved by this edge at all any more — it passes no boundary whatsoever,
        // so the sentinel choice cannot reach the presented timeline through that branch. The
        // projection itself still matters: `timelineSeams` takes the same one to place the seam.
        assertEquals(MessageWindowBounds(), windowBounds(HistoryWindowFocus.Recent, gap))
    }

    @Test
    fun unidentifiableOlderEdgeDoesNotClampItsEqualTimeCohortOutOfAFocusedWindow() {
        val cohortRow = TimelineAnchor(700, 42, 42)
        val bounds = windowBounds(
            HistoryWindowFocus.Around(600),
            listOf(resolved(older = GapEdgeAnchor.TimeOnly(700))),
        )

        assertTrue(checkNotNull(bounds.upperBoundary) >= cohortRow)
    }

    // --- focus selection: the dominant role of an unidentifiable edge ---------------------------

    @Test
    fun recentFocusSelectsTheNewestOlderGap() {
        val selected = focusedOlderGap(
            HistoryWindowFocus.Recent,
            listOf(
                resolved(id = 1, newer = GapEdgeAnchor.Exact(TimelineAnchor(500, 500))),
                resolved(id = 2, newer = GapEdgeAnchor.Exact(TimelineAnchor(900, 900))),
            ),
        )

        assertEquals(2L, selected?.gap?.id)
    }

    @Test
    fun aroundFocusSelectsTheNewestOlderGapAtOrBeforeItsAnchor() {
        val gaps = listOf(
            resolved(id = 1, newer = GapEdgeAnchor.Exact(TimelineAnchor(500, 500))),
            resolved(id = 2, newer = GapEdgeAnchor.Exact(TimelineAnchor(900, 900))),
        )

        assertEquals(1L, focusedOlderGap(HistoryWindowFocus.Around(600), gaps)?.gap?.id)
        assertNull(focusedOlderGap(HistoryWindowFocus.Around(400), gaps))
    }

    @Test
    fun unidentifiableNewerEdgeWinsOlderGapSelectionAgainstAnExactEqualTimePeer() {
        // The opposite of the window role: the gap the client cannot locate is exactly the gap most
        // likely to still be hiding history, so it must be the one older paging works on.
        val exact = resolved(id = 1, newer = GapEdgeAnchor.Exact(TimelineAnchor(500, 42, 42)))
        val opaque = resolved(id = 2, newer = GapEdgeAnchor.TimeOnly(500))

        assertEquals(2L, focusedOlderGap(HistoryWindowFocus.Recent, listOf(exact, opaque))?.gap?.id)
        // Order-independent: the sentinel decides, not list position.
        assertEquals(2L, focusedOlderGap(HistoryWindowFocus.Recent, listOf(opaque, exact))?.gap?.id)
    }

    @Test
    fun recentFocusNeverSelectsANewerGap() {
        // Live events supply newer messages under Recent, so PREPEND has nothing to work on.
        assertNull(focusedNewerGap(HistoryWindowFocus.Recent, listOf(resolved())))
    }

    @Test
    fun aroundFocusSelectsTheNearestNewerGapAtOrAfterItsAnchor() {
        val gaps = listOf(
            resolved(id = 1, older = GapEdgeAnchor.Exact(TimelineAnchor(700, 700))),
            resolved(id = 2, older = GapEdgeAnchor.Exact(TimelineAnchor(900, 900))),
        )

        assertEquals(1L, focusedNewerGap(HistoryWindowFocus.Around(600), gaps)?.gap?.id)
        assertNull(focusedNewerGap(HistoryWindowFocus.Around(1000), gaps))
    }

    @Test
    fun unidentifiableOlderEdgeWinsNewerGapSelectionAgainstAnExactEqualTimePeer() {
        val exact = resolved(id = 1, older = GapEdgeAnchor.Exact(TimelineAnchor(700, 42, 42)))
        val opaque = resolved(id = 2, older = GapEdgeAnchor.TimeOnly(700))
        val focus = HistoryWindowFocus.Around(600)

        assertEquals(2L, focusedNewerGap(focus, listOf(exact, opaque))?.gap?.id)
        assertEquals(2L, focusedNewerGap(focus, listOf(opaque, exact))?.gap?.id)
    }

    @Test
    fun anUnidentifiableOlderEdgeExactlyAtTheAnchorStillQualifiesAsNewerGap() {
        // The default Around anchor is (serverTime, MIN, MIN) and the floor projection equals it, so
        // the inclusive `>=` filter keeps the gap. A MAX projection would also pass here, which is
        // why the sibling test above (an exact equal-time peer) is what actually pins the sentinel.
        val opaque = resolved(id = 2, older = GapEdgeAnchor.TimeOnly(600))

        assertEquals(2L, focusedNewerGap(HistoryWindowFocus.Around(600), listOf(opaque))?.gap?.id)
    }
}
