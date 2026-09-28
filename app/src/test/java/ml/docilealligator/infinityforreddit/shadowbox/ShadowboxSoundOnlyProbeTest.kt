package ml.docilealligator.infinityforreddit.shadowbox

import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.Looper
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.test.core.app.ApplicationProvider
import com.sun.net.httpserver.HttpServer
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.net.InetSocketAddress
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ShadowboxSoundOnlyProbeTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `real metadata retrieval admits audio and excludes absent media`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/audible.m3u8") { exchange ->
            val body = """
                #EXTM3U
                #EXT-X-VERSION:3
                #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",NAME="English",DEFAULT=YES,AUTOSELECT=YES,URI="audio.m3u8"
                #EXT-X-STREAM-INF:BANDWIDTH=100000,CODECS="avc1.42e01e,mp4a.40.2",AUDIO="audio"
                video.m3u8
            """.trimIndent().toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/vnd.apple.mpegurl")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.createContext("/video.m3u8") { exchange ->
            val body = mediaPlaylist("video.ts").toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.createContext("/silent.m3u8") { exchange ->
            val body = """
                #EXTM3U
                #EXT-X-VERSION:3
                #EXT-X-STREAM-INF:BANDWIDTH=100000,CODECS="avc1.42e01e"
                video.m3u8
            """.trimIndent().toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.createContext("/audio.m3u8") { exchange ->
            val body = mediaPlaylist("audio.ts").toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.createContext("/missing.m3u8") { exchange -> exchange.sendResponseHeaders(404, -1) }
        server.createContext("/temporary.m3u8") { exchange -> exchange.sendResponseHeaders(503, -1) }
        server.start()
        val cache = SimpleCache(temporary.newFolder(), NoOpCacheEvictor(), StandaloneDatabaseProvider(context))
        val probe = ShadowboxSoundOnlyProbe(context, cache, OkHttpClient())
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            assertEquals(
                ShadowboxSoundOnlyProbe.Result.AUDIBLE,
                resultFor(probe, Uri.parse("$base/audible.m3u8"))
            )
            assertEquals(
                ShadowboxSoundOnlyProbe.Result.SILENT,
                resultFor(probe, Uri.parse("$base/silent.m3u8"))
            )
            assertEquals(
                ShadowboxSoundOnlyProbe.Result.SILENT,
                resultFor(probe, Uri.parse("$base/missing.m3u8"))
            )
            assertEquals(
                ShadowboxSoundOnlyProbe.Result.FAILED,
                resultFor(probe, Uri.parse("$base/temporary.m3u8"))
            )
        } finally {
            probe.cancel()
            cache.release()
            server.stop(0)
        }
    }

    private fun resultFor(probe: ShadowboxSoundOnlyProbe, uri: Uri): ShadowboxSoundOnlyProbe.Result? {
        var result: ShadowboxSoundOnlyProbe.Result? = null
        probe.check(listOf(uri)) { result = it }
        val deadline = System.currentTimeMillis() + 5_000
        while (result == null && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10))
            Thread.sleep(10)
        }
        return result
    }

    private fun mediaPlaylist(segment: String) = """
        #EXTM3U
        #EXT-X-VERSION:3
        #EXT-X-TARGETDURATION:1
        #EXT-X-MEDIA-SEQUENCE:0
        #EXTINF:1,
        $segment
        #EXT-X-ENDLIST
    """.trimIndent()
}
