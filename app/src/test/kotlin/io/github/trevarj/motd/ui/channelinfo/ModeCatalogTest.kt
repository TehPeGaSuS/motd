package io.github.trevarj.motd.ui.channelinfo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModeCatalogTest {

    @Test fun chanmodes_splits_into_four_groups() {
        val catalog = ModeCatalog.from(mapOf("CHANMODES" to "beI,k,l,imnpstn"))
        assertEquals("beI".toSet(), catalog.listModes)
        assertEquals("k".toSet(), catalog.paramModes)
        assertEquals("l".toSet(), catalog.setParamModes)
        assertEquals("imnpstn".toSet(), catalog.flagModes)
    }

    @Test fun chanmodes_groups_past_the_fourth_are_ignored() {
        val catalog = ModeCatalog.from(mapOf("CHANMODES" to "b,k,l,imnst,xyz,more"))
        assertEquals("imnst".toSet(), catalog.flagModes)
        // The extension groups must not leak into any group this app reasons about.
        assertTrue('x' !in catalog.flagModes && 'x' !in catalog.listModes)
        assertTrue('x' !in catalog.paramModes && 'x' !in catalog.setParamModes)
    }

    @Test fun missing_chanmodes_falls_back_to_rfc_defaults() {
        val catalog = ModeCatalog.from(emptyMap())
        assertEquals("b".toSet(), catalog.listModes)
        assertEquals("k".toSet(), catalog.paramModes)
        assertEquals("l".toSet(), catalog.setParamModes)
        assertEquals("imnpst".toSet(), catalog.flagModes)
    }

    @Test fun malformed_chanmodes_falls_back_rather_than_mislabeling_groups() {
        // Fewer than four groups cannot be assigned safely: a partial split would claim `k` takes
        // no argument, which would send a broken MODE line.
        val catalog = ModeCatalog.from(mapOf("CHANMODES" to "b,k"))
        assertEquals("imnpst".toSet(), catalog.flagModes)
        assertEquals("k".toSet(), catalog.paramModes)
    }

    @Test fun excepts_and_invex_default_their_letter_when_advertised_empty() {
        val catalog = ModeCatalog.from(mapOf("EXCEPTS" to "", "INVEX" to ""))
        assertEquals('e', catalog.banExceptionChar)
        assertEquals('I', catalog.inviteExceptionChar)
    }

    @Test fun excepts_and_invex_honor_an_explicit_letter() {
        val catalog = ModeCatalog.from(mapOf("EXCEPTS" to "f", "INVEX" to "g"))
        assertEquals('f', catalog.banExceptionChar)
        assertEquals('g', catalog.inviteExceptionChar)
    }

    @Test fun absent_excepts_and_invex_are_null_not_a_default_letter() {
        // Absent means the row must not be rendered at all, so this may never fall back.
        val catalog = ModeCatalog.from(mapOf("CHANMODES" to "beI,k,l,imnst"))
        assertNull(catalog.banExceptionChar)
        assertNull(catalog.inviteExceptionChar)
    }

    @Test fun maxlist_expands_the_shared_form() {
        assertEquals(
            mapOf('b' to 60, 'e' to 60, 'I' to 60),
            ModeCatalog.from(mapOf("MAXLIST" to "beI:60")).maxList,
        )
    }

    @Test fun maxlist_expands_the_per_letter_form() {
        assertEquals(
            mapOf('b' to 60, 'e' to 30),
            ModeCatalog.from(mapOf("MAXLIST" to "b:60,e:30")).maxList,
        )
    }

    @Test fun maxlist_skips_entries_without_a_numeric_limit() {
        assertEquals(mapOf('b' to 60), ModeCatalog.from(mapOf("MAXLIST" to "b:60,e:,I")).maxList)
    }

    @Test fun prefix_passes_through_in_privilege_order() {
        val catalog = ModeCatalog.from(mapOf("PREFIX" to "(qaohv)~&@%+"))
        assertEquals(
            listOf(
                PrefixRole('q', '~'), PrefixRole('a', '&'), PrefixRole('o', '@'),
                PrefixRole('h', '%'), PrefixRole('v', '+'),
            ),
            catalog.prefixRoles,
        )
    }

    @Test fun missing_or_malformed_prefix_falls_back_to_op_and_voice() {
        assertEquals(listOf(PrefixRole('o', '@'), PrefixRole('v', '+')), ModeCatalog.DEFAULT.prefixRoles)
        assertEquals(
            listOf(PrefixRole('o', '@'), PrefixRole('v', '+')),
            ModeCatalog.from(mapOf("PREFIX" to "ov@+")).prefixRoles,
        )
    }

    @Test fun numeric_limits_are_read_and_nonpositive_values_dropped() {
        val catalog = ModeCatalog.from(mapOf("KICKLEN" to "255", "TOPICLEN" to "390", "MODES" to "4"))
        assertEquals(255, catalog.kickLen)
        assertEquals(390, catalog.topicLen)
        assertEquals(4, catalog.maxModesPerLine)
        val empty = ModeCatalog.from(mapOf("KICKLEN" to "", "TOPICLEN" to "0", "MODES" to "nope"))
        assertNull(empty.kickLen)
        assertNull(empty.topicLen)
        assertNull(empty.maxModesPerLine)
    }

    @Test fun token_keys_are_case_insensitive() {
        val catalog = ModeCatalog.from(mapOf("chanmodes" to "b,k,l,imnst", "excepts" to "e"))
        assertEquals("imnst".toSet(), catalog.flagModes)
        assertEquals('e', catalog.banExceptionChar)
    }

    @Test fun hints_flag_value_modes_and_unadvertised_letters() {
        val catalog = ModeCatalog.from(mapOf("CHANMODES" to "b,k,l,imnst", "PREFIX" to "(ov)@+"))
        assertEquals(
            listOf(ModeHint.NeedsValue('k'), ModeHint.Unknown('z'), ModeHint.NeedsValue('o')),
            catalog.hintsFor("+kzo"),
        )
        // Plain flag modes produce no advisory at all.
        assertEquals(emptyList<ModeHint>(), catalog.hintsFor("-imnst"))
    }

    @Test fun hints_are_deduplicated_per_letter() {
        assertEquals(listOf(ModeHint.NeedsValue('b')), ModeCatalog.DEFAULT.hintsFor("+b-b"))
    }
}
