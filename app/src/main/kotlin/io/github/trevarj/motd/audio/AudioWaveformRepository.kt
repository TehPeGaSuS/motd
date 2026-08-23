package io.github.trevarj.motd.audio

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AudioWaveformRepository
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) {
        private val root = File(context.cacheDir, "audio-cache/waveforms").also(File::mkdirs)
        private val _waveforms = MutableStateFlow<Map<String, AudioWaveform>>(emptyMap())
        val waveforms: StateFlow<Map<String, AudioWaveform>> = _waveforms.asStateFlow()

        suspend fun load(playbackId: String): AudioWaveform? =
            withContext(Dispatchers.IO) {
                _waveforms.value[playbackId] ?: waveformFile(playbackId)
                    .takeIf(File::isFile)
                    ?.let { AudioWaveform.decode(runCatching(it::readText).getOrNull()) }
                    ?.also { waveform -> _waveforms.value = _waveforms.value + (playbackId to waveform) }
            }

        suspend fun put(
            playbackId: String,
            waveform: AudioWaveform,
        ) = withContext(Dispatchers.IO) {
            val encoded = waveform.encode() ?: return@withContext
            waveformFile(playbackId).writeText(encoded)
            _waveforms.value = _waveforms.value + (playbackId to waveform)
        }

        suspend fun clear() =
            withContext(Dispatchers.IO) {
                root.listFiles()?.forEach(File::delete)
                root.mkdirs()
                _waveforms.value = emptyMap()
            }

        private fun waveformFile(playbackId: String): File = File(root, "${playbackId.sha256()}.wave")

        private fun String.sha256(): String =
            MessageDigest
                .getInstance("SHA-256")
                .digest(toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }
