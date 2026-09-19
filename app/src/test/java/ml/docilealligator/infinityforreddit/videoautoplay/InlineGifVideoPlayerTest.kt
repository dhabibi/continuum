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
    fun `mp4 stays silent and keeps thumbnail and badges until its first frame`() {
        var ready = 0
        val helper = InlineGifVideoPlayer.create(image, uri, creator, {}, { ready++ })!!
        val texture = parent.getChildAt(1) as TextureView
        assertSame(image, parent.getChildAt(0))
        assertSame(badge, parent.getChildAt(2))
        assertEquals(0f, texture.alpha, 0f)
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
        assertEquals(1, ready)
        helper.release()
        helper.release()
        assertEquals(2, parent.childCount)
        verify(player, times(1)).release()
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
        (parent.getChildAt(1) as TextureView).getTransform(Matrix()).getValues(values)
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
}
