package io.github.trevarj.motd.ui.settings

import io.github.trevarj.motd.ui.onboarding.AuthForm
import io.github.trevarj.motd.ui.onboarding.AuthMode
import io.github.trevarj.motd.ui.onboarding.ServerForm
import io.github.trevarj.motd.ui.settings.addnetwork.COMMON_NETWORK_PRESETS
import io.github.trevarj.motd.ui.settings.addnetwork.NetworkGuidanceKind
import io.github.trevarj.motd.ui.settings.addnetwork.NetworkPresetId
import io.github.trevarj.motd.ui.settings.addnetwork.SOJU_NETWORK_PRESET_CHOICES
import io.github.trevarj.motd.ui.settings.addnetwork.applyNetworkPreset
import io.github.trevarj.motd.ui.settings.addnetwork.applySojuNetworkPreset
import io.github.trevarj.motd.ui.settings.addnetwork.networkPreset
import io.github.trevarj.motd.ui.settings.addnetwork.sojuPresetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkPresetsTest {
    @Test
    fun soju_choices_are_custom_then_the_shared_catalog_with_explicit_schemes() {
        assertEquals("Custom", SOJU_NETWORK_PRESET_CHOICES.first().displayName)
        assertEquals(COMMON_NETWORK_PRESETS.map { it.id }, SOJU_NETWORK_PRESET_CHOICES.drop(1).map { it.preset?.id })
        assertEquals("ircs://irc.libera.chat:6697", sojuPresetAddress(COMMON_NETWORK_PRESETS.first()))
        assertEquals(
            "irc+insecure://irc.quakenet.org:6667",
            sojuPresetAddress(COMMON_NETWORK_PRESETS.first { it.id == NetworkPresetId.QUAKENET }),
        )
    }

    @Test
    fun soju_preset_populates_endpoint_and_only_blank_name() {
        val libera = SOJU_NETWORK_PRESET_CHOICES.first { it.preset?.id == NetworkPresetId.LIBERA }
        assertEquals(
            "ircs://irc.libera.chat:6697" to "Libera.Chat",
            applySojuNetworkPreset(libera, "old", ""),
        )
        assertEquals(
            "ircs://irc.libera.chat:6697" to "My Libera",
            applySojuNetworkPreset(libera, "old", "My Libera"),
        )
        assertEquals(
            "irc+insecure://custom:6667" to "Custom name",
            applySojuNetworkPreset(SOJU_NETWORK_PRESET_CHOICES.first(), "irc+insecure://custom:6667", "Custom name"),
        )
    }

    @Test
    fun catalog_values_and_order_are_exact() {
        assertEquals(
            listOf(
                Triple("irc.libera.chat", 6697, true),
                Triple("irc.oftc.net", 6697, true),
                Triple("irc.efnet.org", 6697, true),
                Triple("irc.ircnet.ca", 6697, true),
                Triple("irc.dal.net", 6697, true),
                Triple("irc.rizon.net", 6697, true),
                Triple("irc.snoonet.org", 6697, true),
                Triple("irc.irchighway.net", 6697, true),
                Triple("irc.quakenet.org", 6667, false),
                Triple("irc.undernet.org", 6667, false),
            ),
            COMMON_NETWORK_PRESETS.map { Triple(it.host, it.port, it.tls) },
        )
        assertEquals(8, COMMON_NETWORK_PRESETS.count { !it.legacyUnencrypted })
        assertEquals(2, COMMON_NETWORK_PRESETS.count { it.legacyUnencrypted })
    }

    @Test
    fun guidance_and_official_links_are_exact_for_every_built_in_preset() {
        assertEquals(
            mapOf(
                NetworkPresetId.LIBERA to
                    Triple(
                        NetworkGuidanceKind.NICKSERV_SASL,
                        "https://libera.chat/guides/registration",
                        "https://libera.chat/guides/sasl",
                    ),
                NetworkPresetId.OFTC to
                    Triple(
                        NetworkGuidanceKind.NICKSERV,
                        "https://www.oftc.net/Services/",
                        "https://www.oftc.net/FAQ/Services/",
                    ),
                NetworkPresetId.EFNET to
                    Triple(
                        NetworkGuidanceKind.NO_REGISTRATION,
                        "https://www.efnet.org/",
                        null,
                    ),
                NetworkPresetId.IRCNET to
                    Triple(
                        NetworkGuidanceKind.IRCNET_SASL,
                        "https://sasl.ircnet.com/account/",
                        "https://www.ircnet.com/sasl",
                    ),
                NetworkPresetId.DALNET to
                    Triple(
                        NetworkGuidanceKind.NICKSERV,
                        "https://docs.dal.net/docs/nsemail.html",
                        "https://docs.dal.net/docs/nickserv.html",
                    ),
                NetworkPresetId.RIZON to
                    Triple(
                        NetworkGuidanceKind.NICKSERV_SASL,
                        "https://wiki.rizon.net/index.php?title=Register_your_nickname",
                        "https://wiki.rizon.net/index.php?title=SASL",
                    ),
                NetworkPresetId.SNOONET to
                    Triple(
                        NetworkGuidanceKind.NICKSERV_SASL,
                        "https://snoonet.org/anope#NickServ",
                        "https://snoonet.org/help/",
                    ),
                NetworkPresetId.IRCHIGHWAY to
                    Triple(
                        NetworkGuidanceKind.NICKSERV_PASSWORD_ONLY,
                        "https://irchighway.net/help/nickserv-help",
                        null,
                    ),
                NetworkPresetId.QUAKENET to
                    Triple(
                        NetworkGuidanceKind.QUAKENET_Q,
                        "https://www.quakenet.org/help/q/how-to-register-an-account-with-q",
                        "https://www.quakenet.org/help/q-commands/auth",
                    ),
                NetworkPresetId.UNDERNET to
                    Triple(
                        NetworkGuidanceKind.UNDERNET_CSERVICE,
                        "https://cservice.undernet.org/live/",
                        "https://www.undernet.org/loc/",
                    ),
            ),
            COMMON_NETWORK_PRESETS.associate { it.id to Triple(it.guidance, it.registrationUrl, it.loginUrl) },
        )
        assertTrue(
            COMMON_NETWORK_PRESETS.all { preset ->
                listOfNotNull(preset.registrationUrl, preset.loginUrl).all { it.startsWith("https://") }
            },
        )
        assertNull(networkPreset(NetworkPresetId.CUSTOM))
    }

    @Test
    fun applying_preset_preserves_identity_and_clears_auth() {
        val original =
            ServerForm(
                host = "old.example",
                port = "7000",
                tls = false,
                nick = "trev",
                username = "ident",
                realname = "Trev",
            )
        val preset = COMMON_NETWORK_PRESETS.first { it.id == NetworkPresetId.LIBERA }

        val (server, auth) = applyNetworkPreset(preset, original)

        assertEquals("irc.libera.chat", server.host)
        assertEquals("6697", server.port)
        assertEquals(true, server.tls)
        assertEquals("trev", server.nick)
        assertEquals("ident", server.username)
        assertEquals("Trev", server.realname)
        assertEquals(AuthForm(), auth)
        assertFalse(preset.matches(server.copy(host = "irc.example")))
    }
}
