package io.github.trevarj.motd.irc.client

import io.github.trevarj.motd.irc.event.IrcEvent
import java.net.Inet6Address
import java.net.InetAddress

internal sealed interface ParsedDcc {
    data class Send(val offer: IrcEvent.DccSendOffer) : ParsedDcc
    data class Chat(val offer: IrcEvent.DccChatOffer) : ParsedDcc
    data class Resume(val request: IrcEvent.DccResumeRequest) : ParsedDcc
    data class Accept(val accepted: IrcEvent.DccResumeAccepted) : ParsedDcc
    data class Unsupported(
        val command: String?,
        val reason: IrcEvent.DccUnsupportedReason,
    ) : ParsedDcc
}

internal fun parseDccPayload(payload: String): ParsedDcc? {
    if (!payload.startsWith("DCC")) return null
    if (payload.length != 3 && payload.getOrNull(3) != ' ') {
        return null
    }
    val tokens = tokenizeDcc(payload).getOrNull()
        ?: return ParsedDcc.Unsupported(null, IrcEvent.DccUnsupportedReason.MALFORMED)
    if (tokens.firstOrNull() != "DCC") return null
    val command = tokens.getOrNull(1)?.uppercase()
        ?: return ParsedDcc.Unsupported(null, IrcEvent.DccUnsupportedReason.MALFORMED)
    return when (command) {
        "SEND", "SSEND" -> parseDccSend(command, tokens)
        "CHAT", "SCHAT" -> parseDccChat(command, tokens)
        "RESUME" -> parseDccResume(tokens)
        "ACCEPT" -> parseDccAccept(tokens)
        else -> ParsedDcc.Unsupported(command, IrcEvent.DccUnsupportedReason.UNKNOWN_COMMAND)
    }
}

private fun parseDccSend(command: String, tokens: List<String>): ParsedDcc {
    if (tokens.size !in 5..7) {
        return ParsedDcc.Unsupported(command, IrcEvent.DccUnsupportedReason.MALFORMED)
    }
    val filename = validDccText(tokens[2], maxLength = 255)
        ?: return ParsedDcc.Unsupported(command, IrcEvent.DccUnsupportedReason.MALFORMED)
    val endpoint = parseDccEndpoint(tokens[3], tokens[4], token = tokens.getOrNull(6))
        ?: return ParsedDcc.Unsupported(command, IrcEvent.DccUnsupportedReason.MALFORMED)
    val size = tokens.getOrNull(5)?.parseDccNonNegativeLong()
        ?: if (tokens.size >= 6) return ParsedDcc.Unsupported(
            command,
            IrcEvent.DccUnsupportedReason.MALFORMED,
        ) else null
    val token = parseOptionalDccToken(tokens.getOrNull(6))
        ?: if (tokens.size >= 7) return ParsedDcc.Unsupported(
            command,
            IrcEvent.DccUnsupportedReason.MALFORMED,
        ) else null
    return ParsedDcc.Send(
        IrcEvent.DccSendOffer(
            protocol = if (command == "SSEND") IrcEvent.DccFileProtocol.SSEND else IrcEvent.DccFileProtocol.SEND,
            filename = filename,
            endpoint = endpoint,
            sizeBytes = size,
            token = token,
        ),
    )
}

private fun parseDccChat(command: String, tokens: List<String>): ParsedDcc {
    if (tokens.size !in 5..6 || tokens[2].lowercase() != "chat") {
        return ParsedDcc.Unsupported(command, IrcEvent.DccUnsupportedReason.MALFORMED)
    }
    val endpoint = parseDccEndpoint(tokens[3], tokens[4], token = tokens.getOrNull(5))
        ?: return ParsedDcc.Unsupported(command, IrcEvent.DccUnsupportedReason.MALFORMED)
    val token = parseOptionalDccToken(tokens.getOrNull(5))
        ?: if (tokens.size >= 6) return ParsedDcc.Unsupported(
            command,
            IrcEvent.DccUnsupportedReason.MALFORMED,
        ) else null
    return ParsedDcc.Chat(
        IrcEvent.DccChatOffer(
            protocol = if (command == "SCHAT") IrcEvent.DccChatProtocol.SCHAT else IrcEvent.DccChatProtocol.CHAT,
            endpoint = endpoint,
            token = token,
        ),
    )
}

private fun parseDccResume(tokens: List<String>): ParsedDcc {
    if (tokens.size !in 5..6) {
        return ParsedDcc.Unsupported("RESUME", IrcEvent.DccUnsupportedReason.MALFORMED)
    }
    val filename = validDccText(tokens[2], maxLength = 255)
        ?: return ParsedDcc.Unsupported("RESUME", IrcEvent.DccUnsupportedReason.MALFORMED)
    val port = parseDccPort(tokens[3], token = tokens.getOrNull(5))
        ?: return ParsedDcc.Unsupported("RESUME", IrcEvent.DccUnsupportedReason.MALFORMED)
    val position = tokens[4].parseDccNonNegativeLong()
        ?: return ParsedDcc.Unsupported("RESUME", IrcEvent.DccUnsupportedReason.MALFORMED)
    val token = parseOptionalDccToken(tokens.getOrNull(5))
        ?: if (tokens.size >= 6) return ParsedDcc.Unsupported(
            "RESUME",
            IrcEvent.DccUnsupportedReason.MALFORMED,
        ) else null
    return ParsedDcc.Resume(IrcEvent.DccResumeRequest(filename, port, position, token))
}

private fun parseDccAccept(tokens: List<String>): ParsedDcc {
    if (tokens.size !in 5..6) {
        return ParsedDcc.Unsupported("ACCEPT", IrcEvent.DccUnsupportedReason.MALFORMED)
    }
    val filename = validDccText(tokens[2], maxLength = 255)
        ?: return ParsedDcc.Unsupported("ACCEPT", IrcEvent.DccUnsupportedReason.MALFORMED)
    val port = parseDccPort(tokens[3], token = tokens.getOrNull(5))
        ?: return ParsedDcc.Unsupported("ACCEPT", IrcEvent.DccUnsupportedReason.MALFORMED)
    val position = tokens[4].parseDccNonNegativeLong()
        ?: return ParsedDcc.Unsupported("ACCEPT", IrcEvent.DccUnsupportedReason.MALFORMED)
    val token = parseOptionalDccToken(tokens.getOrNull(5))
        ?: if (tokens.size >= 6) return ParsedDcc.Unsupported(
            "ACCEPT",
            IrcEvent.DccUnsupportedReason.MALFORMED,
        ) else null
    return ParsedDcc.Accept(IrcEvent.DccResumeAccepted(filename, port, position, token))
}

private fun tokenizeDcc(payload: String): Result<List<String>> = runCatching {
    val tokens = mutableListOf<String>()
    var i = 0
    while (i < payload.length) {
        while (i < payload.length && payload[i] == ' ') i++
        if (i >= payload.length) break
        if (payload[i] == '"') {
            val value = StringBuilder()
            i++
            var closed = false
            while (i < payload.length) {
                val ch = payload[i++]
                when {
                    ch == '"' -> {
                        closed = true
                        break
                    }
                    ch == '\\' && i < payload.length -> value.append(payload[i++])
                    else -> value.append(ch)
                }
            }
            require(closed) { "unterminated quoted token" }
            require(i >= payload.length || payload[i] == ' ') { "quoted token not delimited" }
            tokens += value.toString()
        } else {
            val start = i
            while (i < payload.length && payload[i] != ' ') i++
            tokens += payload.substring(start, i)
        }
    }
    tokens
}

private fun parseDccEndpoint(address: String, portToken: String, token: String?): IrcEvent.DccEndpoint? {
    val addressKind = parseDccAddressKind(address) ?: return null
    val port = parseDccPort(portToken, token) ?: return null
    return IrcEvent.DccEndpoint(address, port, addressKind)
}

private fun parseDccAddressKind(address: String): IrcEvent.DccAddressKind? {
    val value = validDccText(address, maxLength = 128) ?: return null
    if (value.all(Char::isDigit)) {
        val numeric = value.toULongOrNull() ?: return null
        return if (numeric <= UInt.MAX_VALUE.toULong()) IrcEvent.DccAddressKind.IPV4_INTEGER else null
    }
    val octets = value.split('.')
    if (octets.size == 4 && octets.all { it.isNotEmpty() && it.all(Char::isDigit) }) {
        return if (octets.all { it.toIntOrNull()?.let { octet -> octet in 0..255 } == true }) {
            IrcEvent.DccAddressKind.IPV4_DOTTED
        } else {
            null
        }
    }
    val literal = value.removeSurrounding("[", "]")
    if (literal.indexOf(':') >= 0 && literal.all { it.code in 0x21..0x7e }) {
        return if (runCatching { InetAddress.getByName(literal) }.getOrNull() is Inet6Address) {
            IrcEvent.DccAddressKind.IPV6_LITERAL
        } else {
            null
        }
    }
    return null
}

private fun parseDccPort(value: String, token: String?): Int? {
    val port = value.toIntOrNull() ?: return null
    if (port !in 0..65535) return null
    if (port == 0 && token.isNullOrBlank()) return null
    return port
}

private fun String.parseDccNonNegativeLong(): Long? =
    takeIf { it.isNotEmpty() && it.all(Char::isDigit) }?.toLongOrNull()

private fun parseOptionalDccToken(value: String?): String? =
    value?.let { validDccText(it, maxLength = 128)?.takeIf(String::isNotEmpty) }

private fun validDccText(value: String, maxLength: Int): String? =
    value.takeIf { it.isNotEmpty() && it.length <= maxLength && it.none(Char::isISOControl) }
