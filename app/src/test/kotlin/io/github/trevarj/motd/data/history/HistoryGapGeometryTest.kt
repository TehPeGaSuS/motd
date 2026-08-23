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
 * Geometry coverage for [GapEdgeAnchor], [GapAnchorResolver] and [newestPageableGap].
 *
 * Two invariants dominate this file and are asserted deliberately rather than incidentally:
 *  1. [GapEdgeAnchor.TimeOnly] CARRIES its serverTime. Its projections are
 *     `(serverTime, MIN/MAX, MIN/MAX)`, never a pure sentinel, so an unidentifiable edge is
 *     permissive/dominant only inside its equal-time cohort and orders truthfully everywhere else.
 *  2. The seam-cut role and the selection role take OPPOSITE sentinels for the same edge, and both
 *     are correct. Anyone who "simplifies" them into one convention should fail here loudly.
 *
 * There is deliberately nothing here about window boundaries. Nothing derives a SQL window from a
 * gap any more — the timeline is one unbounded list with a seam drawn at the cut — so the pin for
 * that lives where it can actually fail: `TimelineSeamPresentationTest` and
 * `BoundedIslandMarkReadTest` load the REAL PagingSource across a stored gap and assert rows on
 * both sides of it are presented.
 */
@RunWith(RobolectricTestRunner::class)
class HistoryGapGeometryTest {
    private lateinit var db: MotdDatabase
    private lateinit var resolver: GapAnchorResolver
    private var roomId = 0L
    private var otherRoomId = 0L

    @Before fun setUp() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            db =
                Room
                    .inMemoryDatabaseBuilder(context, MotdDatabase::class.java)
                    .allowMainThreadQueries()
                    .build()
            val networkId =
                db.networkDao().insert(
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

    @After fun tearDown() {
        db.close()
    }

    private suspend fun insertRow(
        msgid: String?,
        serverTime: Long,
        bufferId: Long = roomId,
    ): MessageEntity {
        val id =
            db
                .messageDao()
                .insertAll(
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
        assertEquals(anchor, edge.asFocusNewerPosition())
    }

    @Test
    fun timeOnlyEdgeCarriesItsServerTimeIntoEveryProjection() {
        // If any projection dropped serverTime for a pure sentinel this fails: the whole point of
        // the fallback is that it stays inside the edge's own timestamp.
        val edge = GapEdgeAnchor.TimeOnly(500)

        listOf(
            edge.asInclusiveLowerBound(),
            edge.asFocusNewerPosition(),
        ).forEach { assertEquals(500L, it.serverTime) }
    }

    @Test
    fun timeOnlyProjectionsUseTheExactSentinelsTheOldCodeUsed() {
        val edge = GapEdgeAnchor.TimeOnly(500)
        val floor = TimelineAnchor(500, Long.MIN_VALUE, Long.MIN_VALUE)
        val ceiling = TimelineAnchor(500, Long.MAX_VALUE, Long.MAX_VALUE)

        // Cut role: MessageRepositoryImpl.resolveHistoryGaps passed MIN for the `newer` edge when it
        // was still a SQL lowerBoundary, and the seam inherited that projection unchanged.
        assertEquals(floor, edge.asInclusiveLowerBound())
        // Selection role: ChatHistoryRemoteMediator.gapNewerAnchor fell back to MAX — the opposite.
        assertEquals(ceiling, edge.asFocusNewerPosition())
    }

    @Test
    fun theTwoRoleConventionsDisagreeOnPurposeAndMustNotBeHarmonized() {
        val edge = GapEdgeAnchor.TimeOnly(500)

        // Same edge, same timestamp, opposite answers. The cut wants an unknown edge to claim
        // nothing (fixed in e91698a0); selection wants the unlocatable gap to WIN.
        assertNotEquals(edge.asInclusiveLowerBound(), edge.asFocusNewerPosition())
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
    fun resolverPrefersTheRowCarryingTheEdgeMsgid() =
        runTest {
            val row = insertRow("edge", serverTime = 250)
            // The stored serverTime is deliberately wrong; the retained row must win outright.
            val gaps = resolver.resolve(roomId, listOf(gap(olderMsgid = "edge", olderServerTime = 999)))

            assertEquals(
                GapEdgeAnchor.Exact(TimelineAnchor(250, row.id, row.timelineOrder)),
                gaps.single().older,
            )
        }

    @Test
    fun resolverFallsBackToTheRetainedRowForTheEdgeEventId() =
        runTest {
            val row = insertRow("present", serverTime = 250)
            val gaps =
                resolver.resolve(
                    roomId,
                    listOf(gap(olderMsgid = "evicted", olderServerTime = 999, olderEventId = row.id)),
                )

            assertEquals(
                GapEdgeAnchor.Exact(TimelineAnchor(250, row.id, row.timelineOrder)),
                gaps.single().older,
            )
        }

    @Test
    fun resolverRejectsAnEventIdRowThatBelongsToAnotherRoom() =
        runTest {
            val foreign = insertRow("elsewhere", serverTime = 250, bufferId = otherRoomId)
            val gaps =
                resolver.resolve(
                    roomId,
                    listOf(gap(olderServerTime = 999, olderEventId = foreign.id, olderTimelineOrder = 3)),
                )

            // Falls through to the stored tuple rather than adopting a foreign room's position.
            assertEquals(GapEdgeAnchor.Exact(TimelineAnchor(999, foreign.id, 3)), gaps.single().older)
        }

    @Test
    fun resolverUsesTheStoredTupleWhenTheRowIsGone() =
        runTest {
            val gaps =
                resolver.resolve(
                    roomId,
                    listOf(gap(olderServerTime = 999, olderEventId = 4242, olderTimelineOrder = 17)),
                )

            assertEquals(GapEdgeAnchor.Exact(TimelineAnchor(999, 4242, 17)), gaps.single().older)
        }

    @Test
    fun resolverDefaultsAStoredTupleTimelineOrderToItsEventId() =
        runTest {
            val gaps = resolver.resolve(roomId, listOf(gap(olderServerTime = 999, olderEventId = 4242)))

            assertEquals(GapEdgeAnchor.Exact(TimelineAnchor(999, 4242, 4242)), gaps.single().older)
        }

    @Test
    fun resolverYieldsTimeOnlyWhenTheEdgeCannotBeIdentifiedAtAll() =
        runTest {
            val gaps =
                resolver.resolve(
                    roomId,
                    listOf(gap(olderMsgid = null, olderServerTime = 100, newerMsgid = "gone", newerServerTime = 500)),
                )

            assertEquals(GapEdgeAnchor.TimeOnly(100), gaps.single().older)
            assertEquals(GapEdgeAnchor.TimeOnly(500), gaps.single().newer)
            assertEquals(1L, gaps.single().gap.id)
        }

    // --- fill selection: the dominant role of an unidentifiable edge ----------------------------

    @Test
    fun selectionTakesTheNewestGap() {
        val selected =
            newestPageableGap(
                listOf(
                    resolved(id = 1, newer = GapEdgeAnchor.Exact(TimelineAnchor(500, 500))),
                    resolved(id = 2, newer = GapEdgeAnchor.Exact(TimelineAnchor(900, 900))),
                ),
            )

        assertEquals(2L, selected?.gap?.id)
    }

    @Test
    fun selectionHasNothingToChooseInAGaplessRoom() {
        assertNull(newestPageableGap(emptyList()))
    }

    @Test
    fun unidentifiableNewerEdgeWinsSelectionAgainstAnExactEqualTimePeer() {
        // The opposite of the cut role: the gap the client cannot locate is exactly the gap most
        // likely to still be hiding history, so it must be the one a hands-free fill works on.
        val exact = resolved(id = 1, newer = GapEdgeAnchor.Exact(TimelineAnchor(500, 42, 42)))
        val opaque = resolved(id = 2, newer = GapEdgeAnchor.TimeOnly(500))

        assertEquals(2L, newestPageableGap(listOf(exact, opaque))?.gap?.id)
        // Order-independent: the sentinel decides, not list position.
        assertEquals(2L, newestPageableGap(listOf(opaque, exact))?.gap?.id)
    }

    @Test
    fun selectionRanksByTheNewerEdgeNotTheOlderOne() {
        // A wide gap low in the room must not outrank a narrow one at the top: the edge that decides
        // is the one an older page is requested BEFORE.
        val wideLow =
            resolved(
                id = 1,
                older = GapEdgeAnchor.Exact(TimelineAnchor(10, 10)),
                newer = GapEdgeAnchor.Exact(TimelineAnchor(500, 500)),
            )
        val narrowHigh =
            resolved(
                id = 2,
                older = GapEdgeAnchor.Exact(TimelineAnchor(890, 890)),
                newer = GapEdgeAnchor.Exact(TimelineAnchor(900, 900)),
            )

        assertEquals(2L, newestPageableGap(listOf(wideLow, narrowHigh))?.gap?.id)
    }
}
