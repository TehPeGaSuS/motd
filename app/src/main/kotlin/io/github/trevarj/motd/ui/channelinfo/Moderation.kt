package io.github.trevarj.motd.ui.channelinfo

/**
 * Pure moderation helpers (plans/16 §5.8). No Android/IRC deps, so op-gating and mask building are
 * unit-testable in isolation.
 */

/** Prefix glyphs at or above operator (owner '~', admin '&', op '@'). Halfop '%' is excluded. */
private const val OP_GLYPHS = "~&@"

/**
 * True when [ownPrefixes] contains a mode at or above op per [prefixOrder] (most privileged first).
 * Halfop is intentionally excluded (Confirmed decision #7): only '~', '&', '@' grant moderation.
 * A glyph not present in [prefixOrder] never qualifies.
 */
fun canModerate(ownPrefixes: String, prefixOrder: String): Boolean {
    val order = prefixOrder.ifEmpty { DEFAULT_PREFIX_ORDER }
    return ownPrefixes.any { it in OP_GLYPHS && order.indexOf(it) >= 0 }
}

/** Ban mask for [nick]: the simple `nick!*@*` form used by the /ban command and ban action. */
fun banMask(nick: String): String = "$nick!*@*"

/**
 * Ban mask for an address: `*!*@host`. Survives a nick change, which the [banMask] form does not,
 * so it is the scope offered whenever a WHOIS/cached host is actually known.
 */
fun hostMask(host: String): String = "*!*@$host"

/** How the ban/exception target picker turns a selection into a mask. */
enum class BanScope { NICK, HOST, CUSTOM }

/**
 * The exact mask a ban/exception dialog will send, so the preview and the wire cannot diverge.
 * Blank means "nothing to send yet" (no member chosen, address not resolved, empty custom text).
 */
fun composeBanMask(scope: BanScope, nick: String?, host: String?, custom: String): String =
    when (scope) {
        BanScope.NICK -> nick?.trim()?.takeIf(String::isNotBlank)?.let(::banMask).orEmpty()
        BanScope.HOST -> host?.trim()?.takeIf(String::isNotBlank)?.let(::hostMask).orEmpty()
        BanScope.CUSTOM -> custom.trim()
    }

/**
 * Host portion of a cached `user@host` hostmask, or null when it carries no host.
 * Used as the second lookup step before falling back to a labeled WHOIS.
 */
fun hostFromUserHost(userHost: String?): String? =
    userHost?.substringAfter('@', "")?.trim()?.takeIf { it.isNotBlank() && it != userHost.trim() }
