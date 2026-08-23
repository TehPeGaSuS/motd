package io.github.trevarj.motd.avatar

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dagger.Lazy
import io.github.trevarj.motd.data.db.BufferEntity
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.testing.NoopConnectionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AvatarCoordinatorConversationTest {
    private lateinit var db: MotdDatabase
    private lateinit var local: LocalAvatarStore

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MotdDatabase::class.java).allowMainThreadQueries().build()
        local = LocalAvatarStore(context)
    }

    @After fun tearDown() = db.close()

    @Test fun query_changes_are_local_only_and_reset_does_not_clear_channel_metadata() =
        runTest {
            val networkId =
                db.networkDao().insert(
                    NetworkEntity(
                        name = "test",
                        role = NetworkRole.DIRECT,
                        host = "irc.example",
                        port = 6697,
                        nick = "me",
                        username = "me",
                        realname = "Me",
                    ),
                )
            val queryId =
                db.bufferDao().insert(
                    BufferEntity(networkId = networkId, name = "alice", displayName = "Alice", type = BufferType.QUERY),
                )
            val channelId =
                db.bufferDao().insert(
                    BufferEntity(networkId = networkId, name = "#chat", displayName = "#chat", type = BufferType.CHANNEL),
                )
            val coordinator =
                AvatarCoordinator(
                    prefs = FakePrefs(),
                    store = FakeStore,
                    userDao = db.userDao(),
                    bufferDao = db.bufferDao(),
                    localAvatars = local,
                    connections = Lazy { NoopConnectionManager() },
                    scope = this,
                )

            assertEquals(
                ConversationAvatarOutcome.LocalOnly,
                coordinator.setConversationAvatar(queryId, "https://example.com/query.png"),
            )
            assertEquals("https://example.com/query.png", db.bufferDao().observeById(queryId)?.avatarOverrideModel)
            assertEquals(ConversationAvatarOutcome.LocalReset, coordinator.resetConversationAvatar(queryId))
            assertNull(db.bufferDao().observeById(queryId)?.avatarOverrideModel)

            assertEquals(
                ConversationAvatarOutcome.LocalOnly,
                coordinator.setConversationAvatar(channelId, "https://example.com/channel.png"),
            )
            assertEquals(
                ConversationAvatarOutcome.LocalOnly,
                coordinator.clearSharedConversationAvatar(channelId),
            )
            assertEquals("https://example.com/channel.png", db.bufferDao().observeById(channelId)?.avatarOverrideModel)
            assertEquals(
                ConversationAvatarOutcome.Invalid,
                coordinator.setConversationAvatar(queryId, "http://example.com/no.png"),
            )
            assertNull(db.bufferDao().observeById(queryId)?.avatarOverrideModel)
        }

    private class FakePrefs : AvatarPrefs {
        override val config = MutableStateFlow(AvatarConfig())

        override fun selfSetting(networkId: Long) = MutableStateFlow<SelfAvatarSetting>(SelfAvatarSetting.Unmanaged)

        override suspend fun setShowSharedAvatars(show: Boolean) = Unit

        override suspend fun setSelfSetting(
            networkId: Long,
            setting: SelfAvatarSetting,
        ) = Unit
    }

    private object FakeStore : AvatarStore {
        override val records = MutableStateFlow(emptyList<AvatarRecord>())

        override suspend fun upsert(
            networkId: Long,
            nick: String,
            account: String?,
            url: String,
        ) = Unit

        override suspend fun remove(
            networkId: Long,
            nick: String,
            account: String?,
        ) = Unit

        override suspend fun rename(
            networkId: Long,
            oldNick: String,
            newNick: String,
            account: String?,
        ) = Unit

        override suspend fun clearNetwork(networkId: Long) = Unit

        override suspend fun clearAll() = Unit
    }
}
