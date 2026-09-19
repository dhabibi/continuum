package ml.docilealligator.infinityforreddit.videoautoplay

import android.content.Context
import android.net.Uri
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.test.core.app.ApplicationProvider
import java.io.IOException
import ml.docilealligator.infinityforreddit.TestInfinity
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = TestInfinity::class, sdk = [34])
class MediaPreloadBudgetTest {
    @get:Rule val temporary = TemporaryFolder()
    private val uri = Uri.parse("https://example.com/clip.mp4")

    @Test
    fun `audio and video share one byte budget`() {
        val budget = PreloadBudget { 0 }
        val buffer = ByteArray(65_536)
        var total = 0
        for (size in listOf(300_000, 3_000_000)) {
            val source = BudgetDataSource(ByteArrayDataSource(ByteArray(size)), budget)
            source.open(DataSpec(uri))
            try {
                while (true) {
                    val count = source.read(buffer, 0, buffer.size)
                    if (count < 0) break
                    total += count
                }
            } catch (_: IOException) {
                // The second source exhausts the common budget, not a fresh per-request budget.
            } finally {
                source.close()
            }
        }
        assertEquals(PreloadBudget.MAX_BYTES, total)
    }

    @Test
    fun `cancel and deadline prevent new reads and opens`() {
        var now = 0L
        val budget = PreloadBudget { now }
        val source = BudgetDataSource(ByteArrayDataSource(ByteArray(16)), budget)
        source.open(DataSpec(uri))
        now = PreloadBudget.MAX_DURATION_MS
        assertThrows(IOException::class.java) { source.read(ByteArray(1), 0, 1) }
        source.close()

        val canceled = PreloadBudget { 0 }
        canceled.cancel()
        assertThrows(IOException::class.java) {
            BudgetDataSource(ByteArrayDataSource(ByteArray(16)), canceled).open(DataSpec(uri))
        }
    }

    @Test
    fun `canceled preload leaves bytes usable without a foreground network request`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val cache = SimpleCache(temporary.newFolder(), NoOpCacheEvictor(), StandaloneDatabaseProvider(context))
        val data = ByteArray(4 * 1024 * 1024) { (it % 251).toByte() }
        try {
            val source = CacheDataSource.Factory().setCache(cache)
                .setUpstreamDataSourceFactory {
                    BudgetDataSource(ByteArrayDataSource(data), PreloadBudget { 0 })
                }.createDataSource()
            source.open(DataSpec(uri))
            val buffer = ByteArray(65_536)
            try {
                while (source.read(buffer, 0, buffer.size) >= 0) { /* Warm until the budget stops it. */ }
                fail("Expected the byte budget to stop the preload")
            } catch (_: IOException) {
                // Represents scrolling away/canceling with a partially filled playback cache.
            } finally {
                source.close()
            }
            assertTrue(cache.isCached(uri.toString(), 0, 65_536))

            var openedNetwork = false
            val network = object : DataSource by ByteArrayDataSource(data) {
                override fun open(dataSpec: DataSpec): Long {
                    openedNetwork = true
                    throw AssertionError("Foreground playback should use the prefetched bytes")
                }
            }
            val playback = CacheDataSource.Factory().setCache(cache)
                .setUpstreamDataSourceFactory { network }.createDataSource()
            playback.open(DataSpec.Builder().setUri(uri).setLength(65_536).build())
            var read = 0
            while (read < buffer.size) {
                val count = playback.read(buffer, read, buffer.size - read)
                assertTrue(count > 0)
                read += count
            }
            playback.close()
            assertEquals(65_536, read)
            assertArrayEquals(data.copyOfRange(0, 65_536), buffer)
            assertFalse(openedNetwork)
        } finally {
            cache.release()
        }
    }
}
