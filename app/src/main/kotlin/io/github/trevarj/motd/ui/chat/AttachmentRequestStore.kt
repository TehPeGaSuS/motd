package io.github.trevarj.motd.ui.chat

import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Process-local "open the attachment sheet here" request, queued by the gesture orb and consumed by
 * the chat screen exactly once.
 *
 * Shaped like the prefill seam in [ComposerDraftStore], and for the same reason: a request aimed at
 * a chat that has yet to be entered is drained by that screen's entry effect, while a request aimed
 * at the chat already on screen has no entry to wait for and arrives over [requests] instead.
 * Nothing here survives process death — an un-consumed request is a dropped gesture, not lost data.
 */
@Singleton
class AttachmentRequestStore @Inject constructor() {
    private val pending: MutableSet<Long> = Collections.newSetFromMap(ConcurrentHashMap())

    private val _requests = MutableSharedFlow<Long>(
        extraBufferCapacity = REQUEST_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Buffer ids that just gained a pending request; zero replay, exactly like the prefill seam. */
    val requests: SharedFlow<Long> = _requests.asSharedFlow()

    /** Ask the chat screen for [bufferId] to open its attachment sheet. */
    fun push(bufferId: Long) {
        pending += bufferId
        _requests.tryEmit(bufferId)
    }

    /** True exactly once per queued request for [bufferId]. */
    fun consume(bufferId: Long): Boolean = pending.remove(bufferId)

    private companion object {
        const val REQUEST_BUFFER = 4
    }
}
