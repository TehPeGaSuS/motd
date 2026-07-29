package io.github.trevarj.motd.data.sync

import io.github.trevarj.motd.irc.event.IrcEvent

/** Pure provenance policy applied before an IRC event reaches persistence handlers. */
internal enum class EventOrigin(
    val notifies: Boolean,
    val mutatesSessionState: Boolean,
) {
    LIVE(notifies = true, mutatesSessionState = true),
    HISTORY(notifies = false, mutatesSessionState = false),
    REPLAY(notifies = false, mutatesSessionState = false),
    PUSH(notifies = true, mutatesSessionState = false),
    ;

    val isHistorical: Boolean
        get() = this == HISTORY || this == REPLAY

    /** Push delivery has a deliberately narrow persistence surface. */
    fun accepts(event: IrcEvent): Boolean = this != PUSH || when (event) {
        is IrcEvent.ChatMessage,
        is IrcEvent.TagMessage,
        is IrcEvent.Invited,
        is IrcEvent.DccSend,
        is IrcEvent.UnsupportedDcc,
        is IrcEvent.Raw,
        -> true
        else -> false
    }
}
