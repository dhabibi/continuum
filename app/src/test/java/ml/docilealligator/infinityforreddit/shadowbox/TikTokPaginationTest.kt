package ml.docilealligator.infinityforreddit.shadowbox

import android.app.Application
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.RecyclerView
import ml.docilealligator.infinityforreddit.RedditDataRoomDatabase
import ml.docilealligator.infinityforreddit.post.Post
import ml.docilealligator.infinityforreddit.user.UserProfileImagesBatchLoader
import ml.docilealligator.infinityforreddit.viewmodels.ViewPostDetailActivityViewModel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Retrofit

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class TikTokPaginationTest {
    private fun model(posts: ArrayList<Post>) = ViewPostDetailActivityViewModel(
        mock(Retrofit::class.java), mock(Retrofit::class.java), mock(RedditDataRoomDatabase::class.java),
        null, mock(UserProfileImagesBatchLoader::class.java)
    ).also { it.posts = posts }

    @Test fun `new content replaces the selected footer instead of moving that footer forward`() {
        val controller = Robolectric.buildActivity(FragmentActivity::class.java).setup()
        val posts = arrayListOf(mock(Post::class.java), mock(Post::class.java))
        val adapter = ShadowboxPagerAdapter(controller.get(), model(posts), { false }, { true })
        adapter.buildPages()
        val firstId = adapter.getItemId(0)
        val footerId = adapter.getItemId(2)
        val changes = mutableListOf<String>()
        adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onItemRangeChanged(start: Int, count: Int, payload: Any?) { changes.add("replace:$start:$count") }
            override fun onItemRangeInserted(start: Int, count: Int) { changes.add("insert:$start:$count") }
        })
        posts.add(mock(Post::class.java))
        posts.add(mock(Post::class.java))
        assertEquals(2, adapter.appendPages())
        assertFalse(adapter.containsItem(footerId))
        assertEquals(firstId, adapter.getItemId(0))
        assertEquals(2, adapter.postIndexForPage(2))
        assertTrue(adapter.containsItem(adapter.getItemId(4)))
        assertEquals(listOf("replace:2:1", "insert:3:2"), changes)
        controller.pause().stop().destroy()
    }

    @Test fun `metadata updates cannot insert old filtered posts into the middle during an append`() {
        val controller = Robolectric.buildActivity(FragmentActivity::class.java).setup()
        val hidden = mock(Post::class.java)
        val posts = arrayListOf(mock(Post::class.java), hidden)
        var showHidden = false
        val adapter = ShadowboxPagerAdapter(controller.get(), model(posts), { false }, { it !== hidden || showHidden })
        adapter.buildPages()
        showHidden = true
        posts.add(mock(Post::class.java))
        assertEquals(1, adapter.appendPages())
        assertEquals(0, adapter.postIndexForPage(0))
        assertEquals(2, adapter.postIndexForPage(1))
        controller.pause().stop().destroy()
    }

    @Test fun `verified posts already in a source batch append in order without moving the visible page`() {
        val controller = Robolectric.buildActivity(FragmentActivity::class.java).setup()
        val posts = arrayListOf(mock(Post::class.java), mock(Post::class.java), mock(Post::class.java))
        val admitted = mutableSetOf(posts[0])
        val adapter = ShadowboxPagerAdapter(controller.get(), model(posts), { false }, { it in admitted })
        adapter.buildPages()
        val firstId = adapter.getItemId(0)
        val footerId = adapter.getItemId(1)

        admitted.add(posts[1])
        assertEquals(1, adapter.appendVerifiedPage(1))
        assertEquals(firstId, adapter.getItemId(0))
        assertFalse(adapter.containsItem(footerId))
        assertEquals(1, adapter.postIndexForPage(1))
        assertNull(adapter.appendVerifiedPage(1))

        admitted.add(posts[2])
        assertEquals(2, adapter.appendVerifiedPage(2))
        assertEquals(listOf(0, 1, 2), (0 until adapter.pageCount).map(adapter::postIndexForPage))
        controller.pause().stop().destroy()
    }

    @Test fun `a changed restored URL can be verified before later retained pages`() {
        val controller = Robolectric.buildActivity(FragmentActivity::class.java).setup()
        val posts = arrayListOf(mock(Post::class.java), mock(Post::class.java), mock(Post::class.java))
        // The first URL changed during rotation. Later posts still have valid retained decisions.
        val admitted = mutableSetOf(posts[1], posts[2])
        val adapter = ShadowboxPagerAdapter(controller.get(), model(posts), { false }, { it in admitted })
        adapter.buildPages()
        val selectedId = adapter.getItemId(1)
        val selectedPostIndex = adapter.postIndexForPage(1)

        admitted.add(posts[0])
        assertEquals(0, adapter.appendVerifiedPage(0))
        assertEquals(listOf(0, 1, 2), (0 until adapter.pageCount).map(adapter::postIndexForPage))
        assertEquals(selectedId, adapter.getItemId(adapter.pageForPostIndex(selectedPostIndex)))
        controller.pause().stop().destroy()
    }

    @Test fun `excluding a silent post invalidates its fragment and keeps other stable IDs`() {
        val controller = Robolectric.buildActivity(FragmentActivity::class.java).setup()
        val silent = mock(Post::class.java)
        val playable = mock(Post::class.java)
        val posts = arrayListOf(silent, playable)
        var includeSilent = true
        val adapter = ShadowboxPagerAdapter(controller.get(), model(posts), { false }, { it !== silent || includeSilent })
        adapter.buildPages()
        val silentId = adapter.getItemId(0)
        val playableId = adapter.getItemId(1)

        includeSilent = false
        adapter.buildPages()

        assertFalse(adapter.containsItem(silentId))
        assertTrue(adapter.containsItem(playableId))
        assertEquals(playableId, adapter.getItemId(0))
        controller.pause().stop().destroy()
    }
}
