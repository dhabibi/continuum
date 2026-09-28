package ml.docilealligator.infinityforreddit.videoautoplay

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.test.core.app.ApplicationProvider
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class AutoplayLifecycleTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `next clip preload can start and stop when autoplay becomes ready`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val cache = SimpleCache(temporary.newFolder(), NoOpCacheEvictor(), StandaloneDatabaseProvider(context))
        val preloader = NextClipPreloader(context, cache) { ByteArrayDataSource(ByteArray(0)) }
        try {
            preloader.preload(Uri.parse("https://example.com/first.mp4"), 720, false)
            preloader.stop()
            preloader.preload(Uri.parse("https://example.com/second.mpd"), 720, true)
        } finally {
            preloader.stop()
            cache.release()
        }
    }
}
