package io.github.trevarj.motd.service

import io.github.trevarj.motd.irc.event.IrcClientState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [readyStateDiagnosticFields]. The diagnostic sanitizer clips every value to 256
 * chars, so a real soju cap list (474 chars) loses its tail in the `caps` field; the derived
 * boolean fields must carry the feature gates independently of that clipping. Pure-function
 * testing style matching [ActorRebuildTest].
 */
class ReadyStateDiagnosticFieldsTest {

    /** The cap set a real released soju (0.10.x) advertises, values included. */
    private val sojuReleaseCaps = setOf(
        "draft/pre-away", "draft/no-implicit-names", "cap-notify", "soju.im/search",
        "message-tags", "batch", "draft/chathistory", "extended-join", "draft/message-redaction",
        "invite-notify", "soju.im/no-implicit-names", "sasl=PLAIN", "multi-prefix",
        "soju.im/account-required", "account-notify", "chghost", "server-time",
        "draft/metadata-2=before-connect", "account-tag", "away-notify", "soju.im/webpush",
        "draft/read-marker", "soju.im/bouncer-networks", "soju.im/read",
        "soju.im/bouncer-networks-notify", "extended-monitor", "setname", "echo-message",
        "draft/extended-monitor",
    )

    private fun ready(caps: Set<String>) =
        IrcClientState.Ready(nick = "motd", caps = caps, isupport = emptyMap())

    @Test
    fun `released soju caps derive search gates the clipped caps field loses`() {
        val fields = readyStateDiagnosticFields(ready(sojuReleaseCaps))

        // Premise of the derived fields: the joined list overflows the 256-char sanitizer budget.
        assertTrue((fields["caps"] as String).length > 256)
        assertEquals(sojuReleaseCaps.size, fields["caps_count"])
        assertEquals(false, fields["cap_labeled_response"])
        assertEquals(true, fields["cap_soju_search"])
        assertEquals(true, fields["cap_chathistory"])
        assertEquals(true, fields["search_available"])
    }

    @Test
    fun `cap names are compared with values stripped`() {
        // A valued advertisement still counts, matching IrcClient.hasCap().
        val fields = readyStateDiagnosticFields(
            ready(setOf("soju.im/search=v2", "draft/chathistory=limit-100")),
        )
        assertEquals(true, fields["cap_soju_search"])
        assertEquals(true, fields["cap_chathistory"])
    }

    @Test
    fun `absent caps derive false gates`() {
        val fields = readyStateDiagnosticFields(ready(setOf("message-tags", "server-time", "batch")))
        assertEquals(false, fields["cap_soju_search"])
        assertEquals(false, fields["cap_labeled_response"])
        assertEquals(false, fields["cap_chathistory"])
        assertEquals(false, fields["search_available"])
    }

    @Test
    fun `no field is named reason or carries identity`() {
        val fields = readyStateDiagnosticFields(ready(sojuReleaseCaps))
        // `reason` is redacted by the sanitizer and identity-ish names must never appear.
        for (banned in listOf("reason", "nick", "account", "host", "credential", "password")) {
            assertFalse("field $banned must not be recorded", banned in fields.keys)
        }
    }
}
