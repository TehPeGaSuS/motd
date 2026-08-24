package io.github.trevarj.motd.irc.client

import io.github.trevarj.motd.irc.proto.IrcMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LabelCorrelatorTest {
    @Test
    fun `buffer limit fails only offending labeled request`() =
        runTest {
            val correlator = LabelCorrelator(maxBufferedMessages = 2)
            val failed = CompletableDeferred<CorrelatedResponse>()
            correlator.register("limited", "SEARCH", failed)
            correlator.route(message("@label=limited BATCH +root labeled-response"))
            assertTrue(correlator.route(message("@batch=root :srv NOTICE me :one")))
            assertTrue(correlator.route(message("@batch=root :srv NOTICE me :two")))
            assertTrue(correlator.route(message("@batch=root :srv NOTICE me :three")))

            val failure = runCatching { failed.await() }.exceptionOrNull()
            assertTrue(failure is IrcProtocolException)

            val healthy = CompletableDeferred<CorrelatedResponse>()
            correlator.register("healthy", "PING", healthy)
            correlator.route(message("@label=healthy :srv PONG me :ok"))
            assertEquals(
                "PONG",
                healthy
                    .await()
                    .messages
                    .single()
                    .command,
            )
        }

    @Test
    fun `streaming response retains framing but no payload list`() =
        runTest {
            val correlator = LabelCorrelator(maxBufferedMessages = 1)
            val response = CompletableDeferred<CorrelatedResponse>()
            var consumed = 0
            correlator.register("stream", "LIST", response) { consumed++ }
            correlator.route(message("@label=stream BATCH +root labeled-response"))
            repeat(100) { correlator.route(message("@batch=root :srv 322 me #c$it $it :topic")) }
            correlator.route(message("BATCH -root"))

            assertEquals(100, consumed)
            assertTrue(response.await().messages.isEmpty())
        }

    private fun message(line: String): IrcMessage = IrcMessage.parse(line)
}
