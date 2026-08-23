package io.github.trevarj.motd.ui.chat

import io.github.trevarj.motd.data.db.TimelineAnchor
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Per-room viewport memory for the current process: where the reader left off, and how far back
 * they got. Both facts have the same lifetime by construction — they are two readings of the same
 * viewport, they are lost together when the process dies, and neither is ever persisted — so they
 * share one store rather than duplicating a second singleton with an identical shape and the same
 * `roomId`-with-`bufferId`-fallback keying at every call site.
 *
 * NOTHING here is read state. The furthest-displayed watermark in particular is NOT the read
 * marker: the read marker is durable, is uploaded as `MARKREAD` to the account's other clients, and
 * is written only through the connection/event path. This class has no DAO, no repository and no
 * connection handle, so a watermark cannot reach that path even by accident. It is local-only in
 * the sense `localUnreadFloorTime` is — a private statement about this device's reader — and it
 * exists purely to decide where a reopen lands.
 */
@Singleton
class ChatScrollPositionStore
    @Inject
    constructor() {
        private val positions = java.util.concurrent.ConcurrentHashMap<Long, ChatScrollPosition>()
        private val displayed = java.util.concurrent.ConcurrentHashMap<Long, TimelineAnchor>()
        private val bottomParks =
            java.util.concurrent.ConcurrentHashMap
                .newKeySet<Long>()

        fun get(bufferId: Long): ChatScrollPosition? = positions[bufferId]

        fun put(
            bufferId: Long,
            position: ChatScrollPosition,
        ) {
            positions[bufferId] = position
            bottomParks.remove(bufferId)
        }

        /** Forgets the saved viewport ONLY. What the reader has already seen does not become untrue. */
        fun remove(bufferId: Long) {
            positions.remove(bufferId)
            bottomParks.remove(bufferId)
        }

        /**
         * Records that the reader left this room at the live bottom.
         *
         * Absence of a saved viewport cannot carry this on its own, because it is also what a room
         * nobody has opened yet looks like, and those two want opposite entries for a room with NO
         * unread: a first open falls back to the read marker, while a follower belongs back at the
         * newest row. The park says which one it is, and nothing more — it is a statement about where
         * the reader stopped, not about what arrived afterwards, so it does not survive contact with an
         * unread divider. Entry consults it only when the room is caught up; see `ChatViewModel`.
         */
        fun markParkedAtBottom(bufferId: Long) {
            positions.remove(bufferId)
            bottomParks += bufferId
        }

        fun isParkedAtBottom(bufferId: Long): Boolean = bufferId in bottomParks

        /** Deepest (oldest) row this process has put on screen for the room, or null if none has. */
        fun furthestDisplayed(bufferId: Long): TimelineAnchor? = displayed[bufferId]

        /**
         * Records a row the timeline displayed. Monotonic toward history: a reader scrolling back
         * forward never retracts the depth they already reached, so the watermark keeps meaning
         * "everything at or below this has been on screen".
         */
        fun recordFurthestDisplayed(
            bufferId: Long,
            anchor: TimelineAnchor,
        ) {
            displayed.merge(bufferId, anchor) { seen, incoming -> minOf(seen, incoming) }
        }
    }
