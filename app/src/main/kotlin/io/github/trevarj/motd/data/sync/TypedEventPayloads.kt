package io.github.trevarj.motd.data.sync

import java.util.Base64

/** Versioned durable payload for [io.github.trevarj.motd.data.db.MessageKind.INVITE]. */
data class InvitePayloadV1(
    val inviter: String,
    val target: String,
    val channel: String,
) {
    fun encode(): String = "$VERSION:${encodeField(inviter)}:${encodeField(target)}:${encodeField(channel)}"

    companion object {
        /** Unknown versions and incomplete payloads deliberately degrade to rendered system text. */
        fun decode(value: String?): InvitePayloadV1? =
            runCatching {
                val parts = value?.split(':') ?: return null
                if (parts.size != 4 || parts[0] != VERSION) return null
                val inviter = String(DECODER.decode(parts[1]), Charsets.UTF_8)
                val target = String(DECODER.decode(parts[2]), Charsets.UTF_8)
                val channel = String(DECODER.decode(parts[3]), Charsets.UTF_8)
                if (target.isBlank() || channel.isBlank()) return null
                InvitePayloadV1(inviter, target, channel)
            }.getOrNull()

        private const val VERSION = "invite-v1"
        private val ENCODER = Base64.getUrlEncoder().withoutPadding()
        private val DECODER = Base64.getUrlDecoder()

        private fun encodeField(value: String): String = ENCODER.encodeToString(value.toByteArray(Charsets.UTF_8))
    }
}

/** Versioned durable payload for collapsed NETSPLIT/NETJOIN timeline events. */
data class NetworkBatchPayloadV1(
    val serverA: String,
    val serverB: String,
    val nicks: List<String>,
) {
    fun encode(): String =
        listOf(
            VERSION,
            encodeField(serverA),
            encodeField(serverB),
            nicks.joinToString(".") { encodeField(it) },
        ).joinToString(":")

    companion object {
        fun decode(value: String?): NetworkBatchPayloadV1? =
            runCatching {
                val parts = value?.split(':') ?: return null
                if (parts.size != 4 || parts[0] != VERSION) return null
                val serverA = decodeField(parts[1])
                val serverB = decodeField(parts[2])
                if (serverA.isBlank() || serverB.isBlank()) return null
                val nicks = if (parts[3].isBlank()) emptyList() else parts[3].split('.').map(::decodeField)
                if (nicks.any(String::isBlank)) return null
                NetworkBatchPayloadV1(serverA, serverB, nicks)
            }.getOrNull()

        private const val VERSION = "network-v1"
        private val ENCODER = Base64.getUrlEncoder().withoutPadding()
        private val DECODER = Base64.getUrlDecoder()

        private fun encodeField(value: String) = ENCODER.encodeToString(value.toByteArray(Charsets.UTF_8))

        private fun decodeField(value: String) = String(DECODER.decode(value), Charsets.UTF_8)
    }
}

data class DccFileOfferPayloadV1(
    val protocol: String,
    val filename: String,
    val address: String,
    val addressKind: String,
    val port: Int,
    val sizeBytes: Long?,
    val token: String?,
    val offerKey: String,
) {
    fun encode(): String =
        listOf(
            VERSION,
            encodePayloadField(protocol),
            encodePayloadField(filename),
            encodePayloadField(address),
            encodePayloadField(addressKind),
            port.toString(),
            sizeBytes?.toString().orEmpty(),
            token?.let(::encodePayloadField).orEmpty(),
            encodePayloadField(offerKey),
        ).joinToString(":")

    companion object {
        fun decode(value: String?): DccFileOfferPayloadV1? =
            runCatching {
                val parts = value?.split(':') ?: return null
                if (parts.size != 9 || parts[0] != VERSION) return null
                val protocol = decodePayloadField(parts[1])
                val filename = decodePayloadField(parts[2])
                val address = decodePayloadField(parts[3])
                val addressKind = decodePayloadField(parts[4])
                val port = parts[5].toIntOrNull() ?: return null
                val size = if (parts[6].isEmpty()) null else parts[6].toLongOrNull() ?: return null
                val token = parts[7].takeIf(String::isNotEmpty)?.let(::decodePayloadField)
                val offerKey = decodePayloadField(parts[8])
                if (protocol.isBlank() || filename.isBlank() || address.isBlank() || offerKey.isBlank()) return null
                DccFileOfferPayloadV1(protocol, filename, address, addressKind, port, size, token, offerKey)
            }.getOrNull()

        private const val VERSION = "dcc-file-v1"
    }
}

data class UnsupportedDccPayloadV1(
    val command: String?,
    val reason: String,
    val rawPayload: String,
) {
    fun encode(): String =
        listOf(
            VERSION,
            command?.let(::encodePayloadField).orEmpty(),
            encodePayloadField(reason),
            encodePayloadField(rawPayload),
        ).joinToString(":")

    companion object {
        fun decode(value: String?): UnsupportedDccPayloadV1? =
            runCatching {
                val parts = value?.split(':') ?: return null
                if (parts.size != 4 || parts[0] != VERSION) return null
                val command = parts[1].takeIf(String::isNotEmpty)?.let(::decodePayloadField)
                val reason = decodePayloadField(parts[2])
                val rawPayload = decodePayloadField(parts[3])
                if (reason.isBlank() || rawPayload.isBlank()) return null
                UnsupportedDccPayloadV1(command, reason, rawPayload)
            }.getOrNull()

        private const val VERSION = "dcc-unsupported-v1"
    }
}

private val PAYLOAD_ENCODER = Base64.getUrlEncoder().withoutPadding()
private val PAYLOAD_DECODER = Base64.getUrlDecoder()

private fun encodePayloadField(value: String): String = PAYLOAD_ENCODER.encodeToString(value.toByteArray(Charsets.UTF_8))

private fun decodePayloadField(value: String): String = String(PAYLOAD_DECODER.decode(value), Charsets.UTF_8)
