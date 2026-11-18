package ml.docilealligator.infinityforreddit.shadowbox

import android.app.Activity
import android.app.Application
import androidx.recyclerview.widget.RecyclerView
import androidx.media3.exoplayer.ExoPlayer
import ml.docilealligator.infinityforreddit.R
import ml.docilealligator.infinityforreddit.font.FontFamily
import ml.docilealligator.infinityforreddit.font.FontStyle
import ml.docilealligator.infinityforreddit.databinding.ActivityShadowboxBinding
import ml.docilealligator.infinityforreddit.databinding.ShadowboxMediaVideoBinding
import ml.docilealligator.infinityforreddit.post.Post
import ml.docilealligator.infinityforreddit.viewmodels.ViewPostDetailActivityViewModel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class TikTokPlayerRetentionTest {
    @Test fun `window follows visible pager neighbors across filtered source gaps`() {
        val fixture = hostFixture()
        fixture.binding.viewPager2ShadowboxActivity.setCurrentItem(2, false)
        assertEquals(setOf(3, 6, 9), fixture.retained())
        fixture.binding.viewPager2ShadowboxActivity.setCurrentItem(4, false)
        assertEquals(setOf(9, 12), fixture.retained())
        fixture.binding.viewPager2ShadowboxActivity.setCurrentItem(0, false)
        assertEquals(setOf(0, 3), fixture.retained())
    }

    @Test fun `footer retains only the preceding post and background retains none`() {
        val fixture = hostFixture()
        fixture.binding.viewPager2ShadowboxActivity.setCurrentItem(5, false)
        assertEquals(setOf(12), fixture.retained())
        assertFalse(fixture.host.canRetainVideoPlayer(-1))
        setField(fixture.host, "viewerInForeground", false)
        assertEquals(emptySet<Int>(), fixture.retained())
    }

    @Test fun `release retains seek volume and manual pause while restore outside window stays empty`() {
        val activity = Robolectric.buildActivity(Activity::class.java).get().apply { setTheme(R.style.AppTheme) }
        val fragment = ShadowboxVideoPageFragment()
        val player: ExoPlayer = mock()
        whenever(player.currentPosition).thenReturn(12_345L)
        val host: ShadowboxActivity = mock()
        activity.theme.applyStyle(FontStyle.Normal.resId, true)
        activity.theme.applyStyle(FontFamily.Default.resId, true)
        val binding = ShadowboxMediaVideoBinding.inflate(activity.layoutInflater)
        setField(fragment, "_binding", binding)
        setField(fragment, "host", host)
        setField(fragment, "post", mock<Post>())
        setField(fragment, "player", player)
        setField(fragment, "mediaLoaded", true)
        setField(fragment, "revealed", true)
        setField(fragment, "userPaused", true)
        setField(fragment, "volume", 0.7f)
        fragment.releaseMedia()
        verify(player).stop()
        verify(player).release()
        assertNull(getField(fragment, "player"))
        assertEquals(12_345L, getField(fragment, "resumePositionMs"))
        assertEquals(true, getField(fragment, "userPaused"))
        assertEquals(0.7f, getField(fragment, "volume"))
        fragment.restoreMedia()
        assertNull(getField(fragment, "player"))
        verify(host).canRetainVideoPlayer(-1)
    }

    private data class Fixture(val host: ShadowboxActivity, val binding: ActivityShadowboxBinding) {
        fun retained(): Set<Int> = (0..14).filterTo(mutableSetOf()) { host.canRetainVideoPlayer(it) }
    }

    private fun hostFixture(): Fixture {
        val host = Robolectric.buildActivity(ShadowboxActivity::class.java).get().apply { setTheme(R.style.AppTheme) }
        val binding = ActivityShadowboxBinding.inflate(host.layoutInflater)
        val posts = ArrayList((0..14).map { mock<Post>() })
        val viewModel: ViewPostDetailActivityViewModel = mock()
        whenever(viewModel.posts).thenReturn(posts)
        val adapter = ShadowboxPagerAdapter(host, viewModel, { false }, { posts.indexOf(it) % 3 == 0 })
        adapter.buildPages()
        // Use the real pager's position without starting injected production fragments.
        val views: RecyclerView.Adapter<RecyclerView.ViewHolder> = mock()
        whenever(views.itemCount).thenReturn(adapter.itemCount)
        binding.viewPager2ShadowboxActivity.adapter = views
        setField(host, "binding", binding)
        setField(host, "adapter", adapter)
        setField(host, "viewerInForeground", true)
        return Fixture(host, binding)
    }

    private fun getField(target: Any, name: String): Any? = field(target, name).get(target)
    private fun setField(target: Any, name: String, value: Any) = field(target, name).set(target, value)
    private fun field(target: Any, name: String): java.lang.reflect.Field {
        var type: Class<*>? = target.javaClass
        while (type != null) {
            type.declaredFields.firstOrNull { it.name == name }?.let {
                it.isAccessible = true
                return it
            }
            type = type.superclass
        }
        error("Missing field $name")
    }
}
