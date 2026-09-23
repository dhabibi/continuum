package ml.docilealligator.infinityforreddit.shadowbox

import android.app.Application
import android.os.Bundle
import android.os.Looper
import android.os.Parcel
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.widget.ViewPager2
import ml.docilealligator.infinityforreddit.RedditDataRoomDatabase
import ml.docilealligator.infinityforreddit.post.Post
import ml.docilealligator.infinityforreddit.user.UserProfileImagesBatchLoader
import ml.docilealligator.infinityforreddit.viewmodels.ViewPostDetailActivityViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.spy
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import retrofit2.Retrofit

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class TikTokPagerLifecycleTest {
    @Test fun `fragment saved state stays bounded while scrolling and appending pages`() {
        val controller = Robolectric.buildActivity(FragmentActivity::class.java).setup()
        val activity = controller.get()
        val pager = ViewPager2(activity).apply {
            orientation = ViewPager2.ORIENTATION_VERTICAL
            offscreenPageLimit = 1
        }
        val posts = arrayListOf<Post>()
        val viewModel = ViewPostDetailActivityViewModel(
            mock(Retrofit::class.java), mock(Retrofit::class.java), mock(RedditDataRoomDatabase::class.java),
            null, mock(UserProfileImagesBatchLoader::class.java)
        ).also { it.posts = posts }
        val adapter = spy(ShadowboxPagerAdapter(activity, viewModel, { false }, { true }))
        doAnswer { invocation ->
            PagerStateProbeFragment.newInstance(invocation.getArgument(0))
        }.`when`(adapter).createFragment(anyInt())
        val samples = mutableListOf<Pair<Int, SavedStateMetrics>>()

        repeat(3) { batch ->
            repeat(PAGES_PER_BATCH) { posts.add(mock(Post::class.java)) }
            if (batch == 0) adapter.buildPages() else assertEquals(PAGES_PER_BATCH, adapter.appendPages())
            if (batch == 0) {
                activity.setContentView(pager)
                pager.adapter = adapter
                settle(pager)
            }

            val firstPage = batch * PAGES_PER_BATCH
            val endPage = firstPage + PAGES_PER_BATCH - 1
            for (page in firstPage..endPage) {
                pager.setCurrentItem(page, false)
                settle(pager)
                if (page == firstPage || page == firstPage + PAGES_PER_BATCH / 2 || page == endPage) {
                    samples.add(page to savedStateMetrics(adapter))
                }
            }
        }

        for (page in (TOTAL_PAGES - 2) downTo 0) {
            pager.setCurrentItem(page, false)
            settle(pager)
            if (page == TOTAL_PAGES - 2 || page == TOTAL_PAGES / 2 || page == 0) {
                samples.add(page to savedStateMetrics(adapter))
            }
        }

        val measurements = samples.joinToString { (page, state) ->
            "page=$page saved=${state.savedFragmentStates} parcel=${state.parcelBytes}B"
        }
        assertTrue(
            "FragmentStateAdapter retained too many detached page states: $measurements",
            samples.all { (_, state) -> state.savedFragmentStates <= MAX_SAVED_FRAGMENT_STATES }
        )
        controller.pause().stop().destroy()
    }

    private fun settle(pager: ViewPager2) {
        val activity = pager.context as FragmentActivity
        val width = 360
        val height = 640
        activity.window.decorView.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        activity.window.decorView.layout(0, 0, width, height)
        shadowOf(Looper.getMainLooper()).idle()
        activity.supportFragmentManager.executePendingTransactions()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun savedStateMetrics(adapter: ShadowboxPagerAdapter): SavedStateMetrics {
        val savedState = adapter.saveState() as Bundle
        val fragmentStates = savedState.keySet().count { it.startsWith("s#") }
        val parcel = Parcel.obtain()
        return try {
            parcel.writeParcelable(savedState, 0)
            SavedStateMetrics(fragmentStates, parcel.dataSize())
        } finally {
            parcel.recycle()
        }
    }

    private data class SavedStateMetrics(val savedFragmentStates: Int, val parcelBytes: Int)

    companion object {
        private const val PAGES_PER_BATCH = 100
        private const val TOTAL_PAGES = 300
        private const val MAX_SAVED_FRAGMENT_STATES = 8
    }
}

class PagerStateProbeFragment : Fragment() {
    override fun onCreateView(
        inflater: android.view.LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = TextView(requireContext()).apply {
        id = android.R.id.text1
        text = requireArguments().getInt(ARG_PAGE).toString()
    }

    companion object {
        private const val ARG_PAGE = "page"

        fun newInstance(page: Int) = PagerStateProbeFragment().apply {
            arguments = Bundle().apply { putInt(ARG_PAGE, page) }
        }
    }
}
