package ml.docilealligator.infinityforreddit.fragments

import android.content.Intent
import android.os.SystemClock
import android.view.TextureView
import android.view.View
import android.view.MotionEvent
import android.view.InputDevice
import android.graphics.RectF
import android.graphics.Matrix
import ml.docilealligator.infinityforreddit.customviews.ZoomableGifImageView
import com.bumptech.glide.Glide
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
        val firstPreview = requireNotNull(args.getString("gifPreviewUrl")) { "Public still preview required" }
        val nextPreview = requireNotNull(args.getString("gifPreviewUrl2")) { "Second still preview required" }
        // Reproduce entering a gallery from a feed that already cached its stills.
        for (preview in listOf(firstPreview, nextPreview)) {
            Glide.with(instrumentation.targetContext).downloadOnly().load(preview).submit().get(30, java.util.concurrent.TimeUnit.SECONDS)
        }
        val first = Post.Gallery("image/gif", "https://i.redd.it/fixture-original.gif", "", "first.gif", "", "").apply {
            mp4Variant = firstUrl; feedPreviewUrl = firstPreview
        }
        val next = Post.Gallery("image/gif", "https://i.redd.it/fixture-original2.gif", "", "second.gif", "", "").apply {
            mp4Variant = nextUrl; feedPreviewUrl = nextPreview
        }
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
            println("GALLERY_INSPECTION_READY")
            if (args.getString("holdForInspection") == "true") SystemClock.sleep(20000)
            lateinit var zoomImage: ZoomableGifImageView
            scenario.onActivity { activity ->
                val fragment = activity.supportFragmentManager.fragments.filterIsInstance<ViewRedditGalleryImageOrGifFragment>().first { it.isResumed }
                assertEquals("Only one media layer may remain visible", View.GONE,
                    fragment.requireView().findViewById<View>(R.id.image_view_view_reddit_gallery_image_or_gif_fragment).visibility)
                zoomImage = fragment.requireView().findViewById(R.id.gif_video_preview)
                val texture = field(firstPlayer, "texture") as TextureView
                val player = field(firstPlayer, "player") as androidx.media3.exoplayer.ExoPlayer
                val bounds = RectF(0f, 0f, texture.width.toFloat(), texture.height.toFloat())
                texture.getTransform(Matrix()).mapRect(bounds)
                assertEquals("Video must retain its own aspect ratio", player.videoSize.width.toFloat() * player.videoSize.pixelWidthHeightRatio / player.videoSize.height,
                    bounds.width() / bounds.height(), 0.02f)
                assertEquals("New video must start fitted", 1f, zoomImage.zoom, 0.03f)
            }
            // Exercise the real gesture dispatch path, including the invisible image's zoom controller.
            val location = IntArray(2)
            var centerX = 0f
            var centerY = 0f
            scenario.onActivity { zoomImage.getLocationOnScreen(location); centerX = location[0] + zoomImage.width / 2f; centerY = location[1] + zoomImage.height / 2f }
            var minimumSpan = 0f
            var startingHalfSpan = 0f
            var endingHalfSpan = 0f
            var barsHidden = false
            scenario.onActivity { activity ->
                minimumSpan = android.view.ViewConfiguration.get(activity).scaledMinimumScalingSpan.toFloat()
                startingHalfSpan = maxOf(zoomImage.width * 0.2f, (minimumSpan + 40f) / 2f)
                endingHalfSpan = zoomImage.width * 0.45f
                barsHidden = activity.isActionBarHidden
            }
            assertTrue("Viewport must accommodate an actual pinch", endingHalfSpan > startingHalfSpan * 1.5f)
            println("PINCH_SPAN minimum=$minimumSpan start=${startingHalfSpan * 2} end=${endingHalfSpan * 2}")
            fun pinch(open: Boolean) {
                val down = SystemClock.uptimeMillis()
                val properties = Array(2) { index -> MotionEvent.PointerProperties().apply { id = index; toolType = MotionEvent.TOOL_TYPE_FINGER } }
                val coords = Array(2) { MotionEvent.PointerCoords().apply { y = centerY; pressure = 1f; size = 1f } }
                fun send(action: Int, count: Int, span: Float) {
                    coords[0].x = centerX - span; coords[1].x = centerX + span
                    val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, count, properties, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
                    instrumentation.sendPointerSync(event); event.recycle()
                    SystemClock.sleep(20)
                }
                val start = if (open) startingHalfSpan else endingHalfSpan
                val end = if (open) endingHalfSpan else startingHalfSpan
                send(MotionEvent.ACTION_DOWN, 1, start)
                send(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, start)
                for (step in 1..15) send(MotionEvent.ACTION_MOVE, 2, start + (end - start) * step / 15)
                send(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, end)
                send(MotionEvent.ACTION_UP, 1, end)
            }
            pinch(true)
            scenario.onActivity { activity ->
                assertTrue("Pinch must zoom the animated image; zoom=" + zoomImage.zoom, zoomImage.zoom > 1.5f)
                assertEquals("Pinch must not click the toolbar", barsHidden, activity.isActionBarHidden)
            }
            pinch(false)
            scenario.onActivity { zoomImage.moveToCenter(1f, false) }
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
