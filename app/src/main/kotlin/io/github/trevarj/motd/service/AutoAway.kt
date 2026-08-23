package io.github.trevarj.motd.service

/**
 * Pure decisions behind [AutoAwayCoordinator].
 *
 * The invariant the whole feature rests on: auto-away never overwrites an away state it did not
 * create, and auto-back only touches networks it marked itself. Everything here derives from the
 * server-confirmed [ConnectionManager.selfAwayStates] keys, never from an optimistic local guess, so
 * an away set by another bouncer client (or replayed from soju's `initialAwayMessage`) is left
 * exactly as the user left it.
 */

/** Networks a firing auto-away should mark: Ready right now and not already away. */
internal fun autoAwayTargets(
    readyNetworkIds: Set<Long>,
    awayNetworkIds: Set<Long>,
): Set<Long> = readyNetworkIds - awayNetworkIds

/**
 * Markers worth keeping: ours, and still away.
 *
 * A network that came back on its own -- the user typed `/back`, another client cleared it, or the
 * connection dropped and cleared the state -- stops being ours the moment the server says so, so a
 * later foreground cannot resurrect a decision the user already made.
 */
internal fun retainedMarkers(
    markedNetworkIds: Set<Long>,
    awayNetworkIds: Set<Long>,
): Set<Long> = markedNetworkIds intersect awayNetworkIds

/** Networks the returning foreground should send `AWAY` (back) to: marked by us and still away. */
internal fun autoBackTargets(
    markedNetworkIds: Set<Long>,
    awayNetworkIds: Set<Long>,
): Set<Long> = retainedMarkers(markedNetworkIds, awayNetworkIds)

/**
 * In-flight auto-away writes worth remembering: those whose network is still Ready.
 *
 * [ConnectionManager.selfAwayStates] only moves once the server confirms with 306, so without this
 * the next connection-state emission would re-send `AWAY` to a network whose confirmation simply has
 * not landed yet. Dropping the request when the network leaves Ready is what re-arms auto-away for a
 * network that reconnects while the app is still backgrounded.
 */
internal fun retainedAwayRequests(
    requestedNetworkIds: Set<Long>,
    readyNetworkIds: Set<Long>,
): Set<Long> = requestedNetworkIds intersect readyNetworkIds

/** How long the app must stay backgrounded before auto-away fires. */
internal fun autoAwayDelayMillis(minutes: Int): Long = minutes.coerceAtLeast(1).toLong() * 60_000L

/** Configured text, or the localized default when the user left the field blank. */
internal fun autoAwayText(
    configured: String,
    default: String,
): String = configured.trim().ifEmpty { default }
