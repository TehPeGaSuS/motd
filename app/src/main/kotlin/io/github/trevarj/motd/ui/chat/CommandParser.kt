package io.github.trevarj.motd.ui.chat

/**
 * Pure translation of a composer line into a [ChatCommand]. No side effects, no Android/IRC deps,
 * so it is trivially unit-testable (WP7 acceptance). The ViewModel executes the returned command
 * against [io.github.trevarj.motd.service.ConnectionManager] / the network's client (plans/07).
 *
 * Lines that do not start with `/` are ordinary messages. A leading `//` escapes to a literal `/`
 * message. `/me` maps to a raw `/me` PRIVMSG (the manager translates it to an ACTION, plans/05).
 * Unknown `/cmd` becomes [ChatCommand.RawLine] with the leading slash stripped, sent verbatim via
 * `IrcMessage.parse` (plans/07).
 *
 * Commands that address a channel accept the target implicitly: run them in the channel's own
 * conversation and the ViewModel supplies the buffer's target, matching how `/topic` and `/kick`
 * already behave.
 */
sealed interface ChatCommand {
    /** Ordinary PRIVMSG (or `/me` action, which the manager rewrites). */
    data class Message(val text: String) : ChatCommand

    /**
     * `/join #chan`, `/join #chan key`, `/join #a,#b key-a,key-b`.
     *
     * [channels] and [keys] stay in their comma-joined wire form: JOIN takes exactly two parameters
     * and pairs them positionally, so splitting and rejoining them here would only risk losing that
     * pairing.
     */
    data class Join(val channels: String, val keys: String? = null) : ChatCommand

    /** `/part [reason]` — parts the current buffer. */
    data class Part(val reason: String?) : ChatCommand

    /** `/hop [reason]` — part the current channel and immediately rejoin it. */
    data class Hop(val reason: String?) : ChatCommand

    /** `/msg nick text` — DM with an immediate message. */
    data class Msg(val nick: String, val text: String) : ChatCommand

    /** `/query nick` — open a DM buffer, no message. */
    data class Query(val nick: String) : ChatCommand

    /** `/notice target text` — NOTICE, which by convention is not auto-replied to. */
    data class Notice(val target: String, val text: String) : ChatCommand

    /** `/nick newnick` — raw NICK on the current network's client. */
    data class Nick(val nick: String) : ChatCommand

    /** `/setname realname` — REALNAME/SETNAME for servers advertising `setname`. */
    data class SetName(val realname: String) : ChatCommand

    /** `/topic text` — set the current channel's topic. */
    data class Topic(val topic: String) : ChatCommand

    /**
     * `/mode [target] modes [args]` — null [target] means the current buffer's own target, and null
     * [modes] queries the modes currently set rather than changing any.
     */
    data class Mode(val target: String?, val modes: String?) : ChatCommand

    /** `/away [message]` — away with a message; `/back` or bare `/away` clears it (plans/16 §5.9). */
    data class Away(val message: String?) : ChatCommand

    /** `/whois nick` — open the nick sheet with WHOIS details (plans/16 §5.9). */
    data class Whois(val nick: String) : ChatCommand

    /** `/list` — open the channel browser for the current network (plans/16 §5.9). */
    data object ChannelList : ChatCommand

    /** `/kick nick [reason]` — kick from the current channel (plans/16 §5.9). */
    data class Kick(val nick: String, val reason: String?) : ChatCommand

    /** `/ban nick` — MODE +b nick!*@* on the current channel (plans/16 §5.9). */
    data class Ban(val nick: String) : ChatCommand

    /** `/invite nick [channel]` — null channel invites to the current channel. */
    data class Invite(val nick: String, val channel: String?) : ChatCommand

    /** `/knock #chan [reason]` — request an invite to an invite-only channel. */
    data class Knock(val channel: String, val reason: String?) : ChatCommand

    /** `/ctcp nick REQUEST [args]` — a CTCP request such as `PING`, `VERSION`, or `TIME`. */
    data class Ctcp(val nick: String, val request: String) : ChatCommand

    /** `/motd [server]` — re-request the message of the day. */
    data class Motd(val server: String?) : ChatCommand

    /** Unknown `/cmd args`, or an explicit `/raw line` — sent verbatim via `IrcMessage.parse`. */
    data class RawLine(val line: String) : ChatCommand

    /** Blank input / bare `/` — nothing to do. */
    data object None : ChatCommand
}

/** Commands that only mean something inside a channel conversation. */
val CHANNEL_ONLY_COMMANDS: Set<String> =
    setOf("/topic", "/kick", "/ban", "/invite", "/hop", "/part")

/**
 * The slash-commands offered in the composer hint popup, in display order. Aliases are deliberately
 * absent: they stay accepted on input without doubling the length of the popup.
 */
val COMMAND_HINTS: List<String> = listOf(
    "/me", "/join", "/part", "/hop", "/msg", "/query", "/notice", "/nick", "/setname",
    "/topic", "/mode", "/away", "/whois", "/list", "/kick", "/ban", "/invite", "/knock",
    "/ctcp", "/motd", "/raw",
)

/**
 * Hints appropriate for the conversation the composer is in. Channel-only commands are withheld
 * outside a channel rather than offered and then silently ignored.
 */
fun commandHintsFor(isChannel: Boolean): List<String> =
    if (isChannel) COMMAND_HINTS else COMMAND_HINTS.filterNot { it in CHANNEL_ONLY_COMMANDS }

/** Parse [raw] composer input into a [ChatCommand]. See the type doc for the rules. */
fun parseCommand(raw: String): ChatCommand {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return ChatCommand.None

    // Not a command — ordinary message. `//text` escapes a literal leading slash.
    if (!trimmed.startsWith("/")) return ChatCommand.Message(trimmed)
    if (trimmed.startsWith("//")) return ChatCommand.Message(trimmed.substring(1))

    // Split "/cmd" from the remainder (single space, remainder kept raw for text args).
    val afterSlash = trimmed.substring(1)
    val space = afterSlash.indexOf(' ')
    val cmd = (if (space < 0) afterSlash else afterSlash.substring(0, space)).lowercase()
    val rest = if (space < 0) "" else afterSlash.substring(space + 1).trim()

    // Bare "/" — nothing to send.
    if (cmd.isEmpty()) return ChatCommand.None

    return when (cmd) {
        "me", "describe" -> if (rest.isEmpty()) ChatCommand.None else ChatCommand.Message("/me $rest")
        "join", "j" -> {
            // JOIN's two parameters are positional lists: "#a,#b key-a,key-b".
            val channels = rest.firstWord()
            val keys = rest.afterFirstWord().firstWord().ifEmpty { null }
            if (channels.isEmpty()) ChatCommand.None else ChatCommand.Join(channels, keys)
        }
        "part", "leave" -> ChatCommand.Part(rest.ifEmpty { null })
        "hop", "rejoin" -> ChatCommand.Hop(rest.ifEmpty { null })
        "msg" -> {
            val nick = rest.firstWord()
            val text = rest.afterFirstWord()
            if (nick.isEmpty() || text.isEmpty()) ChatCommand.None else ChatCommand.Msg(nick, text)
        }
        "query" -> {
            val nick = rest.firstWord()
            if (nick.isEmpty()) ChatCommand.None else ChatCommand.Query(nick)
        }
        "notice" -> {
            val target = rest.firstWord()
            val text = rest.afterFirstWord()
            if (target.isEmpty() || text.isEmpty()) ChatCommand.None else ChatCommand.Notice(target, text)
        }
        "nick" -> {
            val nick = rest.firstWord()
            if (nick.isEmpty()) ChatCommand.None else ChatCommand.Nick(nick)
        }
        "setname" -> if (rest.isEmpty()) ChatCommand.None else ChatCommand.SetName(rest)
        "topic", "t" -> if (rest.isEmpty()) ChatCommand.None else ChatCommand.Topic(rest)
        "mode", "m" -> parseMode(rest)
        // `/away [msg]` sets away with a message; bare `/away` (and `/back`) clears it.
        "away" -> ChatCommand.Away(rest.ifEmpty { null })
        "back" -> ChatCommand.Away(null)
        "whois" -> {
            val nick = rest.firstWord()
            if (nick.isEmpty()) ChatCommand.None else ChatCommand.Whois(nick)
        }
        "list" -> ChatCommand.ChannelList
        "kick" -> {
            val nick = rest.firstWord()
            val reason = rest.afterFirstWord().ifEmpty { null }
            if (nick.isEmpty()) ChatCommand.None else ChatCommand.Kick(nick, reason)
        }
        "ban" -> {
            val nick = rest.firstWord()
            if (nick.isEmpty()) ChatCommand.None else ChatCommand.Ban(nick)
        }
        "invite" -> {
            val nick = rest.firstWord()
            val channel = rest.afterFirstWord().firstWord().ifEmpty { null }
            if (nick.isEmpty()) ChatCommand.None else ChatCommand.Invite(nick, channel)
        }
        "knock" -> {
            val channel = rest.firstWord()
            val reason = rest.afterFirstWord().ifEmpty { null }
            if (channel.isEmpty()) ChatCommand.None else ChatCommand.Knock(channel, reason)
        }
        "ctcp" -> {
            val nick = rest.firstWord()
            val request = rest.afterFirstWord()
            if (nick.isEmpty() || request.isEmpty()) ChatCommand.None else ChatCommand.Ctcp(nick, request)
        }
        "motd" -> ChatCommand.Motd(rest.firstWord().ifEmpty { null })
        // Explicit escape hatch, so a line can reach the server even when its first word collides
        // with a command handled above.
        "raw", "quote" -> if (rest.isEmpty()) ChatCommand.None else ChatCommand.RawLine(rest)
        // Unknown command: pass through raw, slash stripped (e.g. "names").
        else -> ChatCommand.RawLine(afterSlash)
    }
}

/**
 * `/mode`, `/mode +o nick`, `/mode #chan +o nick`, `/mode #chan`.
 *
 * A leading `+`/`-` token is always a mode string, so anything else in that position is the target.
 * The one case this cannot disambiguate is a `+`-prefixed (modeless) channel name — which by
 * definition takes no modes anyway; reach one with `/raw MODE` if a server still offers them.
 */
private fun parseMode(rest: String): ChatCommand {
    if (rest.isEmpty()) return ChatCommand.Mode(target = null, modes = null)
    val first = rest.firstWord()
    if (first.startsWith("+") || first.startsWith("-")) {
        return ChatCommand.Mode(target = null, modes = rest)
    }
    return ChatCommand.Mode(target = first, modes = rest.afterFirstWord().ifEmpty { null })
}

private fun String.firstWord(): String = substringBefore(' ').trim()

private fun String.afterFirstWord(): String = substringAfter(' ', "").trim()
