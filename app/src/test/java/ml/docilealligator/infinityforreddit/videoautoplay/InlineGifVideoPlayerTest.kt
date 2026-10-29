package ml.docilealligator.infinityforreddit.videoautoplay

import android.content.Context
import android.graphics.Matrix
import android.net.Uri
import android.os.Looper
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import androidx.test.core.app.ApplicationProvider
import ml.docilealligator.infinityforreddit.TestInfinity
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = TestInfinity::class, sdk = [34])
class InlineGifVideoPlayerTest {
    private lateinit var parent: FrameLayout
    private lateinit var image: ImageView
    private lateinit var badge: View
    private lateinit var player: ExoPlayer
    private lateinit var creator: ExoCreator
    private val uri = Uri.parse("https://example.com/animation.mp4")

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        parent = FrameLayout(context)
        image = ImageView(context)
        badge = View(context)
        parent.addView(image, FrameLayout.LayoutParams(200, 100))
        parent.addView(badge, FrameLayout.LayoutParams(20, 20))
        parent.measure(View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY))
        parent.layout(0, 0, 200, 100)
        player = mock()
        whenever(player.trackSelectionParameters).thenReturn(TrackSelectionParameters.DEFAULT)
        creator = mock()
        whenever(creator.createPlayer()).thenReturn(player)
        whenever(creator.createMediaSource(uri, null)).thenReturn(mock<MediaSource>())
    }

    @Test
    fun `streamed gallery gif follows image zoom and pan on each draw`() {
        image.scaleType = ImageView.ScaleType.MATRIX
        image.setImageDrawable(android.graphics.drawable.GradientDrawable().apply { setSize(100, 50) })
        image.imageMatrix = Matrix().apply { setScale(4f, 4f); postTranslate(-20f, -10f) }
        val helper = InlineGifVideoPlayer.create(image, uri, creator, {}, {})!!
        val listener = argumentCaptor<Player.Listener>()
        verify(player).addListener(listener.capture())
        listener.firstValue.onVideoSizeChanged(VideoSize(100, 50))
        val texture = parent.getChildAt(0) as TextureView
        val values = FloatArray(9)
        texture.getTransform(Matrix()).getValues(values)
        assertEquals(2f, values[Matrix.MSCALE_X], 0.001f)
        assertEquals(-20f, values[Matrix.MTRANS_X], 0.001f)
        image.imageMatrix = Matrix().apply { setScale(2f, 2f) }
        image.viewTreeObserver.dispatchOnPreDraw()
        texture.getTransform(Matrix()).getValues(values)
        assertEquals(1f, values[Matrix.MSCALE_X], 0.001f)
        assertEquals(0f, values[Matrix.MTRANS_X], 0.001f)
        helper.release()
    }

    @Test
    fun `mp4 stays silent and keeps thumbnail and badges until its first frame`() {
        var ready = 0
        val helper = InlineGifVideoPlayer.create(image, uri, creator, {}, { ready++ })!!
        val texture = parent.getChildAt(0) as TextureView
        assertSame(image, parent.getChildAt(1))
        assertSame(badge, parent.getChildAt(2))
        assertEquals(1f, texture.alpha, 0f)
        assertEquals(1f, image.alpha, 0f)
        assertFalse(texture.isClickable)
        verify(player).volume = 0f
        verify(player).repeatMode = Player.REPEAT_MODE_ONE
        val parameters = argumentCaptor<TrackSelectionParameters>()
        verify(player).trackSelectionParameters = parameters.capture()
        assertTrue(C.TRACK_TYPE_AUDIO in parameters.firstValue.disabledTrackTypes)
        val listener = argumentCaptor<Player.Listener>()
        verify(player).addListener(listener.capture())
        listener.firstValue.onRenderedFirstFrame()
        assertEquals(1f, texture.alpha, 0f)
        assertEquals(0f, image.alpha, 0f)
        assertEquals(1, ready)
        helper.release()
        helper.release()
        assertEquals(2, parent.childCount)
        assertEquals(1f, image.alpha, 0f)
        verify(player, times(1)).release()
    }

    @Test
    fun `unmeasured gallery page retains layout size and thumbnail until its first frame`() {
        val context = image.context
        val unmeasuredParent = FrameLayout(context)
        val unmeasuredImage = ImageView(context).apply { alpha = 0.7f }
        unmeasuredParent.addView(unmeasuredImage,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, 240))
        val helper = InlineGifVideoPlayer.create(unmeasuredImage, uri, creator, {}, {})!!
        val texture = unmeasuredParent.getChildAt(0) as TextureView
        assertEquals(0, unmeasuredImage.width)
        assertEquals(FrameLayout.LayoutParams.MATCH_PARENT, texture.layoutParams.width)
        assertEquals(240, texture.layoutParams.height)
        assertEquals(1f, texture.alpha, 0f)
        assertEquals(0.7f, unmeasuredImage.alpha, 0f)
        assertSame(unmeasuredImage, unmeasuredParent.getChildAt(1))

        // No decoder callback is needed to obtain a usable surface size.
        unmeasuredParent.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(240, View.MeasureSpec.EXACTLY))
        unmeasuredParent.layout(0, 0, 320, 240)
        assertEquals(320, texture.width)
        assertEquals(240, texture.height)
        assertEquals(0.7f, unmeasuredImage.alpha, 0f)
        val listener = argumentCaptor<Player.Listener>()
        verify(player).addListener(listener.capture())
        listener.firstValue.onRenderedFirstFrame()
        assertEquals(0f, unmeasuredImage.alpha, 0f)
        helper.release()
        assertEquals(0.7f, unmeasuredImage.alpha, 0f)
        assertSame(unmeasuredImage, unmeasuredParent.getChildAt(0))
    }

    @Test
    fun `foreground buffering suspends preloading and recovery can resume it`() {
        var ready = 0
        var buffering = 0
        val helper = InlineGifVideoPlayer.create(image, uri, creator, {}, { ready++ }, { buffering++ })!!
        val listener = argumentCaptor<Player.Listener>()
        verify(player).addListener(listener.capture())
        listener.firstValue.onPlaybackStateChanged(Player.STATE_BUFFERING)
        assertEquals(1, buffering)
        listener.firstValue.onRenderedFirstFrame()
        assertEquals(1, ready)
        listener.firstValue.onPlaybackStateChanged(Player.STATE_BUFFERING)
        listener.firstValue.onPlaybackStateChanged(Player.STATE_READY)
        assertEquals(2, buffering)
        assertEquals(2, ready)
        helper.release()
    }

    @Test
    fun `mp4 keeps the gif image alignment and aspect ratio`() {
        image.scaleType = ImageView.ScaleType.FIT_START
        val helper = InlineGifVideoPlayer.create(image, uri, creator, {}, {})!!
        val listener = argumentCaptor<Player.Listener>()
        verify(player).addListener(listener.capture())
        listener.firstValue.onVideoSizeChanged(VideoSize(100, 100))
        val values = FloatArray(9)
        (parent.getChildAt(0) as TextureView).getTransform(Matrix()).getValues(values)
        assertEquals(0.5f, values[Matrix.MSCALE_X], 0.001f)
        assertEquals(1f, values[Matrix.MSCALE_Y], 0.001f)
        assertEquals(0f, values[Matrix.MTRANS_X], 0.001f)
        helper.release()
    }

    @Test
    fun `playback failure signals gif fallback but recycling cancels stale errors`() {
        var failures = 0
        val helper = InlineGifVideoPlayer.create(image, uri, creator, { failures++ }, {})!!
        val listener = argumentCaptor<Player.Listener>()
        verify(player).addListener(listener.capture())
        val error = PlaybackException("test", null, PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS)
        listener.firstValue.onPlayerError(error)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, failures)
        listener.firstValue.onPlayerError(error)
        helper.release()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, failures)
    }

    @Test
    fun `player setup failure leaves the existing gif view usable`() {
        whenever(creator.createPlayer()).thenThrow(IllegalStateException("unavailable"))
        assertNull(InlineGifVideoPlayer.create(image, uri, creator, {}, {}))
        assertEquals(2, parent.childCount)
        assertSame(image, parent.getChildAt(0))
    }

    @Test fun `video aspect and zoom stay correct when poster has different bounds or arrives late`() {
        parent.removeView(image)
        val zoomImage = ml.docilealligator.infinityforreddit.customviews.ZoomableGifImageView(parent.context)
        image = zoomImage
        parent.addView(image, 0, FrameLayout.LayoutParams(200, 200))
        image.setImageDrawable(android.graphics.drawable.GradientDrawable().apply { setSize(100, 100) })
        parent.measure(View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY))
        parent.layout(0, 0, 200, 200)
        val helper = InlineGifVideoPlayer.create(image, uri, creator, {}, {})!!
        val listener = argumentCaptor<Player.Listener>()
        verify(player).addListener(listener.capture())
        listener.firstValue.onVideoSizeChanged(VideoSize(200, 100))
        listener.firstValue.onRenderedFirstFrame()
        val texture = parent.getChildAt(0) as TextureView
        fun bounds(): android.graphics.RectF {
            shadowOf(Looper.getMainLooper()).idle()
            image.viewTreeObserver.dispatchOnPreDraw()
            return android.graphics.RectF(0f, 0f, 200f, 200f).also { texture.getTransform(Matrix()).mapRect(it) }
        }
        assertEquals("Zoom controller must remain interactive", 1f, zoomImage.alpha, 0f)
        assertEquals(100f, bounds().height(), 0.01f)
        assertEquals(50f, bounds().top, 0.01f)
        // A delayed Glide result must not replace the video's coordinate system.
        image.setImageDrawable(android.graphics.drawable.GradientDrawable().apply { setSize(50, 150) })
        assertEquals(100f, bounds().height(), 0.01f)
        zoomImage.zoomTo(2f, false)
        val zoomed = bounds()
        assertEquals(2f, zoomed.width() / zoomed.height(), 0.01f)
        assertEquals(400f, zoomed.width(), 0.01f)
        helper.release()
        assertEquals(1f, image.alpha, 0f)
        assertEquals(50, image.drawable.intrinsicWidth)
        assertEquals(150, image.drawable.intrinsicHeight)
    }
}
