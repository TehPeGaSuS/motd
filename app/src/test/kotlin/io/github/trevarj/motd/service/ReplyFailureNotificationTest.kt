package io.github.trevarj.motd.service

import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.data.db.BufferEntity
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.prefs.DataStoreSettingsRepository
import io.github.trevarj.motd.di.ForegroundBufferTrackerImpl
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** The failure notice raised for a rejected notification reply, and its retry/retire lifecycle. */
@RunWith(RobolectricTestRunner::class)
class ReplyFailureNotificationTest {

    private lateinit var db: MotdDatabase
    private lateinit var repo: DataStoreSettingsRepository
    private lateinit var notifications: MotdNotifications
    private var bufferId: Long = 0

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val shadowManager
        get() = shadowOf(context.getSystemService(NotificationManager::class.java))

    @Before
    fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(context, MotdDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val networkId = db.networkDao().insert(
            NetworkEntity(
                name = "libera", role = NetworkRole.DIRECT, host = "irc.libera.chat",
                port = 6697, nick = "me", username = "me", realname = "Me",
            ),
        )
        bufferId = db.bufferDao().insert(
            BufferEntity(
                networkId = networkId, name = "#motd", displayName = "#motd",
                type = BufferType.CHANNEL,
            ),
        )
        shadowOf(context as android.app.Application)
            .grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        repo = DataStoreSettingsRepository(context)
        notifications = MotdNotifications(context, db, ForegroundBufferTrackerImpl(), repo)
    }

    @After
    fun tearDown() {
        NotificationManagerCompat.from(context).cancelAll()
        db.close()
    }

    @Test
    fun rejectedReplyIsSurfacedWithItsTextAndReason() = runTest {
        notifications.onReplyFailed(bufferId, "lost text", SendRejectionReason.NOT_IN_CHANNEL)

        val posted = shadowManager.activeNotifications.single()
        assertEquals(MotdNotifications.sendFailureNotificationId(bufferId), posted.id)
        assertEquals(MotdNotifications.CHANNEL_SEND_FAILURES, posted.notification.channelId)
        val title = posted.notification.extras.getCharSequence(NotificationCompat.EXTRA_TITLE)?.toString()
        assertEquals("Not sent to #motd", title)
        val body = posted.notification.extras.getCharSequence(NotificationCompat.EXTRA_TEXT)?.toString().orEmpty()
        assertTrue(body, body.contains("lost text"))
        assertTrue(body, body.contains(sendRejectionText(SendRejectionReason.NOT_IN_CHANNEL)))
        // A retry action is the only way back; the send never produced a timeline row.
        assertEquals(listOf("Retry"), posted.notification.actions.map { it.title.toString() })
    }

    @Test
    fun failureNoticeIsRetiredWhenTheRetryLands() = runTest {
        notifications.onReplyFailed(bufferId, "lost text", SendRejectionReason.PERSISTENCE_FAILED)
        assertEquals(1, shadowManager.activeNotifications.size)

        notifications.onReplyFailureResolved(bufferId)

        assertEquals(0, shadowManager.activeNotifications.size)
    }

    @Test
    fun failureNoticeIdCannotAliasTheStatusOrOtherNotificationRanges() {
        val id = MotdNotifications.sendFailureNotificationId(bufferId)
        assertTrue(id in 0x20000000..0x2fffffff)
        assertTrue(id != IrcForegroundService.STATUS_ID)
        assertTrue(id != MotdNotifications.messageNotificationId(bufferId))
        assertTrue(id != MotdNotifications.invitationNotificationId(bufferId))
        assertTrue(id != MotdNotifications.transferNotificationId(bufferId))
    }

    @Test
    fun aRejectionForAnUnknownBufferStillSurfacesTheText() = runTest {
        notifications.onReplyFailed(4_242, "orphan text", SendRejectionReason.BUFFER_NOT_FOUND)

        val posted = shadowManager.activeNotifications.single()
        val title = posted.notification.extras.getCharSequence(NotificationCompat.EXTRA_TITLE)?.toString()
        assertEquals("Message not sent", title)
        val body = posted.notification.extras.getCharSequence(NotificationCompat.EXTRA_TEXT)?.toString().orEmpty()
        assertTrue(body, body.contains("orphan text"))
    }
}
