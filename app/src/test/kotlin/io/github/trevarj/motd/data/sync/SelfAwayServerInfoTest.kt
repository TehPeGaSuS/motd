package io.github.trevarj.motd.data.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.data.db.MessageKind
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.irc.event.IrcEvent
import io.github.trevarj.motd.irc.proto.IrcMessage
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 305/306 stopped being Raw whitelist numerics, so the server-buffer line they used to produce has
 * to keep coming from the typed [IrcEvent.SelfAwayChanged] branch — live only, exactly as before.
 */
@RunWith(RobolectricTestRunner::class)
class SelfAwayServerInfoTest {
    private lateinit var db: MotdDatabase
    private lateinit var processor: EventProcessor
    private var networkId: Long = 0

    @Before
    fun setUp() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            db =
                Room
                    .inMemoryDatabaseBuilder(context, MotdDatabase::class.java)
                    .allowMainThreadQueries()
                    .build()
            processor = EventProcessor(db, TypingTrackerImpl(), MessageNotifier.Noop)
            networkId =
                db.networkDao().insert(
                    NetworkEntity(
                        name = "libera",
                        role = NetworkRole.DIRECT,
                        host = "irc.libera.chat",
                        port = 6697,
                        nick = "me",
                        username = "me",
                        realname = "Me",
                    ),
                )
            processor.onRegistered(networkId, "me", mapOf("CASEMAPPING" to "rfc1459"))
        }

    @After fun tearDown() {
        db.close()
    }

    private suspend fun serverBuffer() = db.bufferDao().byName(networkId, "*")

    private suspend fun serverRows() =
        db
            .messageDao()
            .pagingSource(serverBuffer()!!.id)
            .load(
                androidx.paging.PagingSource.LoadParams
                    .Refresh(null, 100, false),
            ).let { (it as androidx.paging.PagingSource.LoadResult.Page).data }

    @Test
    fun liveSelfAway_insertsSameServerInfoLineAsBefore() =
        runTest {
            processor.process(
                networkId,
                IrcEvent.SelfAwayChanged(isAway = true, text = "You have been marked as being away"),
            )
            processor.process(
                networkId,
                IrcEvent.SelfAwayChanged(isAway = false, text = "You are no longer marked as being away"),
            )
            val rows = serverRows()
            assertEquals(2, rows.size)
            rows.forEach { assertEquals(MessageKind.SERVER_INFO, it.kind) }
            assertEquals(
                setOf(
                    "You have been marked as being away",
                    "You are no longer marked as being away",
                ),
                rows.map { it.text }.toSet(),
            )
        }

    @Test
    fun historySelfAway_insertsNothing() =
        runTest {
            processor.process(
                networkId,
                IrcEvent.HistoryBatch(
                    target = "#chan",
                    events = listOf(IrcEvent.SelfAwayChanged(isAway = true, text = "You have been marked as being away")),
                ),
            )
            assertNull(serverBuffer())
        }

    @Test
    fun pushSelfAway_insertsNothing() =
        runTest {
            processor.processPush(
                networkId,
                IrcEvent.SelfAwayChanged(isAway = true, text = "You have been marked as being away"),
            )
            assertNull(serverBuffer())
        }

    @Test
    fun awayNumericsAreNoLongerRawServerInfo() =
        runTest {
            // The Raw path must not double-render them now that the typed branch owns the line.
            processor.process(
                networkId,
                IrcEvent.Raw(IrcMessage(command = "306", params = listOf("me", "You have been marked as being away"))),
            )
            processor.process(
                networkId,
                IrcEvent.Raw(IrcMessage(command = "305", params = listOf("me", "You are no longer marked as being away"))),
            )
            assertNull(serverBuffer())
        }
}
