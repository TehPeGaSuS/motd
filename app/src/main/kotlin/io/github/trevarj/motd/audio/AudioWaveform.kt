package io.github.trevarj.motd.audio

import java.util.Base64
import kotlin.math.ceil
import kotlin.math.sqrt

/** Compact, transport-safe voice waveform. Peaks are quantized to five bits. */
data class AudioWaveform(val peaks: List<Int>) {
    init {
        require(peaks.all { it in 0..MAX_PEAK }) { "Waveform peaks must be five-bit values." }
    }

    val normalized: List<Float>
        get() = peaks.map { it.toFloat() / MAX_PEAK }

    fun encode(): String? {
        if (peaks.isEmpty() || peaks.size > MAX_PEAKS) return null
        val packed = ByteArray(HEADER_BYTES + ceil(peaks.size * BITS_PER_PEAK / 8.0).toInt())
        packed[0] = VERSION.toByte()
        packed[1] = peaks.size.toByte()
        var bitOffset = HEADER_BYTES * 8
        peaks.forEach { peak ->
            repeat(BITS_PER_PEAK) { bit ->
                if (peak and (1 shl bit) != 0) {
                    val absolute = bitOffset + bit
                    packed[absolute / 8] = (packed[absolute / 8].toInt() or (1 shl (absolute % 8))).toByte()
                }
            }
            bitOffset += BITS_PER_PEAK
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(packed)
    }

    companion object {
        val EMPTY = AudioWaveform(emptyList())
        const val DISPLAY_PEAKS = 48
        private const val VERSION = 1
        private const val HEADER_BYTES = 2
        private const val BITS_PER_PEAK = 5
        private const val MAX_PEAK = 31
        private const val MAX_PEAKS = 96

        fun decode(value: String?): AudioWaveform? {
            if (value.isNullOrBlank()) return null
            return runCatching {
                val packed = Base64.getUrlDecoder().decode(value)
                require(packed.size >= HEADER_BYTES && packed[0].toInt() == VERSION)
                val count = packed[1].toInt() and 0xff
                require(count in 1..MAX_PEAKS)
                require(packed.size == HEADER_BYTES + ceil(count * BITS_PER_PEAK / 8.0).toInt())
                var bitOffset = HEADER_BYTES * 8
                val peaks = List(count) {
                    var peak = 0
                    repeat(BITS_PER_PEAK) { bit ->
                        val absolute = bitOffset + bit
                        val set = packed[absolute / 8].toInt() and (1 shl (absolute % 8)) != 0
                        if (set) peak = peak or (1 shl bit)
                    }
                    bitOffset += BITS_PER_PEAK
                    peak
                }
                AudioWaveform(peaks)
            }.getOrNull()
        }

        fun fromAmplitudes(samples: List<Int>, peakCount: Int = DISPLAY_PEAKS): AudioWaveform {
            if (samples.isEmpty() || peakCount <= 0) return EMPTY
            val peaks = List(peakCount) { index ->
                val start = index * samples.size / peakCount
                val end = ((index + 1) * samples.size / peakCount).coerceAtLeast(start + 1)
                val amplitude = samples.subList(start.coerceAtMost(samples.lastIndex), end.coerceAtMost(samples.size))
                    .maxOrNull()
                    ?.coerceIn(0, 32_767)
                    ?: 0
                (sqrt(amplitude / 32_767f) * MAX_PEAK).toInt().coerceIn(0, MAX_PEAK)
            }
            return AudioWaveform(peaks)
        }
    }
}

fun audioWaveformFromUrl(url: String): AudioWaveform? =
    fragmentParameters(url)[WAVEFORM_FRAGMENT]?.let(AudioWaveform::decode)

fun appendAudioWaveform(url: String, waveform: AudioWaveform): String {
    val encoded = waveform.encode() ?: return url
    val separator = if ('#' in url) "&" else "#"
    return "$url$separator$WAVEFORM_FRAGMENT=$encoded"
}

private fun fragmentParameters(url: String): Map<String, String> =
    url.substringAfter('#', "")
        .split('&')
        .mapNotNull { part ->
            val key = part.substringBefore('=', "")
            val value = part.substringAfter('=', "")
            if (key.isBlank() || value.isBlank()) null else key to value
        }
        .toMap()

private const val WAVEFORM_FRAGMENT = "motd-wave"
