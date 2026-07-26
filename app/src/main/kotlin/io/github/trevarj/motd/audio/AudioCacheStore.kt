package io.github.trevarj.motd.audio

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Singleton
class AudioCacheStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val root = File(context.cacheDir, "audio-cache").also { it.mkdirs() }

    suspend fun clear() = withContext(Dispatchers.IO) {
        root.listFiles()?.forEach { it.deleteRecursively() }
        root.mkdirs()
    }

    suspend fun trim(maxBytes: Long = MAX_AUDIO_CACHE_BYTES) = withContext(Dispatchers.IO) {
        val files = root.walkTopDown().filter { it.isFile }.toList()
        var total = files.sumOf { it.length() }
        if (total <= maxBytes) return@withContext
        for (file in files.sortedBy { it.lastModified() }) {
            val size = file.length()
            if (file.delete()) total -= size
            if (total <= maxBytes) break
        }
    }

    fun tempFile(prefix: String, extension: String): File {
        root.mkdirs()
        return File.createTempFile(prefix, extension, root)
    }

    companion object {
        const val MAX_AUDIO_CACHE_BYTES = 128L * 1024L * 1024L
    }
}
