package io.github.trevarj.motd.service

import io.github.trevarj.motd.irc.event.IrcEvent

/**
 * Pure transition for [ConnectionManager.selfAwayStates].
 *
 * A present key means the server has confirmed we are away on that network; the value is the away
 * message when it is known. [pendingMessage] is the text this device last wrote with AWAY and is
 * only attached when the server confirms (306), because 306 itself carries no message.
 *
 * Away set from another client of the same bouncer arrives as an away-notify echo of our own nick
 * ([IrcEvent.AwayChanged]) rather than a numeric, so that path is handled too and does carry the
 * message. [IrcEvent.Disconnected] clears the network: nothing is confirmed once the socket is gone.
 *
 * [affectsSelfAway] names exactly the events the fold can move, so the per-event hot path can skip
 * the rest without duplicating that knowledge.
 */
internal fun affectsSelfAway(event: IrcEvent): Boolean =
    event is IrcEvent.SelfAwayChanged || event is IrcEvent.AwayChanged || event is IrcEvent.Disconnected

internal fun selfAwayAfterEvent(
    current: Map<Long, String?>,
    networkId: Long,
    event: IrcEvent,
    pendingMessage: String?,
    selfNick: String?,
    normalize: (String) -> String,
): Map<Long, String?> = when (event) {
    is IrcEvent.SelfAwayChanged ->
        if (event.isAway) current + (networkId to pendingMessage) else current - networkId
    is IrcEvent.AwayChanged -> {
        if (selfNick == null || normalize(event.nick) != normalize(selfNick)) {
            current
        } else if (event.awayMessage != null) {
            current + (networkId to event.awayMessage)
        } else {
            current - networkId
        }
    }
    is IrcEvent.Disconnected -> current - networkId
    else -> current
}
