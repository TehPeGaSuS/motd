package io.github.trevarj.motd.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelDeviconBadgeTest {
    @Test fun channel_names_match_the_intended_marks() {
        assertEquals("guix", matchedChannelDevicon("#guix")?.markName)
        assertEquals("arch_linux", matchedChannelDevicon("#archlinux")?.markName)
        assertEquals("debian", matchedChannelDevicon("#debian-devel")?.markName)
        assertEquals("emacs", matchedChannelDevicon("#doomEmacs")?.markName)
        assertEquals("neovim", matchedChannelDevicon("#neovim")?.markName)
        assertEquals("rust", matchedChannelDevicon("#rust-lang")?.markName)
        assertEquals("kubernetes", matchedChannelDevicon("#k8s")?.markName)
        // Catalog-only coverage from the devicon bump.
        assertEquals("nixos", matchedChannelDevicon("#nixos")?.markName)
        assertEquals("postgresql", matchedChannelDevicon("#postgres")?.markName)
        assertEquals("redis", matchedChannelDevicon("#redis")?.markName)
    }

    @Test fun matching_is_conservative_for_short_or_unrelated_tokens() {
        assertEquals("go", matchedChannelDevicon("#go")?.markName)
        assertNull(matchedChannelDevicon("#mango"))
        assertNull(matchedChannelDevicon("#general-chat"))
    }

    @Test fun the_packaged_catalog_resource_loads_every_mark() {
        // Guards the compact index read: a missing/truncated resource must fail, never degrade.
        assertEquals(308, ChannelDeviconCatalog.marks.size)
        assertEquals(308 + ChannelDevicon.entries.size, allChannelMarks.size)
        val index = checkNotNull(javaClass.getResourceAsStream("/channel-devicons-index.json"))
        assertTrue(index.use { it.readBytes().size } <= 64 * 1024)
    }

    @Test fun catalog_path_payload_stays_lazy_until_vector_access() {
        var loads = 0
        val mark =
            CatalogChannelMark("lazy", setOf("lazy"), 10f, 10f) {
                loads++
                listOf(false to "M0 0h10v10z")
            }

        assertEquals("lazy", mark.markName)
        assertEquals(0, loads)
        assertTrue(mark.hasParseablePathData())
        assertEquals(1, loads)
    }

    @Test fun channel_match_cache_is_bounded() {
        repeat(600) { matchedChannelDevicon("#unmatched-$it") }
        assertTrue(channelDeviconMatchCacheSize() <= 512)
    }

    @Test fun every_bundled_channel_mark_has_parseable_vector_source() {
        allChannelMarks.forEach { mark ->
            assertTrue("${mark.markName} should contain parseable path data", mark.hasParseablePathData())
        }
    }

    @Test fun catalog_marks_are_unique_and_carry_reachable_aliases() {
        val names = allChannelMarks.map { it.markName }
        assertEquals(names.size, names.distinct().size)
        allChannelMarks.forEach { mark ->
            mark.aliases.forEach { alias ->
                assertTrue("$alias should be tokenizer-reachable", alias.matches(Regex("[a-z0-9]+")))
            }
        }
    }
}
