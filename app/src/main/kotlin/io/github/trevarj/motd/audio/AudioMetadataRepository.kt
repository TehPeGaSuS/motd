package io.github.trevarj.motd.audio

data class CachedAudioMetadata(val metadata: AudioMetadata?)

interface AudioMetadataRepository {
    fun cached(url: String): CachedAudioMetadata? = null
    suspend fun metadata(url: String, networkId: Long?): AudioMetadata?
}
