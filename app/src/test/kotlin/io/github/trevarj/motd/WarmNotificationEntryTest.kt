package io.github.trevarj.motd

import android.content.Intent
import io.github.trevarj.motd.service.MotdNotifications
import io.github.trevarj.motd.ui.nav.NotificationTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The warm-start entry decision an already-foregrounded process makes for a re-delivered intent.
 *
 * A notification tap fires no ProcessLifecycleOwner ON_START, so this is the only place that entry
 * can be recognized as a foreground: routing the jump without checkpointing history leaves the
 * opened chat showing exactly what the frozen process last saw.
 */
@RunWith(RobolectricTestRunner::class)
class WarmNotificationEntryTest {
    private fun openBufferIntent(bufferId: Long) =
        Intent(MotdNotifications.ACTION_OPEN_BUFFER)
            .putExtra(MotdNotifications.EXTRA_BUFFER_ID, bufferId)
            .putExtra(MotdNotifications.EXTRA_JUMP_MSGID, "m1")

    @Test
    fun `a tapped notification routes the jump and checkpoints history for the tapped buffer`() {
        var target: NotificationTarget? = null
        val checkpoints = mutableListOf<Long>()

        warmNotificationEntry(
            openBufferIntent(7L),
            onTarget = { target = it },
            onCheckpointHistory = { checkpoints += it },
        )

        assertEquals(7L, target?.bufferId)
        assertEquals("m1", target?.jumpToMsgid)
        // The checkpoint is told WHICH buffer the tap opened, so it can be reconciled first
        // instead of waiting for a network-wide pass to discover it.
        assertEquals(listOf(7L), checkpoints)
    }

    @Test
    fun `an accepted invitation is a notification entry too`() {
        val checkpoints = mutableListOf<Long>()

        warmNotificationEntry(
            Intent(MotdNotifications.ACTION_ACCEPT_INVITE)
                .putExtra(MotdNotifications.EXTRA_BUFFER_ID, 3L),
            onTarget = {},
            onCheckpointHistory = { checkpoints += it },
        )

        assertEquals(listOf(3L), checkpoints)
    }

    @Test
    fun `a share, a relaunch, and a targetless notification are not entries`() {
        var target: NotificationTarget? = null
        val checkpoints = mutableListOf<Long>()
        val notEntries =
            listOf(
                Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "hi"),
                Intent(Intent.ACTION_MAIN),
                // A notification intent with nothing to open: there is no chat to bring up to date.
                Intent(MotdNotifications.ACTION_OPEN_BUFFER),
                null,
            )

        notEntries.forEach { intent ->
            warmNotificationEntry(
                intent,
                onTarget = { target = it },
                onCheckpointHistory = { checkpoints += it },
            )
        }

        assertNull(target)
        assertEquals(emptyList<Long>(), checkpoints)
    }
}
