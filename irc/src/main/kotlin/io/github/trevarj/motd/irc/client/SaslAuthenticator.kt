package io.github.trevarj.motd.irc.client

import io.github.trevarj.motd.irc.proto.IrcMessage
import java.util.Base64

/**
 * Drives one SASL exchange, fed line-by-line from the registration state machine.
 *
 * PLAIN: AUTHENTICATE PLAIN -> server `AUTHENTICATE +` -> base64(authzid\\0authcid\\0pass),
 * chunked at 400 bytes; a trailing empty AUTHENTICATE + when the payload length is an exact
 * multiple of 400 (so the server knows the response ended).
 * EXTERNAL: AUTHENTICATE EXTERNAL -> server `+` -> AUTHENTICATE + (identity from the TLS cert).
 *
 * Success = 903. Failure = 904/905/906/907 -> fatal (never retry bad credentials).
 */
internal class SaslAuthenticator(
    private val mechanism: SaslMechanism,
    private val user: String?,
    private val password: String?,
) {
    sealed interface Step {
        /** Emit these AUTHENTICATE lines, then keep feeding server responses. */
        data class Send(
            val lines: List<String>,
        ) : Step

        data object Done : Step

        data class Failed(
            val reason: String,
        ) : Step

        /** Not a SASL-relevant line; ignore. */
        data object Ignore : Step
    }

    /** The very first line to send to kick off the exchange. */
    fun begin(): String =
        when (mechanism) {
            SaslMechanism.PLAIN -> "AUTHENTICATE PLAIN"
            SaslMechanism.EXTERNAL -> "AUTHENTICATE EXTERNAL"
            SaslMechanism.NONE -> error("SASL begin with NONE")
        }

    /** Feed one inbound message; drive the SASL exchange forward. */
    fun onMessage(msg: IrcMessage): Step =
        when (msg.command) {
            "AUTHENTICATE" -> {
                // Server prompts with `AUTHENTICATE +` to request our response payload.
                if (msg.params.firstOrNull() == "+") {
                    when (mechanism) {
                        SaslMechanism.PLAIN -> Step.Send(plainResponse())
                        SaslMechanism.EXTERNAL -> Step.Send(listOf("AUTHENTICATE +"))
                        SaslMechanism.NONE -> Step.Ignore
                    }
                } else {
                    Step.Ignore
                }
            }

            "903" -> {
                Step.Done
            }

            // RPL_SASLSUCCESS
            "904", "905", "906", "907" -> {
                Step.Failed(
                    "SASL ${msg.command}: ${msg.params.lastOrNull().orEmpty()}",
                )
            }

            else -> {
                Step.Ignore
            }
        }

    private fun plainResponse(): List<String> {
        val u = user ?: ""
        val p = password ?: ""
        // soju's `account/network` selector is an authcid, not an authorization identity. Using it
        // for authzid as well authenticates successfully but can leave the child session in the
        // bouncer capability-transition state instead of the selected network. Ordinary account
        // logins retain the historical same-id form required by the IRC plan.
        val authzid = if ('/' in u) "" else u
        val raw = "${authzid}\u0000${u}\u0000$p".toByteArray(Charsets.UTF_8)
        val b64 = Base64.getEncoder().encodeToString(raw)
        return chunk(b64)
    }

    /** Split base64 into 400-byte AUTHENTICATE chunks, with an empty `+` when exactly aligned. */
    private fun chunk(payload: String): List<String> {
        if (payload.isEmpty()) return listOf("AUTHENTICATE +")
        val out = mutableListOf<String>()
        var i = 0
        while (i < payload.length) {
            val end = minOf(i + 400, payload.length)
            out.add("AUTHENTICATE ${payload.substring(i, end)}")
            i = end
        }
        // If the last chunk was a full 400 bytes, an empty continuation signals the end.
        if (payload.length % 400 == 0) out.add("AUTHENTICATE +")
        return out
    }
}
