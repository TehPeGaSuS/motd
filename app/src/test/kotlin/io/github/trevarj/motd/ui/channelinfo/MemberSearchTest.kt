package io.github.trevarj.motd.ui.channelinfo

import io.github.trevarj.motd.data.db.MemberEntity
import io.github.trevarj.motd.irc.proto.IrcCaseMapping
import io.github.trevarj.motd.irc.proto.IrcIdentityRules
import org.junit.Assert.assertEquals
import org.junit.Test

class MemberSearchTest {
    private val ascii = IrcIdentityRules(caseMapping = IrcCaseMapping.Ascii)

    private fun member(nick: String) = MemberEntity(bufferId = 1, nick = nick, prefixes = "")

    private fun names(
        query: String,
        members: List<MemberEntity>,
        lastSpokeAt: (MemberEntity) -> Long? = { null },
        rules: IrcIdentityRules = ascii,
    ): List<String> = rankMembersFuzzy(query, members, rules::normalize, lastSpokeAt).map { it.nick }

    @Test
    fun `blank query returns no results`() {
        assertEquals(emptyList<String>(), names("", listOf(member("alice"))))
    }

    @Test
    fun `no matches returns empty`() {
        assertEquals(emptyList<String>(), names("zzz", listOf(member("alice"), member("bob"))))
    }

    @Test
    fun `exact beats prefix`() {
        val members = listOf(member("alice2"), member("alice"))
        assertEquals(listOf("alice", "alice2"), names("alice", members))
    }

    @Test
    fun `prefix beats substring beats subsequence`() {
        // query "al": "alan" prefix (tier 1), "salad" substring (tier 2), "axl" subsequence (tier 3).
        val members = listOf(member("axl"), member("salad"), member("alan"))
        assertEquals(listOf("alan", "salad", "axl"), names("al", members))
    }

    @Test
    fun `substring beats subsequence`() {
        // "li" is a substring of "alice"; only a subsequence of "lx i" -> "lxi".
        val members = listOf(member("alice"), member("lxi"))
        assertEquals(listOf("alice", "lxi"), names("li", members))
    }

    @Test
    fun `firstMatchOffset tiebreaker prefers earlier match`() {
        // Both substring matches for "a"; "alpha" at offset 0, "delta" at offset 4.
        val members = listOf(member("delta"), member("alpha"))
        assertEquals(listOf("alpha", "delta"), names("a", members))
    }

    @Test
    fun `lastSpokeAt descending nulls last`() {
        // All three prefix-match "z" (tier 1), so the only differentiator is lastSpokeAt.
        val members = listOf(member("zsilent"), member("zrecent"), member("zolder"))
        val lastSpoke = mapOf("zrecent" to 2000L, "zolder" to 1000L)
        val lookup: (MemberEntity) -> Long? = { lastSpoke[it.nick] }
        val out = rankMembersFuzzy("z", members, ascii::normalize, lookup).map { it.nick }
        // most recent first; never-spoke ("zsilent") last.
        assertEquals(listOf("zrecent", "zolder", "zsilent"), out)
    }

    @Test
    fun `tier and offset ties fall back to alphabetical`() {
        // Both prefix-match "x" with the same offset/span and null lastSpoke -> alpha by nick.
        val members = listOf(member("xbob"), member("xalice"))
        val lookup: (MemberEntity) -> Long? = { null }
        assertEquals(listOf("xalice", "xbob"), names("x", members, lookup))
    }

    @Test
    fun `normalize applied case-insensitively`() {
        val members = listOf(member("Alice"))
        assertEquals(listOf("Alice"), names("ALICE", members))
        assertEquals(listOf("Alice"), names("alice", members))
    }

    @Test
    fun `fools are searchable since the helper is fool-agnostic`() {
        val members = listOf(member("alice"), member("troll"))
        assertEquals(listOf("alice", "troll"), names("l", members))
    }

    @Test
    fun `subsequence kernel rejects non-subsequence`() {
        // "zyx" is not a subsequence of "abc" (wrong order).
        assertEquals(emptyList<String>(), names("zyx", listOf(member("abc"))))
    }
}
