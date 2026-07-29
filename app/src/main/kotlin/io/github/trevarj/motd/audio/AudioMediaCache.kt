package io.github.trevarj.motd.audio

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(UnstableApi::class)
@Singleton
class AudioMediaCache @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    val cache: SimpleCache by lazy {
        SimpleCache(
            File(context.cacheDir, "audio-cache/media3").also(File::mkdirs),
            LeastRecentlyUsedCacheEvictor(AudioCacheStore.MAX_AUDIO_CACHE_BYTES),
            StandaloneDatabaseProvider(context),
        )
    }

    fun dataSourceFactory(): DataSource.Factory {
        val direct = DefaultDataSource.Factory(context)
        val cached = CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(direct)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        return DataSource.Factory {
            SchemeAwareDataSource(cached.createDataSource(), direct.createDataSource())
        }
    }

    suspend fun copyIfComplete(url: String, output: File): Boolean = withContext(Dispatchers.IO) {
        val key = url
        val length = ContentMetadata.getContentLength(cache.getContentMetadata(key))
        if (length <= 0 || !cache.isCached(key, 0, length)) return@withContext false
        val source = CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(null)
            .createDataSource()
        return@withContext try {
            source.open(DataSpec(url.toUri()))
            output.outputStream().use { sink ->
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    val count = source.read(buffer, 0, buffer.size)
                    if (count < 0) break
                    sink.write(buffer, 0, count)
                }
            }
            true
        } catch (_: Exception) {
            output.delete()
            false
        } finally {
            runCatching { source.close() }
        }
    }

    suspend fun status(url: String): AudioCacheStatus = withContext(Dispatchers.IO) {
        val key = url.substringBefore('#')
        val length = ContentMetadata.getContentLength(cache.getContentMetadata(key))
        val cachedBytes = cache.getCachedSpans(key).sumOf { span -> span.length }
        when {
            length > 0 && cache.isCached(key, 0, length) -> AudioCacheStatus.CACHED
            cachedBytes > 0L -> AudioCacheStatus.PARTIAL
            else -> AudioCacheStatus.NOT_CACHED
        }
    }

    /** Returns byte-accurate cache progress, or null until the response length is known. */
    suspend fun downloadFraction(url: String): Float? = withContext(Dispatchers.IO) {
        val key = url.substringBefore('#')
        val length = ContentMetadata.getContentLength(cache.getContentMetadata(key))
        val cachedBytes = cache.getCachedSpans(key).sumOf { span -> span.length }
        audioDownloadFraction(cachedBytes, length)
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        cache.keys.toList().forEach { key -> runCatching { cache.removeResource(key) } }
    }

    private class SchemeAwareDataSource(
        private val cached: DataSource,
        private val direct: DataSource,
    ) : DataSource {
        private var active: DataSource? = null

        override fun addTransferListener(transferListener: TransferListener) {
            cached.addTransferListener(transferListener)
            direct.addTransferListener(transferListener)
        }

        override fun open(dataSpec: DataSpec): Long {
            val scheme = dataSpec.uri.scheme
            active = if (scheme.equals("http", true) || scheme.equals("https", true)) cached else direct
            return checkNotNull(active).open(dataSpec)
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            checkNotNull(active).read(buffer, offset, length)

        override fun getUri(): Uri? = active?.uri

        override fun getResponseHeaders(): Map<String, List<String>> = active?.responseHeaders.orEmpty()

        override fun close() {
            active?.close()
            active = null
        }
    }
}

internal fun audioDownloadFraction(cachedBytes: Long, totalBytes: Long): Float? =
    totalBytes.takeIf { it > 0L }?.let { total ->
        (cachedBytes.coerceAtLeast(0L).toDouble() / total.toDouble()).coerceIn(0.0, 1.0).toFloat()
    }
