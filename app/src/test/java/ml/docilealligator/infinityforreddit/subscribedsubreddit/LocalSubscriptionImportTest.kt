package ml.docilealligator.infinityforreddit.subscribedsubreddit

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import ml.docilealligator.infinityforreddit.RedditDataRoomDatabase
import ml.docilealligator.infinityforreddit.account.Account
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class LocalSubscriptionImportTest {
    private val database = RedditDataRoomDatabase.createInMemoryForTest(ApplicationProvider.getApplicationContext())
    private val anonymous = Account.ANONYMOUS_ACCOUNT

    @After fun close() = database.close()

    @Test fun `unknown communities import offline and reimport preserves favorites`() {
        database.subscribedSubredditDao().insert(SubscribedSubredditData("t5_pics", "pics", "original-icon", anonymous, true))
        val names = listOf("PICS", "news", "UnknownCommunity", "NEWS")
        val first = LocalSubscriptionImport.importNames(database, names)
        val second = LocalSubscriptionImport.importNames(database, names)
        assertEquals(2, first.added)
        assertEquals(1, first.existing)
        assertEquals(0, second.added)
        assertEquals(3, second.existing)
        val original = database.subscribedSubredditDao().getSubscribedSubreddit("pics", anonymous)!!
        assertTrue(original.isFavorite)
        assertEquals("original-icon", original.iconUrl)
        assertEquals("local:unknowncommunity", database.subscribedSubredditDao().getSubscribedSubreddit("UnknownCommunity", anonymous)!!.id)
    }

    @Test fun `invalid names cannot partially change the local list`() {
        assertThrows(IllegalArgumentException::class.java) {
            LocalSubscriptionImport.importNames(database, listOf("news", "bad/name"))
        }
        assertTrue(database.subscribedSubredditDao().getAllSubscribedSubredditsList(anonymous).isEmpty())
    }

    @Test fun `profile links reach Home as well as follows and preserve saved state`() {
        val dao = database.subscribedUserDao()
        dao.insertIfAbsent("alice", "icon", anonymous)
        dao.updateSaved("alice", anonymous, true)
        dao.updateFavorite("alice", anonymous, true)
        assertEquals(1, LocalSubscriptionImport.importNames(database, listOf("u_alice")).added)
        val user = dao.getSubscribedUser("alice", anonymous)!!
        assertTrue(user.isFollowed)
        assertTrue(user.isSaved)
        assertTrue(user.isFavorite)
        assertNotNull(database.subscribedSubredditDao().getSubscribedSubreddit("u_alice", anonymous))
    }
}
