package io.github.trevarj.motd.data.prefs

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/** Independent network-content gates. Both default on for existing and fresh installs. */
@Serializable
data class ContentPreviewConfig(
    val showImages: Boolean = true,
    val showLinkPreviews: Boolean = true,
)

interface ContentPreviewPrefs {
    val config: Flow<ContentPreviewConfig>
    suspend fun setShowImages(show: Boolean)
    suspend fun setShowLinkPreviews(show: Boolean)
}
