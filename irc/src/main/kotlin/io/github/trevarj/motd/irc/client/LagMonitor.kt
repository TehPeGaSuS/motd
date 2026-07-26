package io.github.trevarj.motd.irc.client

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Round-trip latency probe (issue #34).
 *
 * Sends `PING motd-lag-<n>` on a fixed cadence and records the elapsed time until the server echoes
 * the matching `PONG` payload back. The most recent RTT in milliseconds is published to [lag]; `null`
 * means no measurement has completed yet (or the connection was reset). The token prefix
 * `motd-lag-` keeps these probes distinct from [PingWatchdog]'s `motd-<epoch>` keepalive PINGs, so
 * the two never steal each other's replies.
 *
 * [lag] is a caller-supplied [MutableStateFlow] ([sink]) so the owning [IrcClient] can expose one
 * stable [StateFlow] across the connection's lifetime — collectors that attach before the first
 * probe still observe later readings instead of pinning a throwaway flow.
 *
 * Timing is [delay]-driven so the cadence works under coroutines-test virtual time; [nowMs] is
 * injectable so a test can advance a logical clock independently of the scheduler. Probes are only
 * sent once the caller reports the connection registered, so a pre-welcome PING cannot confuse a
 * server that rejects commands before 001.
 *
 * The watchdog remains the authoritative liveness guard: a dropped PONG still trips the watchdog
 * after its idle/grace windows. This class only measures latency; it never tears the socket down.
 */
internal class LagMonitor(
    private val scope: CoroutineScope,
    private val sendPing: suspend (payload: String) -> Unit,
    private val isRegistered: () -> Boolean,
    private val sink: MutableStateFlow<Long?>,
    private val intervalMs: Long = DEFAULT_INTERVAL_MS,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    /** Latest RTT (ms) or null; backed by the caller's [sink]. */
    val lag: StateFlow<Long?> = sink

    // token -> send timestamp. ConcurrentHashMap: probes send from the loop coroutine while PONGs
    // arrive on the socket-reader coroutine.
    private val pending = ConcurrentHashMap<String, Long>()
    private val counter = AtomicLong(0)
    private var job: Job? = null

    fun start() {
        stop()
        sink.value = null
        job = scope.launch {
            while (isActive) {
                delay(intervalMs)
                if (!isRegistered()) continue
                pruneStale()
                val token = "motd-lag-${counter.incrementAndGet()}"
                pending[token] = nowMs()
                runCatching { sendPing(token) }
            }
        }
    }

    /**
     * Correlate one inbound PONG. Returns true when [params] contained a token issued by this
     * monitor, so [IrcClient] can consume the line instead of dispatching it as a raw event. A
     * negative or implausibly large RTT (clock skew, a delayed stale PONG) is discarded rather than
     * published, but the matching probe is still retired.
     */
    fun onPong(params: List<String>): Boolean {
        if (params.isEmpty()) return false
        val token = params.firstOrNull { pending.containsKey(it) } ?: return false
        val sentAt = pending.remove(token) ?: return false
        val rtt = nowMs() - sentAt
        if (rtt in 0..MAX_LAG_MS) sink.value = rtt
        return true
    }

    /** Clear the published reading and any in-flight probes (e.g. on disconnect). */
    fun reset() {
        sink.value = null
        pending.clear()
    }

    fun stop() {
        job?.cancel()
        job = null
        pending.clear()
    }

    private fun pruneStale() {
        val cutoff = nowMs() - STALE_AFTER_MS
        val iterator = pending.entries.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().value < cutoff) iterator.remove()
        }
    }

    companion object {
        const val DEFAULT_INTERVAL_MS = 30_000L
        // Drop probes whose PONG is taking longer than twice the cadence; the watchdog owns the
        // real dead-connection decision, so lingering entries only risk matching a very late echo.
        const val STALE_AFTER_MS = 2 * DEFAULT_INTERVAL_MS
        // Ignore readings beyond this threshold rather than flashing a bogus multi-minute value.
        const val MAX_LAG_MS = 5 * 60 * 1000L
    }
}