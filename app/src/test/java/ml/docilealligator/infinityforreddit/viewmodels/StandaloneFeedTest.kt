package ml.docilealligator.infinityforreddit.viewmodels

import android.app.Application
import android.os.Looper
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import ml.docilealligator.infinityforreddit.RedditDataRoomDatabase
import ml.docilealligator.infinityforreddit.account.Account
import ml.docilealligator.infinityforreddit.post.LoadingMorePostsStatus
import ml.docilealligator.infinityforreddit.post.Post
import ml.docilealligator.infinityforreddit.post.PostType
import ml.docilealligator.infinityforreddit.readpost.ReadPost
import ml.docilealligator.infinityforreddit.readpost.ReadPostType
import ml.docilealligator.infinityforreddit.subscribedsubreddit.SubscribedSubredditData
import ml.docilealligator.infinityforreddit.user.UserProfileImagesBatchLoader
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import retrofit2.Retrofit
import retrofit2.converter.scalars.ScalarsConverterFactory

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class StandaloneFeedTest {
    private val database = RedditDataRoomDatabase.createInMemoryForTest(ApplicationProvider.getApplicationContext())
    private val requests = CopyOnWriteArrayList<Request>()
    private val store = ViewModelStore()
    private var respond: (Request, Call) -> String = { _, _ -> listing(null) }
    private val client = OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request()
        requests.add(request)
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body(respond(request, chain.call()).toResponseBody("application/json".toMediaType())).build()
    }.build()
    private val retrofit = Retrofit.Builder().baseUrl("https://reddit.example/").client(client)
        .addConverterFactory(ScalarsConverterFactory.create()).build()
    private val model = ViewPostDetailActivityViewModel(retrofit, retrofit, database, null,
        mock(UserProfileImagesBatchLoader::class.java))
    private var state = ViewPostDetailActivityViewModel.LoadMorePostsState(LoadingMorePostsStatus.NOT_LOADING)
    private val observer = Observer<ViewPostDetailActivityViewModel.LoadMorePostsState> { state = it }

    @Before fun observe() {
        store.put("feed", model)
        model.loadMorePostsState.observeForever(observer)
    }

    @After fun close() {
        model.stopFeedLoading()
        model.loadMorePostsState.removeObserver(observer)
        store.clear()
        client.dispatcher.executorService.shutdownNow()
        client.connectionPool.evictAll()
        database.close()
    }

    @Test fun `home resolves its own memberships and starts without an inherited feed cursor`() {
        for (name in listOf("pics", "aww")) {
            database.subscribedSubredditDao().insert(SubscribedSubredditData(name, name, "", Account.ANONYMOUS_ACCOUNT, false))
        }
        val inherited = mock(Post::class.java)
        `when`(inherited.fullName).thenReturn("t3_underlying_feed")
        model.posts = arrayListOf(inherited)
        model.startFeed(PostFeedRequest(PostType.ANONYMOUS_FRONT_PAGE, Account.ANONYMOUS_ACCOUNT))
        await { state.status == LoadingMorePostsStatus.NO_MORE_POSTS }

        val request = requests.single()
        assertEquals("aww+pics", request.url.pathSegments[1])
        assertNull(request.url.queryParameter("after"))
        assertEquals("40", request.url.queryParameter("limit"))
        assertTrue(model.posts!!.isEmpty())
        assertFalse(state.emptySource)
    }

    @Test fun `an empty local home does not fetch a substitute public feed`() {
        model.startFeed(PostFeedRequest(PostType.ANONYMOUS_FRONT_PAGE, Account.ANONYMOUS_ACCOUNT))
        await { state.status == LoadingMorePostsStatus.NO_MORE_POSTS }
        assertTrue(state.emptySource)
        assertFalse(state.hasMore)
        assertTrue(requests.isEmpty())
    }

    @Test fun `filtered batches can continue while the upstream cursor advances`() {
        val calls = AtomicInteger()
        respond = { _, _ ->
            val page = calls.incrementAndGet()
            listing(if (page <= 5) "cursor-" + page else null)
        }
        model.startFeed(PostFeedRequest(PostType.SUBREDDIT, Account.ANONYMOUS_ACCOUNT, subredditName = "pics"))
        await { state.status == LoadingMorePostsStatus.LOADED }
        assertTrue(state.hasMore)
        assertEquals(0, state.nNewPosts)
        assertEquals(5, requests.size)
        val firstBatch = state.batchId

        model.loadNextFeedPage()
        await { state.status == LoadingMorePostsStatus.NO_MORE_POSTS }
        assertEquals("cursor-5", requests.last().url.queryParameter("after"))
        assertEquals("100", requests.last().url.queryParameter("limit"))
        assertFalse(state.hasMore)
        assertTrue(state.batchId > firstBatch)
    }

    @Test fun `anonymous history advances over filtered posts without requesting an empty id list`() {
        runBlocking {
            database.readPostDaoKt().insert(ReadPost(Account.ANONYMOUS_ACCOUNT, "history", ReadPostType.READ_POSTS))
        }
        model.startFeed(PostFeedRequest(PostType.READ_POSTS, Account.ANONYMOUS_ACCOUNT))
        await { state.status == LoadingMorePostsStatus.NO_MORE_POSTS }

        val request = requests.single()
        assertEquals("t3_history", request.url.queryParameter("id"))
        assertNull(request.header("Authorization"))
        assertFalse(state.hasMore)
    }

    @Test fun `changing source cancels the old request and starts the new source at its beginning`() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val oldCall = AtomicReference<Call>()
        respond = { request, call ->
            if (request.url.pathSegments[1] == "old") {
                oldCall.set(call)
                started.countDown()
                check(release.await(3, TimeUnit.SECONDS))
            }
            listing(null)
        }
        try {
            model.startFeed(PostFeedRequest(PostType.SUBREDDIT, Account.ANONYMOUS_ACCOUNT, subredditName = "old"))
            await { started.count == 0L }
            model.startFeed(PostFeedRequest(PostType.SUBREDDIT, Account.ANONYMOUS_ACCOUNT, subredditName = "new"))
            assertTrue(oldCall.get().isCanceled())
            await { state.status == LoadingMorePostsStatus.NO_MORE_POSTS }
            assertEquals("new", model.currentFeedRequest!!.subredditName)
            assertNull(requests.last().url.queryParameter("after"))
            assertEquals(2, requests.size)
        } finally {
            release.countDown()
        }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4)
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(5)
        }
        fail("Feed did not reach the expected state: " + state)
    }

    private fun listing(cursor: String?): String = JSONObject().put("data",
        JSONObject().put("children", JSONArray()).put("after", cursor ?: JSONObject.NULL)).toString()
}
