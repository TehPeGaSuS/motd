package io.github.trevarj.motd.data.prefs

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class InviteEnrollmentStoreTest {
    @Test
    fun `keys drafts and reminders survive a new store and clear with network`() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<android.content.Context>()
            val id = 91_337L
            val first = InviteEnrollmentStore(context)
            first.clearNetwork(id)
            first.putChannelKey(id, "#friends", "secret")
            first.putAccountDraft(
                AccountEnrollmentDraft(id, AccountEnrollmentProvider.IRCV3, "alice", null, "generated-password"),
            )
            first.setAccountReminder(id, true)
            first.setProvisionalNetwork(id, true)
            first.setImportedCertPin(id, true)

            val reopened = InviteEnrollmentStore(context)
            assertEquals("secret", reopened.channelKey(id, "#friends"))
            reopened.prepareChannelKeyBackup(id, "#friends")
            reopened.putChannelKey(id, "#friends", "replacement")
            reopened.restoreChannelKeyBackup(id)
            assertEquals("secret", reopened.channelKey(id, "#friends"))
            assertEquals("generated-password", reopened.accountDraft(id)?.password)
            assertEquals(true, id in reopened.accountReminders.first())
            assertEquals(true, reopened.isProvisionalNetwork(id))
            assertEquals(true, reopened.hasImportedCertPin(id))

            reopened.clearNetwork(id)
            assertNull(reopened.channelKey(id, "#friends"))
            assertNull(reopened.accountDraft(id))
            assertEquals(false, id in reopened.accountReminders.first())
            assertEquals(false, reopened.isProvisionalNetwork(id))
            assertEquals(false, reopened.hasImportedCertPin(id))
        }
}
