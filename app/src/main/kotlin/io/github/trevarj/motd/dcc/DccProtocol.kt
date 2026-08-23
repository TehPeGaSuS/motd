package io.github.trevarj.motd.dcc

import io.github.trevarj.motd.data.db.DccAddressKind
import io.github.trevarj.motd.data.db.DccTransferProtocol
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.nio.ByteBuffer

private const val CTCP_DELIMITER = '\u0001'

data class DccOutgoingOffer(
    val protocol: DccTransferProtocol,
    val filename: String,
    val address: String,
    val port: Int,
    val sizeBytes: Long?,
    val token: String? = null,
)

fun dccSendCtcp(offer: DccOutgoingOffer): String {
    val command =
        when (offer.protocol) {
            DccTransferProtocol.SEND -> "SEND"
            DccTransferProtocol.SSEND -> "SSEND"
        }
    val size = offer.sizeBytes?.let { " $it" }.orEmpty()
    val token = offer.token?.let { " $it" }.orEmpty()
    return "$CTCP_DELIMITER" +
        "DCC $command ${quoteDccToken(offer.filename)} ${offer.address} ${offer.port}$size$token" +
        "$CTCP_DELIMITER"
}

fun dccResumeCtcp(
    filename: String,
    port: Int,
    positionBytes: Long,
    token: String?,
): String =
    "$CTCP_DELIMITER" +
        "DCC RESUME ${quoteDccToken(filename)} $port $positionBytes${token?.let { " $it" }.orEmpty()}" +
        "$CTCP_DELIMITER"

fun dccAcceptCtcp(
    filename: String,
    port: Int,
    positionBytes: Long,
    token: String?,
): String =
    "$CTCP_DELIMITER" +
        "DCC ACCEPT ${quoteDccToken(filename)} $port $positionBytes${token?.let { " $it" }.orEmpty()}" +
        "$CTCP_DELIMITER"

fun quoteDccToken(value: String): String {
    require(value.isNotBlank()) { "DCC token must not be blank" }
    require(value.length <= 255) { "DCC token too long" }
    require(value.none(Char::isISOControl)) { "DCC token contains control characters" }
    val escaped =
        buildString(value.length) {
            value.forEach { ch ->
                when (ch) {
                    '\\', '"' -> append('\\').append(ch)
                    else -> append(ch)
                }
            }
        }
    return if (escaped.any(Char::isWhitespace)) "\"$escaped\"" else escaped
}

fun resolveDccAddress(
    address: String,
    kind: DccAddressKind,
): InetAddress =
    when (kind) {
        DccAddressKind.IPV4_INTEGER -> {
            val numeric = address.toLong()
            val bytes = ByteBuffer.allocate(Int.SIZE_BYTES).putInt(numeric.toInt()).array()
            InetAddress.getByAddress(bytes)
        }

        DccAddressKind.IPV4_DOTTED -> {
            InetAddress.getByName(address)
        }

        DccAddressKind.IPV6_LITERAL -> {
            InetAddress.getByName(address.removeSurrounding("[", "]"))
        }
    }

fun advertiseDccAddress(address: InetAddress): Pair<String, DccAddressKind> =
    when (address) {
        is Inet4Address -> {
            val numeric =
                ByteBuffer
                    .wrap(address.address)
                    .int
                    .toUInt()
                    .toLong()
            numeric.toString() to DccAddressKind.IPV4_INTEGER
        }

        is Inet6Address -> {
            address.hostAddress.orEmpty() to DccAddressKind.IPV6_LITERAL
        }

        else -> {
            address.hostAddress.orEmpty() to DccAddressKind.IPV4_DOTTED
        }
    }

enum class DccEndpointRisk {
    PUBLIC,
    PRIVATE,
    LOOPBACK,
    LINK_LOCAL,
    MULTICAST,
    UNSPECIFIED,
}

fun dccEndpointRisk(address: InetAddress): DccEndpointRisk =
    when {
        address.isAnyLocalAddress -> DccEndpointRisk.UNSPECIFIED
        address.isLoopbackAddress -> DccEndpointRisk.LOOPBACK
        address.isMulticastAddress -> DccEndpointRisk.MULTICAST
        address.isLinkLocalAddress -> DccEndpointRisk.LINK_LOCAL
        address is Inet6Address && ((address.address.first().toInt() and 0xfe) == 0xfc) -> DccEndpointRisk.PRIVATE
        address.isSiteLocalAddress -> DccEndpointRisk.PRIVATE
        else -> DccEndpointRisk.PUBLIC
    }

fun sanitizeDccDisplayFilename(filename: String): String {
    val leaf = filename.replace('\\', '/').substringAfterLast('/').trim()
    return leaf
        .filterNot(Char::isISOControl)
        .take(255)
        .ifBlank { "download" }
}
