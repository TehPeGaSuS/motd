package io.github.trevarj.motd.data.prefs

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/** Independent network-content gates. Both display gates default on for existing and fresh installs. */
@Serializable
data class ContentPreviewConfig(
    val showImages: Boolean = true,
    val showLinkPreviews: Boolean = true,
    /**
     * Opt-in: on a network that uses a proxy or an embedded REALITY tunnel, fetch media previews
     * over the device's direct connection instead of withholding them. The per-network tunnel
     * cannot carry arbitrary media hosts, so previews otherwise never load there. Defaults off
     * because a direct fetch reveals the device IP to the media host, outside the tunnel.
     */
    val directMediaOnProxiedNetworks: Boolean = false,
)

interface ContentPreviewPrefs {
    val config: Flow<ContentPreviewConfig>
    suspend fun setShowImages(show: Boolean)
    suspend fun setShowLinkPreviews(show: Boolean)
    suspend fun setDirectMediaOnProxiedNetworks(enabled: Boolean)
}
