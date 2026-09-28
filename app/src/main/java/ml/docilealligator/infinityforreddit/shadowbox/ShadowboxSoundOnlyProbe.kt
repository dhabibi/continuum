package ml.docilealligator.infinityforreddit.shadowbox

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.ParserException
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.inspector.MetadataRetriever
import com.google.common.util.concurrent.ListenableFuture
import ml.docilealligator.infinityforreddit.utils.APIUtils
import okhttp3.OkHttpClient
import java.io.IOException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Reads only track metadata offscreen. At most one retriever is alive and each URL has a wall
 * clock, open-count and byte limit. Closing the retriever also releases its media source threads.
 */
@OptIn(UnstableApi::class)
internal class ShadowboxSoundOnlyProbe(
    private val context: Context,
    private val cache: SimpleCache,
    private val client: OkHttpClient,
) {
    enum class Result { AUDIBLE, SILENT, FAILED }

    private val handler = Handler(Looper.getMainLooper())
    private val mainExecutor = Executor { command -> handler.post(command) }
    private var retriever: MetadataRetriever? = null
    private var future: ListenableFuture<*>? = null
    private var timeout: Runnable? = null
    private var serial = 0

    fun cancel() {
        serial++
        timeout?.let(handler::removeCallbacks)
        timeout = null
        future?.cancel(true)
        future = null
        retriever?.close()
        retriever = null
    }

    fun check(urls: List<Uri>, result: (Result) -> Unit) {
        cancel()
        val request = serial
        var sawRetryableFailure = false

        fun next(index: Int) {
            if (request != serial) return
            if (index == urls.size) {
                result(if (sawRetryableFailure) Result.FAILED else Result.SILENT)
                return
            }
            val uri = urls[index]
            val budget = ReadBudget()
            val cacheFactory = CacheDataSource.Factory()
                .setCache(cache)
                .setUpstreamDataSourceFactory(
                    OkHttpDataSource.Factory(client).setUserAgent(APIUtils.USER_AGENT)
                )
            val boundedFactory = DataSource.Factory { BoundedDataSource(cacheFactory.createDataSource(), budget) }
            val item = MediaItem.fromUri(uri)
            val sourceFactory = if (Util.inferContentType(uri) == C.CONTENT_TYPE_HLS) {
                HlsMediaSource.Factory(boundedFactory)
            } else {
                ProgressiveMediaSource.Factory(boundedFactory)
            }
            try {
                val current = MetadataRetriever.Builder(context, item)
                    .setMediaSourceFactory(sourceFactory).build()
                retriever = current
                val pending = current.retrieveTrackGroups()
                future = pending
                val deadline = Runnable {
                    if (request != serial || retriever !== current) return@Runnable
                    sawRetryableFailure = true
                    releaseCurrent()
                    next(index + 1)
                }
                timeout = deadline
                handler.postDelayed(deadline, PROBE_TIMEOUT_MS)
                pending.addListener({
                    if (request != serial || retriever !== current) return@addListener
                    val outcome = try {
                        val groups = pending.get()
                        when {
                            !ShadowboxSoundOnlyTracks.containsTrack(groups, C.TRACK_TYPE_VIDEO) ->
                                Result.SILENT
                            ShadowboxSoundOnlyTracks.containsTrack(groups, C.TRACK_TYPE_AUDIO) ->
                                Result.AUDIBLE
                            else -> Result.SILENT
                        }
                    } catch (error: ExecutionException) {
                        if (isTerminalFailure(error)) Result.SILENT else Result.FAILED
                    } catch (_: Exception) {
                        Result.FAILED
                    }
                    if (outcome == Result.FAILED) sawRetryableFailure = true
                    releaseCurrent()
                    if (outcome == Result.AUDIBLE) result(outcome) else next(index + 1)
                }, mainExecutor)
            } catch (error: Exception) {
                if (!isTerminalFailure(error)) sawRetryableFailure = true
                releaseCurrent()
                next(index + 1)
            }
        }
        next(0)
    }

    private fun releaseCurrent() {
        timeout?.let(handler::removeCallbacks)
        timeout = null
        future?.cancel(true)
        future = null
        retriever?.close()
        retriever = null
    }

    private fun isTerminalFailure(error: Throwable): Boolean {
        var cause: Throwable? = error
        while (cause != null) {
            if (cause is ParserException) return true
            if (cause is HttpDataSource.InvalidResponseCodeException &&
                cause.responseCode in setOf(404, 410)
            ) return true
            cause = cause.cause
        }
        return false
    }

    private class ReadBudget {
        val opens = AtomicInteger()
        val bytes = AtomicLong()
    }

    private class BoundedDataSource(
        private val upstream: DataSource,
        private val budget: ReadBudget,
    ) : DataSource {
        override fun addTransferListener(listener: TransferListener) =
            upstream.addTransferListener(listener)

        override fun open(dataSpec: DataSpec): Long {
            if (budget.opens.incrementAndGet() > MAX_OPENS) throw IOException("Media probe open limit")
            return upstream.open(dataSpec)
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            // HLS can read audio and video playlists on separate loader threads.
            synchronized(budget) {
                val remaining = MAX_BYTES - budget.bytes.get()
                if (remaining <= 0) throw IOException("Media probe byte limit")
                val count = upstream.read(buffer, offset, minOf(length.toLong(), remaining).toInt())
                if (count > 0) budget.bytes.addAndGet(count.toLong())
                return count
            }
        }

        override fun getUri(): Uri? = upstream.uri
        override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders
        override fun close() = upstream.close()
    }

    private companion object {
        const val PROBE_TIMEOUT_MS = 8_000L
        const val MAX_BYTES = 4L * 1024 * 1024
        const val MAX_OPENS = 12
    }
}
