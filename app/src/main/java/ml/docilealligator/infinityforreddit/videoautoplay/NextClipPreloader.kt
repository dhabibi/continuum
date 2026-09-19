package ml.docilealligator.infinityforreddit.videoautoplay

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.source.preload.DefaultPreloadManager
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import java.io.IOException

/** Warms one clip's opening second into the same cache used for real playback. */
@UnstableApi
class NextClipPreloader(
    private val context: Context,
    private val cache: Cache,
    private val upstreamFactory: DataSource.Factory,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var manager: DefaultPreloadManager? = null
    private var budget: PreloadBudget? = null
    private var current: Uri? = null
    private val stopAtDeadline = Runnable { stop() }

    fun preload(uri: Uri, resolution: Int, portrait: Boolean) {
        if (uri == current) return
        stop()
        current = uri
        val requestBudget = PreloadBudget()
        budget = requestBudget
        val boundedUpstream = DataSource.Factory {
            BudgetDataSource(upstreamFactory.createDataSource(), requestBudget)
        }
        val cachedSource = CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(boundedUpstream)
            // Without FLAG_BLOCK_ON_CACHE, a foreground writer never holds this work up.
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        val preloadManager = DefaultPreloadManager.Builder(context) {
            DefaultPreloadManager.PreloadStatus.specifiedRangeLoaded(1_000L)
        }.setDataSourceFactory(cachedSource)
            .setBandwidthMeter(DefaultBandwidthMeter.getSingletonInstance(context))
            .setTrackSelectorFactory { playerContext ->
                DefaultTrackSelector(playerContext).apply {
                    if (resolution > 0) {
                        parameters = buildUponParameters()
                            .setMaxVideoSize(if (portrait) resolution else Int.MAX_VALUE,
                                if (portrait) Int.MAX_VALUE else resolution)
                            .setForceHighestSupportedBitrate(true)
                            .build()
                    }
                }
            }.build()
        manager = preloadManager
        preloadManager.add(MediaItem.fromUri(uri), 0)
        preloadManager.invalidate()
        handler.postDelayed(stopAtDeadline, PreloadBudget.MAX_DURATION_MS)
    }

    /** Pending work ends here; complete cache spans remain available to playback. */
    fun stop() {
        handler.removeCallbacks(stopAtDeadline)
        budget?.cancel()
        budget = null
        manager?.release()
        manager = null
        current = null
    }
}

/** One shared network budget across a clip's manifest, audio and video requests. */
@UnstableApi
internal class PreloadBudget(private val now: () -> Long = SystemClock::elapsedRealtime) {
    private val deadline = now() + MAX_DURATION_MS
    private var remaining = MAX_BYTES
    @Volatile private var canceled = false

    fun cancel() { canceled = true }

    @Synchronized
    fun check() {
        if (canceled || now() >= deadline || remaining <= 0) {
            throw IOException("Media preload budget exhausted")
        }
    }

    @Synchronized
    fun read(source: DataSource, buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        check()
        val count = source.read(buffer, offset, minOf(length, remaining))
        if (count > 0) remaining -= count
        return count
    }

    companion object {
        const val MAX_BYTES = 2 * 1024 * 1024
        const val MAX_DURATION_MS = 8_000L
    }
}

@UnstableApi
internal class BudgetDataSource(
    private val upstream: DataSource, private val budget: PreloadBudget,
) : DataSource by upstream {
    override fun open(dataSpec: DataSpec): Long {
        budget.check()
        return upstream.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        budget.read(upstream, buffer, offset, length)
}
