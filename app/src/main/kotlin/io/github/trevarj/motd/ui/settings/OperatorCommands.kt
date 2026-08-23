package io.github.trevarj.motd.ui.settings

import io.github.trevarj.motd.irc.proto.IrcMessage

/**
 * Pure builders for the network operator commands. The screen builds the [IrcMessage] once, shows
 * it in the confirmation preview and hands that same instance to the ViewModel, so what the user
 * approves and what reaches the transport cannot diverge.
 */

/** Which operator command a pending confirmation belongs to, for its blast-radius copy. */
enum class OperatorCommandKind(
    val commandName: String,
) {
    KILL("KILL"),
    REHASH("REHASH"),
    CONNECT("CONNECT"),
    SQUIT("SQUIT"),
}

fun operMessage(
    username: String,
    password: String,
): IrcMessage = IrcMessage(command = "OPER", params = listOf(username.trim(), password))

fun modeMessage(
    target: String,
    modes: String,
    args: String,
): IrcMessage = IrcMessage(command = "MODE", params = listOf(target.trim(), modes.trim()) + splitOperatorArgs(args))

fun killMessage(
    nick: String,
    reason: String,
): IrcMessage = IrcMessage(command = "KILL", params = listOf(nick.trim(), reason.trim()))

fun rehashMessage(server: String): IrcMessage = IrcMessage(command = "REHASH", params = listOfNotNull(server.trim().takeIf(String::isNotBlank)))

fun connectMessage(
    server: String,
    port: String,
    remote: String,
): IrcMessage =
    IrcMessage(
        command = "CONNECT",
        params =
            listOfNotNull(
                server.trim(),
                port.trim().takeIf(String::isNotBlank),
                remote.trim().takeIf(String::isNotBlank),
            ),
    )

fun squitMessage(
    server: String,
    reason: String,
): IrcMessage = IrcMessage(command = "SQUIT", params = listOf(server.trim(), reason.trim()))

/**
 * The exact line the transport will write. [IrcMessage.serialize] already excludes the trailing
 * CRLF the transport appends, so this is the wire line verbatim; null when the message could not
 * be serialized at all (CR/LF injection or a length overflow), which the send path reports too.
 *
 * Only ever show this for the destructive commands. OPER carries a password and must never be
 * previewed.
 */
fun IrcMessage.previewLine(): String? = runCatching { serialize() }.getOrNull()

internal fun splitOperatorArgs(raw: String): List<String> = raw.split(' ').map(String::trim).filter(String::isNotBlank)
