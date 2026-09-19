package ml.docilealligator.infinityforreddit.account

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import ml.docilealligator.infinityforreddit.RedditDataRoomDatabase
import ml.docilealligator.infinityforreddit.multireddit.AnonymousMultiredditSubreddit
import ml.docilealligator.infinityforreddit.multireddit.MultiReddit
import ml.docilealligator.infinityforreddit.subscribedsubreddit.SubscribedSubredditData
import ml.docilealligator.infinityforreddit.subscribeduser.SubscribedUserData
import ml.docilealligator.infinityforreddit.utils.SharedPreferencesUtils
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class LocalProfilesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val executor = Executors.newSingleThreadExecutor()
    private val databases = mutableListOf<RedditDataRoomDatabase>()
    private val account = Account.ANONYMOUS_ACCOUNT
    private val path = "/user/-/m/reading"

    @Before fun reset() {
        LocalProfiles.resetForTests()
        context.getSharedPreferences("continuum_local_profiles", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After fun close() {
        databases.forEach { it.close() }
        executor.shutdownNow()
        LocalProfiles.resetForTests()
    }

    private fun <T> io(block: () -> T): T = executor.submit(Callable { block() }).get(10, TimeUnit.SECONDS)

    private fun open(): RedditDataRoomDatabase = RedditDataRoomDatabase.create(context).also { databases.add(it) }

    private fun seed(database: RedditDataRoomDatabase, subreddit: String, user: String) = io {
        database.runInTransaction {
            database.subscribedSubredditDao().insert(SubscribedSubredditData("t5_$subreddit", subreddit, "", account, true))
            database.subscribedUserDao().insert(SubscribedUserData(user, "", account, true))
            database.multiRedditDao().insert(MultiReddit(path, "Reading", "reading", null, null, null, null,
                account, 0, 0, false, false, true))
            database.anonymousMultiredditSubredditDao().insert(AnonymousMultiredditSubreddit(path, subreddit, null))
        }
    }

    @Test fun `switching accounts isolates lists and preserves the existing default database`() {
        val original = LocalProfiles.get(context)
        val first = open()
        seed(first, "android", "alice")
        assertTrue(context.getDatabasePath(RedditDataRoomDatabase.DATABASE_NAME).exists())
        val resume = File(original.filesDir(context), "resume_state.json")
        resume.writeText("default account screen")
        val currentPrefs = SharedPreferencesUtils.CURRENT_ACCOUNT_SHARED_PREFERENCES_FILE
        context.getSharedPreferences(original.preferenceFileName(currentPrefs), 0).edit()
            .putString(SharedPreferencesUtils.ACCOUNT_NAME, "alice").commit()

        val secondId = original.create("Work")
        assertTrue(original.select(secondId))
        // Pending background work keeps the previous account's partition until the restart.
        assertEquals(LocalProfiles.DEFAULT_ID, original.currentId)
        assertEquals(resume, File(original.filesDir(context), "resume_state.json"))
        LocalProfiles.resetForTests()

        val work = LocalProfiles.get(context)
        val second = open()
        assertEquals(secondId, work.currentId)
        assertFalse(File(work.filesDir(context), "resume_state.json").exists())
        assertNull(context.getSharedPreferences(work.preferenceFileName(currentPrefs), 0)
            .getString(SharedPreferencesUtils.ACCOUNT_NAME, null))
        io {
            assertTrue(second.subscribedSubredditDao().getAllSubscribedSubredditsList(account).isEmpty())
            assertTrue(second.subscribedUserDao().getAllSubscribedUsersList(account).isEmpty())
            assertNull(second.multiRedditDao().getMultiReddit(path, account))
        }
        // The same multireddit path may mean different things in the two local accounts.
        seed(second, "science", "bob")
        work.renameCurrent("Research")
        assertTrue(work.select(LocalProfiles.DEFAULT_ID))
        LocalProfiles.resetForTests()

        assertEquals("default account screen", File(LocalProfiles.get(context).filesDir(context), "resume_state.json").readText())
        val reopened = open()
        io {
            assertEquals(listOf("android"), reopened.subscribedSubredditDao().getAllSubscribedSubredditsList(account).map { it.name })
            assertEquals(listOf("alice"), reopened.subscribedUserDao().getAllSubscribedUsersList(account).map { it.name })
            assertEquals(listOf("android"), reopened.anonymousMultiredditSubredditDao().getAllAnonymousMultiRedditSubreddits(path).map { it.subredditName })
            assertEquals(listOf("science"), second.anonymousMultiredditSubredditDao().getAllAnonymousMultiRedditSubreddits(path).map { it.subredditName })
        }
        assertEquals("Research", LocalProfiles.get(context).profiles.first { it.id == secondId }.name)
    }

    @Test fun `names are unique while storage paths are independent of display names`() {
        val profiles = LocalProfiles.get(context)
        assertThrows(IllegalArgumentException::class.java) { profiles.create("  ") }
        val id = profiles.create("Work")
        assertThrows(IllegalArgumentException::class.java) { profiles.create(" work ") }
        assertTrue(profiles.select(id))
        LocalProfiles.resetForTests()
        val work = LocalProfiles.get(context)
        val directory = work.filesDir(context)
        work.renameCurrent("../Personal")
        assertEquals(directory, work.filesDir(context))
        assertTrue(directory.canonicalPath.startsWith(context.filesDir.canonicalPath + File.separator))
        assertFalse(work.select("../../outside"))
    }
}
