package io.github.trevarj.motd.ui.settings.addnetwork

import io.github.trevarj.motd.ui.onboarding.AuthForm
import io.github.trevarj.motd.ui.onboarding.ServerForm

enum class NetworkPresetId {
    CUSTOM,
    LIBERA,
    OFTC,
    EFNET,
    IRCNET,
    DALNET,
    RIZON,
    SNOONET,
    IRCHIGHWAY,
    QUAKENET,
    UNDERNET,
}

enum class NetworkGuidanceKind {
    NICKSERV_SASL,
    NICKSERV,
    NO_REGISTRATION,
    IRCNET_SASL,
    NICKSERV_PASSWORD_ONLY,
    QUAKENET_Q,
    UNDERNET_CSERVICE,
}

data class NetworkPreset(
    val id: NetworkPresetId,
    val displayName: String,
    val host: String,
    val port: Int,
    val tls: Boolean,
    val guidance: NetworkGuidanceKind,
    val registrationUrl: String,
    val loginUrl: String? = null,
    val legacyUnencrypted: Boolean = false,
) {
    fun matches(server: ServerForm): Boolean = server.host.equals(host, ignoreCase = true) && server.port == port.toString() && server.tls == tls
}

/** Compile-time convenience defaults. Secure entries are deliberately ordered before legacy IRC. */
val COMMON_NETWORK_PRESETS: List<NetworkPreset> =
    listOf(
        NetworkPreset(
            NetworkPresetId.LIBERA,
            "Libera.Chat",
            "irc.libera.chat",
            6697,
            tls = true,
            guidance = NetworkGuidanceKind.NICKSERV_SASL,
            registrationUrl = "https://libera.chat/guides/registration",
            loginUrl = "https://libera.chat/guides/sasl",
        ),
        NetworkPreset(
            NetworkPresetId.OFTC,
            "OFTC",
            "irc.oftc.net",
            6697,
            tls = true,
            guidance = NetworkGuidanceKind.NICKSERV,
            registrationUrl = "https://www.oftc.net/Services/",
            loginUrl = "https://www.oftc.net/FAQ/Services/",
        ),
        NetworkPreset(
            NetworkPresetId.EFNET,
            "EFnet",
            "irc.efnet.org",
            6697,
            tls = true,
            guidance = NetworkGuidanceKind.NO_REGISTRATION,
            registrationUrl = "https://www.efnet.org/",
        ),
        NetworkPreset(
            NetworkPresetId.IRCNET,
            "IRCnet",
            "irc.ircnet.ca",
            6697,
            tls = true,
            guidance = NetworkGuidanceKind.IRCNET_SASL,
            registrationUrl = "https://sasl.ircnet.com/account/",
            loginUrl = "https://www.ircnet.com/sasl",
        ),
        NetworkPreset(
            NetworkPresetId.DALNET,
            "DALnet",
            "irc.dal.net",
            6697,
            tls = true,
            guidance = NetworkGuidanceKind.NICKSERV,
            registrationUrl = "https://docs.dal.net/docs/nsemail.html",
            loginUrl = "https://docs.dal.net/docs/nickserv.html",
        ),
        NetworkPreset(
            NetworkPresetId.RIZON,
            "Rizon",
            "irc.rizon.net",
            6697,
            tls = true,
            guidance = NetworkGuidanceKind.NICKSERV_SASL,
            registrationUrl = "https://wiki.rizon.net/index.php?title=Register_your_nickname",
            loginUrl = "https://wiki.rizon.net/index.php?title=SASL",
        ),
        NetworkPreset(
            NetworkPresetId.SNOONET,
            "Snoonet",
            "irc.snoonet.org",
            6697,
            tls = true,
            guidance = NetworkGuidanceKind.NICKSERV_SASL,
            registrationUrl = "https://snoonet.org/anope#NickServ",
            loginUrl = "https://snoonet.org/help/",
        ),
        NetworkPreset(
            NetworkPresetId.IRCHIGHWAY,
            "IRCHighWay",
            "irc.irchighway.net",
            6697,
            tls = true,
            guidance = NetworkGuidanceKind.NICKSERV_PASSWORD_ONLY,
            registrationUrl = "https://irchighway.net/help/nickserv-help",
        ),
        NetworkPreset(
            NetworkPresetId.QUAKENET,
            "QuakeNet",
            "irc.quakenet.org",
            6667,
            tls = false,
            guidance = NetworkGuidanceKind.QUAKENET_Q,
            registrationUrl = "https://www.quakenet.org/help/q/how-to-register-an-account-with-q",
            loginUrl = "https://www.quakenet.org/help/q-commands/auth",
            legacyUnencrypted = true,
        ),
        NetworkPreset(
            NetworkPresetId.UNDERNET,
            "Undernet",
            "irc.undernet.org",
            6667,
            tls = false,
            guidance = NetworkGuidanceKind.UNDERNET_CSERVICE,
            registrationUrl = "https://cservice.undernet.org/live/",
            loginUrl = "https://www.undernet.org/loc/",
            legacyUnencrypted = true,
        ),
    )

fun networkPreset(id: NetworkPresetId): NetworkPreset? = COMMON_NETWORK_PRESETS.firstOrNull { it.id == id }

/** Apply only endpoint defaults, preserve IRC identity, and drop credentials from the old server. */
fun applyNetworkPreset(
    preset: NetworkPreset,
    server: ServerForm,
): Pair<ServerForm, AuthForm> = server.copy(host = preset.host, port = preset.port.toString(), tls = preset.tls) to AuthForm()

/** Create-only Soju BouncerServ choices: Custom followed by the shared catalog order. */
data class SojuNetworkPresetChoice(
    val preset: NetworkPreset?,
) {
    val displayName: String get() = preset?.displayName ?: "Custom"
    val address: String? get() = preset?.let(::sojuPresetAddress)
}

val SOJU_NETWORK_PRESET_CHOICES: List<SojuNetworkPresetChoice> =
    listOf(SojuNetworkPresetChoice(null)) + COMMON_NETWORK_PRESETS.map(::SojuNetworkPresetChoice)

/** Soju accepts explicit URI schemes; plaintext presets must never silently become TLS. */
fun sojuPresetAddress(preset: NetworkPreset): String = "${if (preset.tls) "ircs" else "irc+insecure"}://${preset.host}:${preset.port}"

/** Apply only a selected preset endpoint and a blank name default; every other form field survives. */
fun applySojuNetworkPreset(
    choice: SojuNetworkPresetChoice,
    address: String,
    name: String,
): Pair<String, String> =
    choice.preset?.let { preset ->
        sojuPresetAddress(preset) to name.ifBlank { preset.displayName }
    } ?: (address to name)
