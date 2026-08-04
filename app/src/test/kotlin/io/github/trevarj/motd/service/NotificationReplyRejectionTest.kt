package io.github.trevarj.motd.service

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A rejected notification reply used to be logged and dropped: the RemoteInput UI reported success,
 * nothing was persisted, and the typed text was gone. The reply now survives in the composer draft
 * and the user gets a retryable failure notice.
 */
class NotificationReplyRejectionTest {

    private class Recorder {
        var preserved = 0
        var released = 0
        var failedWith: SendRejectionReason? = null
        var resolved = 0
    }

    private suspend fun deliver(
        acceptance: SendAcceptance,
        retry: Boolean,
        recorder: Recorder,
    ) = deliverNotificationReply(
        retry = retry,
        send = { acceptance },
        preserveDraft = { recorder.preserved++ },
        releaseDraft = { recorder.released++ },
        notifyFailed = { reason -> recorder.failedWith = reason },
        notifyResolved = { recorder.resolved++ },
    )

    private val accepted = SendAcceptance.Accepted(eventIds = listOf(7L))

    @Test
    fun rejectedReplyPreservesTheTextAndSurfacesTheFailure() = runTest {
        val recorder = Recorder()

        deliver(SendAcceptance.Rejected(SendRejectionReason.NOT_IN_CHANNEL), retry = false, recorder)

        assertEquals(1, recorder.preserved)
        assertEquals(SendRejectionReason.NOT_IN_CHANNEL, recorder.failedWith)
        assertEquals(0, recorder.released)
        assertEquals(0, recorder.resolved)
    }

    @Test
    fun acceptedReplyTouchesNothing() = runTest {
        val recorder = Recorder()

        deliver(accepted, retry = false, recorder)

        assertEquals(0, recorder.preserved)
        assertEquals(0, recorder.released)
        assertEquals(0, recorder.resolved)
        assertNull(recorder.failedWith)
    }

    @Test
    fun acceptedRetryReleasesThePreservedDraftAndRetiresTheNotice() = runTest {
        val recorder = Recorder()

        deliver(accepted, retry = true, recorder)

        assertEquals(1, recorder.released)
        assertEquals(1, recorder.resolved)
        assertEquals(0, recorder.preserved)
        assertNull(recorder.failedWith)
    }

    @Test
    fun rejectedRetryReportsAgainWithoutDuplicatingThePreservedDraft() = runTest {
        val recorder = Recorder()

        deliver(SendAcceptance.Rejected(SendRejectionReason.BUFFER_NOT_FOUND), retry = true, recorder)

        assertEquals(0, recorder.preserved)
        assertEquals(SendRejectionReason.BUFFER_NOT_FOUND, recorder.failedWith)
        assertEquals(0, recorder.resolved)
    }

    @Test
    fun preservedTextIsAppendedToAnInProgressDraft() {
        assertEquals("rejected", mergeRejectedReply(null, "rejected"))
        assertEquals("rejected", mergeRejectedReply("", "rejected"))
        assertEquals("rejected", mergeRejectedReply("   ", "rejected"))
        assertEquals("typing\nrejected", mergeRejectedReply("typing", "rejected"))
    }

    @Test
    fun retriedTextIsRemovedOnlyWhenTheDraftStillHoldsIt() {
        assertEquals("", withoutRetriedReply("rejected", "rejected"))
        assertEquals("typing", withoutRetriedReply("typing\nrejected", "rejected"))
        // Edited or replaced by the user: leave the draft alone.
        assertNull(withoutRetriedReply("typing\nrejected and more", "rejected"))
        assertNull(withoutRetriedReply("something else", "rejected"))
        assertNull(withoutRetriedReply(null, "rejected"))
    }

    @Test
    fun everyRejectionReasonHasUserFacingText() {
        SendRejectionReason.entries.forEach { reason ->
            assertTrue(reason.name, sendRejectionText(reason).isNotBlank())
        }
        assertEquals("You're not in this channel", sendRejectionText(SendRejectionReason.NOT_IN_CHANNEL))
    }
}
