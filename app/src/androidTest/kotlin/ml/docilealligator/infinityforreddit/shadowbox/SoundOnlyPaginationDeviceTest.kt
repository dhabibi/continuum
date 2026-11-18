package ml.docilealligator.infinityforreddit.shadowbox

import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CopyOnWriteArrayList
import ml.docilealligator.infinityforreddit.R
import ml.docilealligator.infinityforreddit.account.Account
import ml.docilealligator.infinityforreddit.post.LoadingMorePostsStatus
import ml.docilealligator.infinityforreddit.post.PostType
import ml.docilealligator.infinityforreddit.viewmodels.PostFeedRequest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SoundOnlyPaginationDeviceTest {
    @Test fun stationaryFooterShowsListingFailure() = exercise(503)
    @Test fun stationaryFooterShowsExhaustedListing() = exercise(200)

    private fun exercise(lastStatus: Int) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val cursors = CopyOnWriteArrayList<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            cursors.add(request.url.queryParameter("after") ?: "initial")
            val page = cursors.size
            val after = if (page <= 5) "\"cursor-$page\"" else "null"
            val code = if (page <= 5) 200 else lastStatus
            val body = """{"kind":"Listing","data":{"children":[],"after":$after}}"""
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code)
                .message(if (code == 200) "OK" else "Service unavailable")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()

        // The initial local feed reads an absent multireddit from Room and makes no HTTP call.
        val intent = Intent(instrumentation.targetContext, ShadowboxActivity::class.java)
            .putExtra(ShadowboxActivity.EXTRA_FEED_POST_TYPE, PostType.ANONYMOUS_MULTIREDDIT)
            .putExtra(ShadowboxActivity.EXTRA_FEED_MULTI_PATH, "empty-local-test-list")
            .putExtra(ShadowboxActivity.EXTRA_TIKTOK_WITH_SOUND, true)
        try {
            ActivityScenario.launch<ShadowboxActivity>(intent).use { scenario ->
                fun await(description: String, condition: (ShadowboxActivity) -> Boolean) {
                    val end = SystemClock.elapsedRealtime() + 12000
                    do {
                        var ready = false
                        scenario.onActivity { ready = condition(it) }
                        if (ready) return
                        SystemClock.sleep(30)
                    } while (SystemClock.elapsedRealtime() < end)
                    error("Timed out waiting for $description; cursors=$cursors")
                }
                await("empty local feed") {
                    it.viewModel.loadMorePostsState.value?.emptySource == true &&
                        it.findViewById<TextView>(R.id.status_text_view_shadowbox_end_page_fragment)?.isShown == true
                }
                scenario.onActivity { activity ->
                    val model = activity.viewModel
                    // Only the listing transport is replaced. The Activity, ViewModel, actual coroutine
                    // loader, cursor loop, admission observer, fragment, and rendered footer stay in place.
                    val retrofit = activity.retrofit.newBuilder().baseUrl("http://127.0.0.1/").client(client).build()
                    model.javaClass.getDeclaredField("retrofit").apply { isAccessible = true }.set(model, retrofit)
                    model.startFeed(PostFeedRequest(PostType.SUBREDDIT, Account.ANONYMOUS_ACCOUNT,
                        subredditName = "fixture", mediaOnly = true))
                }
                val terminal = if (lastStatus == 503) LoadingMorePostsStatus.FAILED else LoadingMorePostsStatus.NO_MORE_POSTS
                await("sixth request and terminal listing state") {
                    cursors.size == 6 && it.viewModel.loadMorePostsState.value?.status == terminal
                }
                // No taps, selections, media callbacks or lifecycle transitions intervene.
                instrumentation.waitForIdleSync()
                scenario.onActivity { activity ->
                    assertEquals(listOf("initial", "cursor-1", "cursor-2", "cursor-3", "cursor-4", "cursor-5"), cursors.toList())
                    assertTrue(activity.isSoundOnlyFeedEmpty())
                    val label = activity.findViewById<TextView>(R.id.status_text_view_shadowbox_end_page_fragment)
                    val spinner = activity.findViewById<View>(R.id.progress_bar_shadowbox_end_page_fragment)
                    val expected = activity.getString(if (lastStatus == 503) R.string.load_more_posts_failed else R.string.tiktok_sound_only_empty)
                    val result = "Expected terminal footer for HTTP $lastStatus at ${cursors.last()}"
                    assertEquals(result, expected, label.text.toString())
                    assertFalse(result, spinner.isShown)
                }
            }
        } finally {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }
}
