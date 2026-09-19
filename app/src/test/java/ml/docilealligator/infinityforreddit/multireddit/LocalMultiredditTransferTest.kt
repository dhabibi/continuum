package ml.docilealligator.infinityforreddit.multireddit

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
class LocalMultiredditTransferTest {
    private val source = RedditDataRoomDatabase.createInMemoryForTest(ApplicationProvider.getApplicationContext())
    private val target = RedditDataRoomDatabase.createInMemoryForTest(ApplicationProvider.getApplicationContext())
    private val account = Account.ANONYMOUS_ACCOUNT
    private val path = "/user/-/m/reading"

    @After fun close() { source.close(); target.close() }

    private fun seed(database: RedditDataRoomDatabase, member: String) {
        database.multiRedditDao().insert(MultiReddit(path, "Reading", "reading", "My description", null,
            "icon", "private", account, 0, 123L, false, false, true))
        database.anonymousMultiredditSubredditDao().insert(AnonymousMultiredditSubreddit(path, member, "member-icon"))
    }

    @Test fun `move keeps membership and favorites without overwriting a colliding destination`() {
        seed(source, "android")
        seed(target, "science")
        val result = LocalMultiredditTransfer.moveBetweenDatabases(source, target, listOf(path))
        assertEquals(1, result.moved)
        assertEquals(listOf("Reading (2)"), result.renamed)
        assertNull(source.multiRedditDao().getMultiReddit(path, account))
        assertTrue(source.anonymousMultiredditSubredditDao().getAllSubreddits().isEmpty())
        assertEquals("science", target.anonymousMultiredditSubredditDao().getAllAnonymousMultiRedditSubreddits(path).single().subredditName)
        val copy = target.multiRedditDao().getMultiReddit("/user/-/m/reading_2", account)
        assertEquals("My description", copy.description)
        assertTrue(copy.isFavorite)
        assertEquals("android", target.anonymousMultiredditSubredditDao().getAllAnonymousMultiRedditSubreddits(copy.path).single().subredditName)
    }

    @Test fun `failed destination write retains the only copy and rolls back partial target rows`() {
        seed(source, "android")
        target.openHelper.writableDatabase.execSQL("""CREATE TRIGGER fail_member BEFORE INSERT ON anonymous_multireddit_subreddits
            BEGIN SELECT RAISE(ABORT, 'test write failure'); END""")
        assertThrows(RuntimeException::class.java) {
            LocalMultiredditTransfer.moveBetweenDatabases(source, target, listOf(path))
        }
        assertNotNull(source.multiRedditDao().getMultiReddit(path, account))
        assertEquals(1, source.anonymousMultiredditSubredditDao().getAllSubreddits().size)
        assertNull(target.multiRedditDao().getMultiReddit(path, account))
    }

    @Test fun `a stale bulk selection does not move a subset`() {
        seed(source, "android")
        assertThrows(IllegalArgumentException::class.java) {
            LocalMultiredditTransfer.moveBetweenDatabases(source, target, listOf(path, "/missing"))
        }
        assertNotNull(source.multiRedditDao().getMultiReddit(path, account))
        assertTrue(target.multiRedditDao().getAllMultiRedditsList(account).isEmpty())
    }
}
