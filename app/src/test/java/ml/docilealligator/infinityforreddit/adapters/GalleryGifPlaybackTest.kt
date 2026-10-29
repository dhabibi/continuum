package ml.docilealligator.infinityforreddit.adapters

import android.content.Context
import android.net.Uri
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.View
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.exoplayer.ExoPlayer
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import com.bumptech.glide.Glide
import ml.docilealligator.infinityforreddit.font.*
import ml.docilealligator.infinityforreddit.R
import ml.docilealligator.infinityforreddit.SaveMemoryCenterInisdeDownsampleStrategy
import ml.docilealligator.infinityforreddit.TestInfinity
import ml.docilealligator.infinityforreddit.post.Post
import ml.docilealligator.infinityforreddit.videoautoplay.ExoCreator
import ml.docilealligator.infinityforreddit.videoautoplay.NextClipPreloader
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
class GalleryGifPlaybackTest {
    private lateinit var list: RecyclerView
    private lateinit var adapter: PostGalleryTypeImageRecyclerViewAdapter
    private lateinit var creator: ExoCreator
    private lateinit var player: ExoPlayer
    private lateinit var preloader: NextClipPreloader
    private lateinit var first: Post.Gallery
    private lateinit var next: Post.Gallery

    @Before fun setup() {
        val context = ContextThemeWrapper(ApplicationProvider.getApplicationContext<Context>(), R.style.AppTheme)
        for (style in listOf(R.style.Theme_Normal, FontStyle.Normal.resId,
            TitleFontStyle.Normal.resId, ContentFontStyle.Normal.resId, FontFamily.Default.resId,
            TitleFontFamily.Default.resId, ContentFontFamily.Default.resId)) context.theme.applyStyle(style, true)
        creator = mock()
        player = mock()
        preloader = mock()
        whenever(player.trackSelectionParameters).thenReturn(TrackSelectionParameters.DEFAULT)
        whenever(creator.createPlayer()).thenReturn(player)
        whenever(creator.createMediaSource(any(), isNull())).thenReturn(mock())
        whenever(creator.createPreloader()).thenReturn(preloader)
        val still = "android.resource://${context.packageName}/${R.drawable.ic_gallery_day_night_24dp}"
        fun item(id: String) = Post.Gallery("image/gif", "$still#$id", "", "$id.gif", "", "").apply {
            feedPreviewUrl = still
            mp4Variant = "https://preview.redd.it/$id.gif?format=mp4&s=signed"
        }
        first = item("first")
        next = item("next")
        adapter = PostGalleryTypeImageRecyclerViewAdapter(Glide.with(context), null,
            SaveMemoryCenterInisdeDownsampleStrategy(1_000_000), 0, 0)
        adapter.setGifPlayback(creator, true)
        adapter.setAutoplayGif(true)
        adapter.setRatio(1f)
        adapter.setGalleryImages(arrayListOf(first, next))
        list = RecyclerView(context)
        list.layoutManager = LinearLayoutManager(context, RecyclerView.HORIZONTAL, false)
        list.adapter = adapter
        list.measure(View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY))
        list.layout(0, 0, 300, 300)
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun `selected gallery uses MP4 and warms only next GIF after first frame`() {
        adapter.setPlaying(true)
        verify(creator).createMediaSource(Uri.parse(first.mp4Variant), null)
        verifyNoInteractions(preloader)
        val listener = argumentCaptor<Player.Listener>()
        verify(player).addListener(listener.capture())
        listener.firstValue.onRenderedFirstFrame()
        verify(preloader).preload(Uri.parse(next.mp4Variant), 0, false)
        assertTrue(first.url.endsWith("#first"))
        adapter.setPlaying(false)
        verify(player).release()
        verify(preloader).stop()
        assertTrue((list.findViewHolderForAdapterPosition(0) as PostGalleryTypeImageRecyclerViewAdapter.ImageViewHolder).gifPlayer == null)
    }

    @Test fun `autoplay disabled blurred and grid galleries never create players`() {
        adapter.setAutoplayGif(false)
        adapter.setPlaying(true)
        verify(creator, never()).createPlayer()
        adapter.setPlaying(false)
        adapter.setAutoplayGif(true)
        adapter.setBlurImage(true)
        adapter.setPlaying(true)
        verify(creator, never()).createPlayer()
        adapter.setPlaying(false)
        adapter.setBlurImage(false)
        adapter.setIsGridLayout(true)
        adapter.setPlaying(true)
        verify(creator, never()).createPlayer()
    }

    @Test fun `MP4 failure falls back once and still image pages never create players`() {
        adapter.setPlaying(true)
        val listener = argumentCaptor<Player.Listener>()
        verify(player).addListener(listener.capture())
        listener.firstValue.onPlayerError(androidx.media3.common.PlaybackException("failed", null,
            androidx.media3.common.PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS))
        shadowOf(Looper.getMainLooper()).idle()
        verify(player).release()
        verify(creator, times(1)).createPlayer()
        val holder = list.findViewHolderForAdapterPosition(0) as PostGalleryTypeImageRecyclerViewAdapter.ImageViewHolder
        assertNull(holder.gifPlayer)
        assertEquals(first.mp4Variant, holder.failedMp4)
        adapter.setPlaying(false)
        adapter.setGalleryImages(arrayListOf(Post.Gallery("image/png", first.url, "", "still.png", "", "")))
        adapter.setPlaying(true)
        verify(creator, times(1)).createPlayer()
    }

    @Test fun `data saving preload gate leaves explicit current playback available`() {
        adapter.setGifPlayback(creator, false)
        adapter.setPlaying(true)
        val listener = argumentCaptor<Player.Listener>()
        verify(player).addListener(listener.capture())
        listener.firstValue.onRenderedFirstFrame()
        verify(creator, never()).createPreloader()
        adapter.setPlaying(false)
    }
}
