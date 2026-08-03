package io.github.trevarj.motd.data.sync

import io.github.trevarj.motd.data.db.RoomId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The three things the timeline needs from [HistoryGapFillCoordinator]: start a fill for a tapped
 * seam, start one hands-free for whichever seam sits newest (the reconnect catch-up autopilot), and
 * know which gaps are filling right now so their dividers can show progress.
 *
 * Narrow on purpose, mirroring how `HistoryResyncController` fronts its own coordinator. The
 * coordinator owns a live per-network wire, a page budget, and a diagnostics journal; a ViewModel
 * depending on all of that could not be built in a unit test without standing up a transport.
 */
interface HistoryGapFiller {
    /** Gap ids with a fill in flight, for the spinner on their divider rows. */
    val fillsInFlight: StateFlow<Set<Long>>

    /** Fill the tapped gap. Each tap grants a fresh page budget. */
    suspend fun fillGap(roomId: RoomId, gapId: Long)

    /**
     * Fill whichever gap older paging would work on under Recent focus — the newest seam in the
     * room. One page budget per call, exactly like a tap; the caller decides how often to arm it.
     */
    suspend fun fillNewestGap(roomId: RoomId)
}

/** No history transport at all: nothing ever fills, so every seam keeps the state it already has. */
object NoopHistoryGapFiller : HistoryGapFiller {
    override val fillsInFlight: StateFlow<Set<Long>> = MutableStateFlow(emptySet())
    override suspend fun fillGap(roomId: RoomId, gapId: Long) = Unit
    override suspend fun fillNewestGap(roomId: RoomId) = Unit
}
