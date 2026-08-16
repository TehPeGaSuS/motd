package io.github.trevarj.motd.service

import io.github.trevarj.motd.data.sync.HistoryPageLoader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred

/**
 * How many CHATHISTORY requests one catch-up pass keeps on the wire, adapted to what the server is
 * actually keeping up with.
 *
 * A fixed width has to be chosen for the worst server, and it was: three. On a healthy bouncer that
 * left the wire mostly idle while the user watched a chat list fill in one room at a time, and on a
 * struggling one it still queued every remaining target behind requests that were going to expire.
 * So this starts optimistic and reacts: additive-increase after a run of clean responses,
 * multiplicative-decrease the moment a request times out (AIMD, the same shape congestion control
 * uses, and for the same reason — a timeout is the only honest evidence of overload available here).
 *
 * Three properties are load-bearing:
 *
 *  - **Admission is FIFO.** The pass orders its targets deliberately (visible chat first, then by
 *    recency), and the whole point of that ordering is that the first requests on the wire are the
 *    ones the user is most likely to look at. A semaphore that hands permits to whichever waiter the
 *    scheduler resumes first would silently discard that ordering.
 *  - **Shrinking never revokes a slot that is already held.** Requests in flight have already
 *    started their timeout clocks; cancelling one to satisfy a smaller width converts a slow server
 *    into a failed pass. The width applies to ADMISSION only, so an over-width pass drains naturally
 *    as its in-flight requests complete.
 *  - **The ceiling is the loader's wire gate.** Widening past it would only queue requests inside
 *    the gate with their timeout clocks already running, which is exactly the mass-expiry this class
 *    exists to avoid.
 *
 * One instance per pass: the evidence is about this connection under this workload, and a new pass
 * on a reconnected socket deserves to be optimistic again.
 */
internal class AdaptiveFanOut(
    initialWidth: Int = INITIAL_WIDTH,
    private val floor: Int = 1,
    private val ceiling: Int = HistoryPageLoader.MAX_CONCURRENT_WIRE_REQUESTS,
    private val restoreStreak: Int = RESTORE_STREAK,
) {
    // Every field below is guarded by this monitor. All of it is non-suspending bookkeeping, so a
    // plain monitor is both sufficient and immune to the cancellation hazards a Mutex would add on
    // the release path.
    private val monitor = Any()
    private var width = initialWidth.coerceIn(floor, ceiling)
    private var held = 0
    private var successStreak = 0

    // Waiters in arrival order. A ticket is completed at the moment its slot is granted, so the
    // queue is the admission order and nothing else re-orders it.
    private val waiters = ArrayDeque<CompletableDeferred<Unit>>()

    /** Current admission width; observable so a test can assert the reaction, not just its effect. */
    val currentWidth: Int get() = synchronized(monitor) { width }

    /** Slots handed out right now, which may exceed [currentWidth] after a shrink. */
    val inFlight: Int get() = synchronized(monitor) { held }

    /** Run [block] holding one admission slot, releasing it however [block] ends. */
    suspend fun <T> withSlot(block: suspend () -> T): T {
        acquire()
        try {
            return block()
        } finally {
            release()
        }
    }

    /**
     * A request expired. Halve the width toward [floor] and forget any progress toward widening:
     * the server is behind, and adding to its queue makes the next timeout more likely, not less.
     */
    fun onTimeout() {
        synchronized(monitor) {
            successStreak = 0
            width = maxOf(floor, width / 2)
        }
    }

    /**
     * A request completed. Widen by one only after [restoreStreak] consecutive clean responses, so
     * a single lucky reply cannot undo a shrink the server earned.
     */
    fun onSuccess() {
        synchronized(monitor) {
            if (width >= ceiling) {
                successStreak = 0
                return
            }
            successStreak++
            if (successStreak < restoreStreak) return
            successStreak = 0
            width++
            drainLocked()
        }
    }

    private suspend fun acquire() {
        val ticket = synchronized(monitor) {
            // Queue behind existing waiters even when a slot is free: overtaking them is exactly the
            // ordering loss this class exists to prevent.
            if (held < width && waiters.isEmpty()) {
                held++
                return
            }
            CompletableDeferred<Unit>().also { waiters.addLast(it) }
        }
        try {
            ticket.await()
        } catch (cancelled: CancellationException) {
            synchronized(monitor) {
                // Still queued: just leave the queue. Already granted (the ticket completed in the
                // same moment this coroutine was cancelled): hand the slot straight to the next
                // waiter rather than leaking it for the rest of the pass.
                if (!waiters.remove(ticket)) {
                    held--
                    drainLocked()
                }
            }
            throw cancelled
        }
    }

    private fun release() {
        synchronized(monitor) {
            held--
            drainLocked()
        }
    }

    private fun drainLocked() {
        while (held < width && waiters.isNotEmpty()) {
            val next = waiters.removeFirst()
            held++
            // A ticket that somehow cannot be completed never took its slot; give it straight back.
            if (!next.complete(Unit)) held--
        }
    }

    internal companion object {
        /**
         * Optimistic start. Two rounds at this width cover [WAVE_ONE_LIMIT], so the whole visible
         * wave of a healthy account settles in two round trips.
         */
        const val INITIAL_WIDTH = HistoryPageLoader.MAX_CONCURRENT_WIRE_REQUESTS

        /** Clean responses required before widening by one. */
        const val RESTORE_STREAK = 3
    }
}
