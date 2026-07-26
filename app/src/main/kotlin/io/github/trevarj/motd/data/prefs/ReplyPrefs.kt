package io.github.trevarj.motd.data.prefs

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/** App-owned reply delivery preference kept outside the frozen settings contract. */
@Serializable
data class ReplyConfig(val visibleChannelPrefix: Boolean = false)

interface ReplyPrefs {
    val config: Flow<ReplyConfig>
    suspend fun setVisibleChannelPrefix(enabled: Boolean)
}
