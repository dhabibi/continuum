package ml.docilealligator.infinityforreddit.shadowbox

import android.app.Activity
import android.app.Application
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Looper
import android.view.LayoutInflater
import com.bumptech.glide.Priority
import com.bumptech.glide.RequestBuilder
import com.bumptech.glide.RequestManager
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.exoplayer.ExoPlayer
import ml.docilealligator.infinityforreddit.R
import ml.docilealligator.infinityforreddit.font.FontFamily
import ml.docilealligator.infinityforreddit.font.FontStyle
import ml.docilealligator.infinityforreddit.databinding.ItemShadowboxGalleryBinding
import ml.docilealligator.infinityforreddit.post.Post
import ml.docilealligator.infinityforreddit.videoautoplay.ExoCreator
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Answers
import org.mockito.kotlin.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Exercise the production holder, not a parallel playback policy. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ShadowboxGalleryStreamingTest {
    private lateinit var fragment: ShadowboxGalleryPageFragment
    private lateinit var holder: Any
    private lateinit var player: ExoPlayer
    private lateinit var creator: ExoCreator
    private lateinit var glide: RequestManager
    private val item = Post.Gallery("image/gif", "https://media.example/original.gif", "", "gif", "", "").apply {
        mp4Variant = "https://media.example/stream.mp4"
    }

    @Before fun setUp() {
        val activity = Robolectric.buildActivity(Activity::class.java).get()
        activity.setTheme(R.style.AppTheme)
        activity.theme.applyStyle(FontStyle.Normal.resId, true)
        activity.theme.applyStyle(FontFamily.Default.resId, true)
        val binding = ItemShadowboxGalleryBinding.inflate(LayoutInflater.from(activity))
        fragment = ShadowboxGalleryPageFragment()
        player = mock()
        whenever(player.trackSelectionParameters).thenReturn(TrackSelectionParameters.DEFAULT)
        creator = mock()
        whenever(creator.createPlayer()).thenReturn(player)
        whenever(creator.createMediaSource(any(), isNull())).thenReturn(mock())
        glide = mock()
        val request: RequestBuilder<Drawable> = mock(defaultAnswer = Answers.RETURNS_SELF)
        whenever(glide.load(any<String>())).thenReturn(request)
        setField(fragment, "host", mock<ShadowboxActivity>())
        setField(fragment, "glide", glide)
        setField(fragment, "exoCreator", creator)
        setField(fragment, "revealed", true)
        setField(fragment, "autoplayGif", true)
        setField(fragment, "galleryPageActive", true)
        setField(fragment, "mediaForeground", true)
        setField(fragment, "galleryPagePositionInitialized", true)
        setField(fragment, "tileWidth", 200)
        setField(fragment, "tileHeight", 200)
        val holderClass = ShadowboxGalleryPageFragment::class.java.declaredClasses.single { it.simpleName == "GalleryViewHolder" }
        holder = holderClass.declaredConstructors.single().apply { isAccessible = true }.newInstance(fragment, binding)
    }

    @Test fun `selected gif uses mp4 and releases its decoder on pause`() {
        bind()
        verify(creator).createMediaSource(Uri.parse(item.mp4Variant), null)
        verify(glide, never()).load(item.url)
        fragment.releaseMedia()
        verify(player).release()
    }

    @Test fun `redundant binds preserve a selected player until an actual stop`() {
        bind()
        bind()
        bind()
        verify(creator, times(1)).createPlayer()
        verify(player, never()).release()
        fragment.releaseMedia()
        verify(player).release()
        setField(fragment, "mediaForeground", true)
        bind()
        verify(creator, times(2)).createPlayer()
    }

    @Test fun `failed mp4 falls back once to the original gif`() {
        bind()
        val listener = argumentCaptor<Player.Listener>()
        verify(player).addListener(listener.capture())
        listener.firstValue.onPlayerError(PlaybackException("test", null, PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS))
        shadowOf(Looper.getMainLooper()).idle()
        verify(player).release()
        verify(glide).load(item.url)
        verify(creator, times(1)).createPlayer()
    }

    @Test fun `offscreen paused hidden and autoplay-disabled gifs do not start either download`() {
        for (field in listOf("galleryPageActive", "mediaForeground", "revealed", "autoplayGif")) {
            setField(fragment, field, false)
            bind()
            verifyNoInteractions(creator)
            verify(glide, never()).load(item.url)
            setField(fragment, field, true)
        }
        setField(fragment, "galleryScrolling", true)
        bind()
        verifyNoInteractions(creator)
        verify(glide, never()).load(item.url)
    }

    @Test fun `recycled holder releases mp4 and ignores delayed player errors`() {
        bind()
        val listener = argumentCaptor<Player.Listener>()
        verify(player).addListener(listener.capture())
        holder.javaClass.getDeclaredMethod("prepareForRecycling").apply { isAccessible = true }.invoke(holder)
        listener.firstValue.onPlayerError(PlaybackException("stale", null, PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS))
        shadowOf(Looper.getMainLooper()).idle()
        verify(player).release()
        verify(glide, never()).load(item.url)
    }

    private fun bind() = holder.javaClass.getDeclaredMethod("bind", Post.Gallery::class.java, Int::class.javaPrimitiveType, Priority::class.java)
        .apply { isAccessible = true }.invoke(holder, item, 0, Priority.IMMEDIATE)

    private fun setField(target: Any, name: String, value: Any) {
        var type: Class<*>? = target.javaClass
        while (type != null) {
            val field = type.declaredFields.firstOrNull { it.name == name }
            if (field != null) {
                field.isAccessible = true
                field.set(target, value)
                return
            }
            type = type.superclass
        }
        error("Missing field $name")
    }
}
