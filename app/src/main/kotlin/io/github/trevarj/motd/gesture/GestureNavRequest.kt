package io.github.trevarj.motd.gesture

/**
 * A destination the gesture menu wants opened.
 *
 * The dispatcher runs outside composition and has no `NavController`, so navigation is published as
 * a one-shot request and performed by the overlay host, which does have one in scope. Same split as
 * the notification-target hand-off in `MainActivity`: whoever decided *what* to open is never the
 * thing that knows *how* to open it.
 */
sealed interface GestureNavRequest {
    /** [bufferId] is already canonical: durable room redirects are resolved before publishing. */
    data class OpenChat(
        val bufferId: Long,
    ) : GestureNavRequest

    data object OpenSearch : GestureNavRequest

    /** Channel info for a canonical [bufferId]. */
    data class OpenChannelInfo(
        val bufferId: Long,
    ) : GestureNavRequest

    data object OpenChatList : GestureNavRequest
}
