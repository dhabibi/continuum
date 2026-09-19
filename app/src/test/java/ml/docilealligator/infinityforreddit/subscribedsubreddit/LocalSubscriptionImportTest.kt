package ml.docilealligator.infinityforreddit.subscribedsubreddit

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import ml.docilealligator.infinityforreddit.RedditDataRoomDatabase
import ml.docilealligator.infinityforreddit.account.Account
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Retrofit
import retrofit2.converter.scalars.ScalarsConverterFactory

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class LocalSubscriptionImportTest {
    private val database = RedditDataRoomDatabase.createInMemoryForTest(ApplicationProvider.getApplicationContext())
    private val anonymous = Account.ANONYMOUS_ACCOUNT

    @After fun close() = database.close()

    private fun retrofit(status: Int, body: String): Retrofit = Retrofit.Builder()
        .baseUrl("https://example.test/")
        .addConverterFactory(ScalarsConverterFactory.create())
        .client(OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(status).message("fixture").body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()).build()

    @Test fun `reimport preserves favorites and reports missing names`() {
        database.subscribedSubredditDao().insert(SubscribedSubredditData("t5_pics", "pics", "original-icon", anonymous, true))
        val client = retrofit(200, """{"data":{"children":[{"data":{"name":"t5_news","display_name":"news","subscribers":5}}]}}""")
        val names = listOf("PICS", "news", "missing")
        val first = LocalSubscriptionImport.importNames(client, database, names)
        val second = LocalSubscriptionImport.importNames(client, database, names)
        assertEquals(1, first.added)
        assertEquals(1, first.existing)
        assertEquals(listOf("missing"), first.unavailable)
        assertEquals(0, second.added)
        assertEquals(2, second.existing)
        val original = database.subscribedSubredditDao().getSubscribedSubreddit("pics", anonymous)!!
        assertTrue(original.isFavorite)
        assertEquals("original-icon", original.iconUrl)
    }

    @Test fun `failed lookup does not change subscriptions`() {
        database.subscribedSubredditDao().insert(SubscribedSubredditData("t5_pics", "pics", "", anonymous, true))
        assertThrows(java.io.IOException::class.java) {
            LocalSubscriptionImport.importNames(retrofit(503, "unavailable"), database, listOf("news"))
        }
        assertEquals(listOf("pics"), database.subscribedSubredditDao().getAllSubscribedSubredditsList(anonymous).map { it.name })
    }

    @Test fun `importing a user follow preserves its saved and favorite state`() {
        val dao = database.subscribedUserDao()
        dao.insertIfAbsent("alice", "icon", anonymous)
        dao.updateSaved("alice", anonymous, true)
        dao.updateFavorite("alice", anonymous, true)
        val client = retrofit(200, """{"data":{"children":[{"data":{"name":"t5_alice","display_name":"u_alice","subscribers":1}}]}}""")
        assertEquals(1, LocalSubscriptionImport.importNames(client, database, listOf("u_alice")).added)
        val user = dao.getSubscribedUser("alice", anonymous)!!
        assertTrue(user.isFollowed)
        assertTrue(user.isSaved)
        assertTrue(user.isFavorite)
    }
}
