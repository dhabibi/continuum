package ml.docilealligator.infinityforreddit.multireddit

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import ml.docilealligator.infinityforreddit.RedditDataRoomDatabase
import ml.docilealligator.infinityforreddit.account.Account
import ml.docilealligator.infinityforreddit.subreddit.SubredditData
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class LocalMultiredditMembershipTest {
    private val database = RedditDataRoomDatabase.createInMemoryForTest(ApplicationProvider.getApplicationContext())
    private val path = "/user/-/m/reading"
    private fun subreddit(name: String) = SubredditData("t5_test", name, "new-icon", "", "", "", 1, 0, "", false)

    @After fun close() = database.close()

    @Test fun `adding a community twice preserves existing members and metadata`() {
        database.multiRedditDao().insert(MultiReddit(path, "Reading", "reading", "description", null,
            null, "private", Account.ANONYMOUS_ACCOUNT, 0, 0, false, false, true))
        database.anonymousMultiredditSubredditDao().insert(AnonymousMultiredditSubreddit(path, "science", "original-icon"))
        MultiredditMembershipDialog.addLocal(database, path, subreddit("SCIENCE"))
        MultiredditMembershipDialog.addLocal(database, path, subreddit("android"))
        MultiredditMembershipDialog.addLocal(database, path, subreddit("ANDROID"))
        val members = database.anonymousMultiredditSubredditDao().getAllAnonymousMultiRedditSubreddits(path)
        assertEquals(listOf("android", "science"), members.map { it.subredditName })
        assertEquals("original-icon", members.last().iconUrl)
        assertTrue(database.multiRedditDao().getMultiReddit(path, Account.ANONYMOUS_ACCOUNT).isFavorite)
    }

    @Test fun `a deleted feed cannot receive orphan members`() {
        assertThrows(IllegalArgumentException::class.java) {
            MultiredditMembershipDialog.addLocal(database, path, subreddit("android"))
        }
        assertTrue(database.anonymousMultiredditSubredditDao().getAllSubreddits().isEmpty())
    }
}
