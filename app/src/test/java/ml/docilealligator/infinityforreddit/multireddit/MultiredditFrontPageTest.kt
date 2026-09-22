package ml.docilealligator.infinityforreddit.multireddit

import android.app.Activity
import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.Executor
import ml.docilealligator.infinityforreddit.RedditDataRoomDatabase
import ml.docilealligator.infinityforreddit.account.Account
import ml.docilealligator.infinityforreddit.events.ChangeAnonymousSubredditSubscriptionEvent
import ml.docilealligator.infinityforreddit.subscribedsubreddit.SubscribedSubredditData
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import retrofit2.Retrofit

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class MultiredditFrontPageTest {
    private val database = RedditDataRoomDatabase.createInMemoryForTest(ApplicationProvider.getApplicationContext())
    private val otherProfile = RedditDataRoomDatabase.createInMemoryForTest(ApplicationProvider.getApplicationContext())
    private val account = Account.ANONYMOUS_ACCOUNT
    private val path = "/user/-/m/reading"

    @After fun close() {
        database.close()
        otherProfile.close()
    }

    class SubscriptionObserver {
        var updates = 0
        @Subscribe fun onChange(event: ChangeAnonymousSubredditSubscriptionEvent) { updates++ }
    }

    @Test fun `local menu action adds members offline to this profile and refreshes Home once`() {
        database.multiRedditDao().insert(MultiReddit(path, "Reading", "reading", "", null,
            "", "private", account, 0, 0L, false, false, false))
        database.anonymousMultiredditSubredditDao().insertAll(listOf(
            AnonymousMultiredditSubreddit(path, "PICS", null),
            AnonymousMultiredditSubreddit(path, "science", null)))
        database.subscribedSubredditDao().insert(
            SubscribedSubredditData("t5_pics", "pics", "favorite-icon", account, true))
        val api = mock(Retrofit::class.java)
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val observer = SubscriptionObserver()
        EventBus.getDefault().register(observer)
        try {
            repeat(2) {
                MultiredditFrontPage.add(controller.get(), Executor { it.run() }, database,
                    api, api, account, null, path)
                shadowOf(Looper.getMainLooper()).idle()
            }
            assertEquals(setOf("pics", "science"), database.subscribedSubredditDao()
                .getAllSubscribedSubredditsList(account).map { it.name }.toSet())
            val favorite = database.subscribedSubredditDao().getSubscribedSubreddit("pics", account)!!
            assertTrue(favorite.isFavorite)
            assertEquals("favorite-icon", favorite.iconUrl)
            assertTrue(otherProfile.subscribedSubredditDao().getAllSubscribedSubredditsList(account).isEmpty())
            assertEquals(2, database.anonymousMultiredditSubredditDao().getAllAnonymousMultiRedditSubreddits(path).size)
            assertEquals(1, observer.updates)
            verifyNoInteractions(api)
        } finally {
            EventBus.getDefault().unregister(observer)
            controller.pause().stop().destroy()
        }
    }
}
