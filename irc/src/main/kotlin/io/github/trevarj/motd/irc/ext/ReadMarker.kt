package io.github.trevarj.motd.irc.ext

import io.github.trevarj.motd.irc.proto.IrcMessage

/**
 * Builds read-marker request lines. `MARKREAD` is the IRCv3 `draft/read-marker` command;
 * soju's older `soju.im/read` extension reuses the same `timestamp=<ISO>` param shape under the
 * `READ` command, so the builders are parameterized by command name.
 */
internal object ReadMarkerCommands {
    /** MARKREAD <target> timestamp=<ISO> — set. */
    fun set(
        target: String,
        timestampMs: Long,
    ): IrcMessage = set("MARKREAD", target, timestampMs)

    /** MARKREAD <target> — get (server echoes current marker to all clients). */
    fun get(target: String): IrcMessage = get("MARKREAD", target)

    /** <command> <target> timestamp=<ISO> — set, for either MARKREAD or soju's READ. */
    fun set(
        command: String,
        target: String,
        timestampMs: Long,
    ): IrcMessage =
        IrcMessage(
            command = command,
            params = listOf(target, ChatHistorySelectors.timestamp(timestampMs)),
        )

    /** <command> <target> — get, for either MARKREAD or soju's READ. */
    fun get(
        command: String,
        target: String,
    ): IrcMessage = IrcMessage(command = command, params = listOf(target))
}
