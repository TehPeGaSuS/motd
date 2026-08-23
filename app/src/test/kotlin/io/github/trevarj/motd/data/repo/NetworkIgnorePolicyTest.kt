package io.github.trevarj.motd.data.repo

import io.github.trevarj.motd.data.db.NetworkIgnoreEntity
import io.github.trevarj.motd.irc.proto.IrcCaseMapping
import io.github.trevarj.motd.irc.proto.IrcIdentityRules
import io.github.trevarj.motd.irc.proto.Prefix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkIgnorePolicyTest {
    @Test fun `bare nick becomes nick hostmask`() {
        assertEquals("alice!*@*", normalizeIgnorePattern(" alice ").getOrThrow())
    }

    @Test fun `user host mask gets wildcard nick`() {
        assertEquals("*!user@example.net", normalizeIgnorePattern("user@example.net").getOrThrow())
    }

    @Test fun `line breaks are rejected`() {
        assertTrue(normalizeIgnorePattern("alice\nOPER bad").isFailure)
    }

    @Test fun `enabled ignore matches source hostmask`() {
        val ignores = listOf(ignore("a?ice!*@*.example.net"))
        assertTrue(
            ignoredBy(
                ignores,
                Prefix("alice", "ident", "chat.example.net"),
                IrcIdentityRules(),
            ),
        )
    }

    @Test fun `disabled ignore does not match`() {
        val ignores = listOf(ignore("alice!*@*", enabled = false))
        assertFalse(ignoredBy(ignores, Prefix("alice", "u", "h"), IrcIdentityRules()))
    }

    @Test fun `nick matching follows irc case mapping`() {
        val ignores = listOf(ignore("[lice!*@*"))
        assertTrue(
            ignoredBy(
                ignores,
                Prefix("{lice", "u", "h"),
                IrcIdentityRules(IrcCaseMapping.Rfc1459),
            ),
        )
        assertFalse(
            ignoredBy(
                ignores,
                Prefix("{lice", "u", "h"),
                IrcIdentityRules(IrcCaseMapping.Ascii),
            ),
        )
    }

    private fun ignore(
        pattern: String,
        enabled: Boolean = true,
    ) = NetworkIgnoreEntity(id = 1, networkId = 1, pattern = pattern, enabled = enabled, createdAt = 1)
}
