package io.github.trevarj.motd.ui.channelinfo

import io.github.trevarj.motd.data.db.MemberEntity
import io.github.trevarj.motd.data.prefs.matchesConfiguredNick
import io.github.trevarj.motd.irc.proto.IrcIdentityRules

/**
 * Pure member-list sectioning by highest channel prefix. Fully unit-testable; no Android deps.
 *
 * A member's [MemberEntity.prefixes] holds the prefix glyphs they hold (e.g. "@+"). Members are
 * grouped by their *highest* prefix using [prefixOrder] (most privileged first), with unprefixed
 * members last. Within a section, members sort case-insensitively by nick.
 */

/** Sensible fallback prefix ordering when ISUPPORT PREFIX is unavailable. */
const val DEFAULT_PREFIX_ORDER: String = "~&@%+"

data class MemberSection(
    /** Highest prefix glyph for this section, or null for the unprefixed (regular) section. */
    val prefix: Char?,
    val members: List<MemberEntity>,
)

/**
 * Build sections. [prefixOrder] is the ordered prefix glyphs, most privileged first (from
 * `client.isupport.prefixModes` prefixes, mapped to glyph order). Falls back to
 * [DEFAULT_PREFIX_ORDER] when empty. [comparator] orders members within a section (defaults to
 * alphabetical by normalized nick).
 */
fun sectionMembers(
    members: List<MemberEntity>,
    prefixOrder: String = DEFAULT_PREFIX_ORDER,
    identityRules: IrcIdentityRules = IrcIdentityRules(),
    comparator: Comparator<MemberEntity> = identityRules.memberComparator(),
): List<MemberSection> {
    val order = prefixOrder.ifEmpty { DEFAULT_PREFIX_ORDER }
    val rank: (Char) -> Int = { c -> order.indexOf(c).let { if (it < 0) Int.MAX_VALUE else it } }

    // Highest prefix = the one with the smallest rank among the member's held prefixes.
    fun highest(m: MemberEntity): Char? = m.prefixes.minByOrNull(rank).takeIf { it != null && rank(it) != Int.MAX_VALUE }

    val grouped = members.groupBy { highest(it) }

    // Section order: known prefixes by [order], then the null (regular) bucket last.
    val prefixSections =
        order
            .mapNotNull { glyph ->
                grouped[glyph]?.let { list ->
                    MemberSection(glyph, list.sortedWith(comparator))
                }
            }
    val regular =
        grouped[null]?.let { list ->
            MemberSection(null, list.sortedWith(comparator))
        }

    return prefixSections + listOfNotNull(regular)
}

/**
 * Derive the prefix-glyph order (most privileged first) from ISUPPORT prefixModes
 * (mode->prefix pairs, already in privilege order). Empty input yields the default order.
 */
fun prefixOrderFrom(prefixModes: List<Pair<Char, Char>>): String =
    if (prefixModes.isEmpty()) {
        DEFAULT_PREFIX_ORDER
    } else {
        prefixModes.joinToString("") { it.second.toString() }
    }

/**
 * Prefix sections with fools pulled out into a trailing bucket. Fool members
 * using the network's IRC casemapping are removed from every prefix section and returned separately,
 * sorted case-insensitively. Friends are NOT moved — they stay in their prefix section (they
 * only gain a star on the row). Empty [fools] reproduces the plain [sectionMembers] result.
 *
 * [comparator] orders members within each prefix section; the fools bucket stays alphabetical
 * (fools are a separate trailing bucket, not a prefix section), so it ignores [comparator].
 */
data class SocialSections(
    val sections: List<MemberSection>,
    val fools: List<MemberEntity>,
)

fun sectionMembersSocial(
    members: List<MemberEntity>,
    prefixOrder: String = DEFAULT_PREFIX_ORDER,
    fools: Set<String> = emptySet(),
    identityRules: IrcIdentityRules = IrcIdentityRules(),
    comparator: Comparator<MemberEntity> = identityRules.memberComparator(),
): SocialSections {
    val (foolMembers, rest) =
        members.partition {
            identityRules.matchesConfiguredNick(it.nick, fools)
        }
    return SocialSections(
        sections = sectionMembers(rest, prefixOrder, identityRules, comparator),
        fools = foolMembers.sortedWith(identityRules.memberComparator()),
    )
}

private fun IrcIdentityRules.memberComparator(): Comparator<MemberEntity> = compareBy<MemberEntity> { normalize(it.nick) }.thenBy { it.nick }

/**
 * Orders members by last-spoke time descending, with never-spoke members (null) sorting last
 * (mapped to [Long.MIN_VALUE] so they fall below any real timestamp), then falls back to the
 * alphabetical [IrcIdentityRules.memberComparator] for ties. Used to rank a prefix section by
 * recent channel activity.
 */
fun activityMemberComparator(
    identityRules: IrcIdentityRules,
    lastSpokeAt: (MemberEntity) -> Long?,
): Comparator<MemberEntity> {
    val alpha = identityRules.memberComparator()
    return compareByDescending<MemberEntity> { lastSpokeAt(it) ?: Long.MIN_VALUE }
        .thenComparator { a, b -> alpha.compare(a, b) }
}
