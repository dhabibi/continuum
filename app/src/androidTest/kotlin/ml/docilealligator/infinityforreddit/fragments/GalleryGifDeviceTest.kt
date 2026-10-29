package ml.docilealligator.infinityforreddit.fragments

import android.content.Intent
import android.os.SystemClock
import android.view.TextureView
import androidx.lifecycle.Lifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.Cache
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.viewpager.widget.ViewPager
import com.google.gson.Gson
import ml.docilealligator.infinityforreddit.R
import ml.docilealligator.infinityforreddit.activities.ViewRedditGalleryActivity
import ml.docilealligator.infinityforreddit.post.Post
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Public GIF rendition URLs are supplied at run time; use a disposable configured emulator. */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class GalleryGifDeviceTest {
    private fun field(instance: Any, name: String): Any? = instance.javaClass.getDeclaredField(name).run {
        isAccessible = true
        get(instance)
    }

    @Test fun gifFramesRenderNextAnimationPreloadsAndPlayersReleaseOnSwipeAndPause() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val firstUrl = requireNotNull(args.getString("gifMp4Url")) { "Public GIF MP4 fixture required" }
        val nextUrl = requireNotNull(args.getString("gifMp4Url2")) { "Second public GIF MP4 fixture required" }
        val first = Post.Gallery("image/gif", "https://i.redd.it/fixture-original.gif", "", "first.gif", "", "").apply { mp4Variant = firstUrl }
        val next = Post.Gallery("image/gif", "https://i.redd.it/fixture-original2.gif", "", "second.gif", "", "").apply { mp4Variant = nextUrl }
        val gson = Gson()
        val post = gson.fromJson("""{"id":"gif-device-fixture","fullName":"t3_gif-device-fixture","subredditName":"gifs","title":"GIF playback verification","permalink":"https://www.reddit.com/r/gifs/"}""", Post::class.java)
        post.gallery = arrayListOf(first, next)
        post.postType = Post.GALLERY_TYPE
        val intent = Intent(instrumentation.targetContext, ViewRedditGalleryActivity::class.java)
            .putExtra(ViewRedditGalleryActivity.EXTRA_POST_JSON, gson.toJson(post))
        ActivityScenario.launch<ViewRedditGalleryActivity>(intent).use { scenario ->
            var current: Any? = null
            fun await(timeout: Long = 20000, condition: (ViewRedditGalleryActivity) -> Boolean) {
                val end = SystemClock.elapsedRealtime() + timeout
                while (SystemClock.elapsedRealtime() < end) {
                    var ready = false
                    scenario.onActivity { ready = condition(it) }
                    if (ready) return
                    SystemClock.sleep(100)
                }
                scenario.onActivity { activity ->
                    val fragments = activity.supportFragmentManager.fragments.filterIsInstance<ViewRedditGalleryImageOrGifFragment>()
                    for (fragment in fragments) {
                        val inline = field(fragment, "gifPlayer")
                        if (inline == null) println("GIF_DIAG resumed=" + fragment.isResumed + " player=null")
                        else {
                            val texture = field(inline, "texture") as TextureView
                            val player = field(inline, "player") as androidx.media3.exoplayer.ExoPlayer
                            println("GIF_DIAG resumed=" + fragment.isResumed + " frame=" + field(inline, "hasRenderedFrame") + " state=" + player.playbackState + " position=" + player.currentPosition + " buffer=" + player.bufferedPosition + " size=" + player.videoSize.width + "x" + player.videoSize.height + " texture=" + texture.width + "x" + texture.height + " available=" + texture.isAvailable + " shown=" + texture.isShown + " alpha=" + texture.alpha + " decoded=" + player.videoDecoderCounters?.renderedOutputBufferCount)
                        }
                    }
                }
                fail("Gallery condition timed out")
            }
            fun currentPlayer(activity: ViewRedditGalleryActivity): Any? = activity.supportFragmentManager.fragments
                .filterIsInstance<ViewRedditGalleryImageOrGifFragment>().firstOrNull { it.isResumed }
                ?.let { field(it, "gifPlayer") }
            await { activity ->
                current = currentPlayer(activity)
                current != null && field(current!!, "hasRenderedFrame") == true
            }
            val firstPlayer = current!!
            var firstPixels = IntArray(32 * 32)
            scenario.onActivity {
                val bitmap = (field(firstPlayer, "texture") as TextureView).getBitmap(32, 32)!!
                bitmap.getPixels(firstPixels, 0, 32, 0, 0, 32, 32)
                bitmap.recycle()
            }
            assertTrue("Decoded GIF is visible", firstPixels.toSet().size > 1)
            await(10000) {
                val bitmap = (field(firstPlayer, "texture") as TextureView).getBitmap(32, 32) ?: return@await false
                val pixels = IntArray(32 * 32)
                bitmap.getPixels(pixels, 0, 32, 0, 0, 32, 32)
                bitmap.recycle()
                !pixels.contentEquals(firstPixels)
            }
            var warmedBytes = 0L
            await { activity ->
                val preloader = field(activity, "gifPreloader") ?: return@await false
                val cache = field(preloader, "cache") as Cache
                warmedBytes = cache.getCachedBytes(nextUrl, 0, Long.MAX_VALUE)
                warmedBytes > 0
            }
            scenario.onActivity { it.findViewById<ViewPager>(R.id.view_pager_view_reddit_gallery_activity).setCurrentItem(1, false) }
            await { activity ->
                current = currentPlayer(activity)
                current != null && current !== firstPlayer && field(current!!, "hasRenderedFrame") == true
            }
            assertEquals(true, field(firstPlayer, "released"))
            val secondPlayer = current!!
            scenario.moveToState(Lifecycle.State.CREATED)
            assertEquals(true, field(secondPlayer, "released"))
            assertEquals("https://i.redd.it/fixture-original.gif", first.url)
            println("REAL_ANDROID_GIF_OK visible changing frames, next animation cache bytes=$warmedBytes, swipe and pause release")
        }
    }
}
