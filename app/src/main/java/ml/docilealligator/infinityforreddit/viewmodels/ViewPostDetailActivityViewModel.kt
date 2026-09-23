package ml.docilealligator.infinityforreddit.viewmodels

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ml.docilealligator.infinityforreddit.RedditDataRoomDatabase
import ml.docilealligator.infinityforreddit.account.Account
import ml.docilealligator.infinityforreddit.apis.RedditAPIKt
import ml.docilealligator.infinityforreddit.comment.Comment
import ml.docilealligator.infinityforreddit.post.LoadingMorePostsStatus
import ml.docilealligator.infinityforreddit.post.ParsePost
import ml.docilealligator.infinityforreddit.post.Post
import ml.docilealligator.infinityforreddit.post.PostType
import ml.docilealligator.infinityforreddit.postfilter.PostFilter
import ml.docilealligator.infinityforreddit.readpost.ReadPostType
import ml.docilealligator.infinityforreddit.readpost.ReadPostsListInterface
import ml.docilealligator.infinityforreddit.thing.SortType
import ml.docilealligator.infinityforreddit.user.UserProfileImagesBatchLoader
import ml.docilealligator.infinityforreddit.utils.APIUtils
import ml.docilealligator.infinityforreddit.utils.JSONUtils
import ml.docilealligator.infinityforreddit.utils.TextToSpeechHelper
import org.json.JSONException
import org.json.JSONObject
import retrofit2.Response
import retrofit2.Retrofit

class ViewPostDetailActivityViewModel(
    private val retrofit: Retrofit,
    private val oauthRetrofit: Retrofit,
    private val redditDataRoomDatabase: RedditDataRoomDatabase,
    private val accessToken: String?,
    private val loader: UserProfileImagesBatchLoader
) : ViewModel() {
    var post: Post? = null

    var posts: ArrayList<Post>? = null

    var currentFeedRequest: PostFeedRequest? = null
        private set

    private var loadJob: Job? = null
    private var requestGeneration = 0L
    private var completedBatchId = 0L
    private var listingExhausted = false
    private var emptyLocalSource = false
    private var lastReadPostTime: Long? = null

    // Held here (not on the activity) so Read Aloud survives configuration changes such as rotation.
    private var textToSpeechHelper: TextToSpeechHelper? = null

    fun getTextToSpeechHelper(context: Context): TextToSpeechHelper {
        return textToSpeechHelper ?: TextToSpeechHelper(context).also { textToSpeechHelper = it }
    }

    fun stopTextToSpeech() {
        textToSpeechHelper?.stop()
    }

    fun shutdownTextToSpeech() {
        textToSpeechHelper?.shutdown()
    }

    override fun onCleared() {
        super.onCleared()
        textToSpeechHelper?.shutdown()
        textToSpeechHelper = null
    }

    /**
     * The listing cursor from the last response, rather than the last post that survived filtering.
     * A page can be fetched and kept in full, in part, or not at all -- "Media Posts Only" on a page
     * of link posts keeps none of it -- and paging from the last *kept* post would then re-request
     * the page just read and never move. Reddit's own `after` is what actually advances.
     */
    private var lastListingCursor: String? = null

    private var _loadMorePostsState = MutableStateFlow(LoadMorePostsState(LoadingMorePostsStatus.NOT_LOADING, 0))
    val loadMorePostsState = _loadMorePostsState.asLiveData()

    data class LoadMorePostsState(
        val status: Int,
        val nNewPosts: Int = 0,
        val changePage: Boolean = false,
        val hasMore: Boolean = status != LoadingMorePostsStatus.NO_MORE_POSTS,
        val batchId: Long = 0,
        val emptySource: Boolean = false,
    )

    /** Start an independent listing, canceling every pending result from the previous source. */
    fun startFeed(request: PostFeedRequest) {
        stopFeedLoading()
        stopTextToSpeech()
        currentFeedRequest = request
        posts = ArrayList()
        post = null
        lastListingCursor = null
        lastReadPostTime = null
        listingExhausted = false
        emptyLocalSource = false
        publishLoadState(LoadingMorePostsStatus.NOT_LOADING)
        loadNextFeedPage()
    }

    fun loadNextFeedPage() {
        val request = currentFeedRequest ?: return
        fetchMorePosts(
            request.accessToken, request.accountName, false, request.postType,
            request.subredditName, request.concatenatedSubredditNames, request.username,
            request.userWhere, request.multiPath, request.query, request.sortType,
            request.sortTime, request.postFilter, request.readPostType,
            request.readPostsList, request.mediaOnly,
        )
    }

    /** Pause network work without losing the current source, cursor or already loaded posts. */
    fun stopFeedLoading() {
        requestGeneration++
        loadJob?.cancel()
        loadJob = null
        if (_loadMorePostsState.value.status == LoadingMorePostsStatus.LOADING) {
            publishLoadState(LoadingMorePostsStatus.NOT_LOADING)
        }
    }

    private fun publishLoadState(status: Int, added: Int = 0, changePage: Boolean = false) {
        _loadMorePostsState.value = LoadMorePostsState(
            status, added, changePage, hasMore = !listingExhausted, batchId = completedBatchId,
            emptySource = emptyLocalSource,
        )
    }

    private fun completeBatch(added: Int, changePage: Boolean) {
        completedBatchId++
        publishLoadState(
            if (added == 0 && listingExhausted) LoadingMorePostsStatus.NO_MORE_POSTS
            else LoadingMorePostsStatus.LOADED,
            added, changePage,
        )
    }

    private suspend fun localFeedNames(postType: Int, accountName: String, multiPath: String?): String? =
        withContext(Dispatchers.IO) {
            val names = if (postType == PostType.ANONYMOUS_MULTIREDDIT) {
                redditDataRoomDatabase.anonymousMultiredditSubredditDao()
                    .getAllAnonymousMultiRedditSubreddits(multiPath).map { it.subredditName }
            } else {
                redditDataRoomDatabase.subscribedSubredditDao()
                    .getAllSubscribedSubredditsList(accountName).map { it.name }
            }
            names.distinctBy { it.lowercase(java.util.Locale.ROOT) }
                .joinToString("+").takeIf { it.isNotEmpty() }
        }

    fun getPost(index: Int): Post? {
        return posts?.getOrNull(index)
    }

    fun loadAuthorImages(comments: List<Comment>, loadIconListener: UserProfileImagesBatchLoader.LoadIconListener) {
        loader.loadAuthorImagesInComments(accessToken, comments, loadIconListener)
    }

    fun fetchMorePosts(
        accessToken: String?,
        accountName: String,
        changePage: Boolean,
        postType: Int,
        subredditName: String?,
        concatenatedSubredditNames: String?,
        username: String?,
        userWhere: String?,
        multiPath: String?,
        query: String?,
        sortType: SortType.Type?,
        sortTime: SortType.Time?,
        postFilter: PostFilter?,
        @ReadPostType readPostType: Int,
        readPostsList: ReadPostsListInterface?,
        mediaOnly: Boolean
    ) {
        if (_loadMorePostsState.value.status == LoadingMorePostsStatus.LOADING) return
        if (listingExhausted || _loadMorePostsState.value.status == LoadingMorePostsStatus.NO_MORE_POSTS) {
            listingExhausted = true
            publishLoadState(LoadingMorePostsStatus.NO_MORE_POSTS)
            return
        }
        if (postType == PostType.DUPLICATES) {
            // This loader has no duplicate-discussions endpoint; never substitute the Home feed.
            listingExhausted = true
            publishLoadState(LoadingMorePostsStatus.NO_MORE_POSTS)
            return
        }
        if (postType != PostType.READ_POSTS && sortType == null) {
            publishLoadState(LoadingMorePostsStatus.FAILED)
            return
        }

        val generation = requestGeneration
        publishLoadState(LoadingMorePostsStatus.LOADING)
        loadJob = viewModelScope.launch {
            try {
                if (generation != requestGeneration) return@launch
                val anonymous = accountName == Account.ANONYMOUS_ACCOUNT
                val api = (if (anonymous) retrofit else oauthRetrofit).create(RedditAPIKt::class.java)
                val localSource = postType == PostType.ANONYMOUS_FRONT_PAGE || postType == PostType.ANONYMOUS_MULTIREDDIT
                val sourceSubreddits = if (localSource) {
                    concatenatedSubredditNames?.takeIf { it.isNotBlank() }
                        ?: localFeedNames(postType, accountName, multiPath)
                } else null
                if (localSource && sourceSubreddits == null) {
                    emptyLocalSource = true
                    listingExhausted = true
                    completeBatch(0, changePage)
                    return@launch
                }

                var barrenPages = 0
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val afterKey = lastListingCursor ?: posts?.lastOrNull()?.fullName
                    val requestLimit = if (currentFeedRequest != null && afterKey == null) 40
                        else APIUtils.subredditAPICallLimit(subredditName)
                    val response: Response<String>?
                    var historyCursor: Long? = null
                    var historyCursorBefore: Long? = null
                    if (postType == PostType.READ_POSTS) {
                        val lastItem = lastReadPostTime ?: posts?.lastOrNull()?.let {
                            redditDataRoomDatabase.readPostDaoKt().getReadPost(it.id)?.time
                        } ?: Long.MAX_VALUE
                        val readPosts = redditDataRoomDatabase.readPostDaoKt()
                            .getAllReadPosts(accountName, lastItem, readPostType)
                        if (readPosts.isEmpty()) {
                            listingExhausted = true
                            completeBatch(0, changePage)
                            return@launch
                        }
                        historyCursorBefore = lastItem
                        historyCursor = readPosts.last().time
                        val ids = readPosts.joinToString(",") { "t3_" + it.id }
                        response = if (anonymous) api.getInfo(ids)
                            else api.getInfoOauth(ids, APIUtils.getOAuthHeader(accessToken))
                    } else {
                        val listingSort = requireNotNull(sortType)
                        when (postType) {
                            PostType.SUBREDDIT -> response = subredditName?.let {
                                if (accountName == Account.ANONYMOUS_ACCOUNT) {
                                    api.getSubredditBestPosts(
                                        subredditName, listingSort, sortTime, afterKey,
                                        requestLimit
                                    )
                                } else {
                                    api.getSubredditBestPostsOauth(
                                        subredditName, listingSort,
                                        sortTime, afterKey, requestLimit,
                                        APIUtils.getOAuthHeader(accessToken)
                                    )
                                }
                            }

                            PostType.USER -> response = username?.let {
                                if (accountName == Account.ANONYMOUS_ACCOUNT) {
                                    api.getUserPosts(username, afterKey, listingSort, sortTime)
                                } else {
                                    userWhere?.let {
                                        api.getUserPostsOauth(
                                            username, userWhere, afterKey, listingSort,
                                            sortTime, APIUtils.getOAuthHeader(accessToken)
                                        )
                                    }
                                }
                            }

                            PostType.SEARCH -> response = if (subredditName == null) {
                                if (accountName == Account.ANONYMOUS_ACCOUNT) {
                                    api.searchPosts(
                                        query, afterKey, listingSort, sortTime
                                    )
                                } else {
                                    api.searchPostsOauth(
                                        query, afterKey, listingSort,
                                        sortTime, APIUtils.getOAuthHeader(accessToken)
                                    )
                                }
                            } else {
                                if (accountName == Account.ANONYMOUS_ACCOUNT) {
                                    api.searchPostsInSpecificSubreddit(
                                        subredditName, query,
                                        listingSort, sortTime, afterKey
                                    )
                                } else {
                                    api.searchPostsInSpecificSubredditOauth(
                                        subredditName, query,
                                        listingSort, sortTime, afterKey,
                                        APIUtils.getOAuthHeader(accessToken)
                                    )
                                }
                            }

                            PostType.MULTIREDDIT -> response = multiPath?.let {
                                if (accountName == Account.ANONYMOUS_ACCOUNT) {
                                    api.getMultiRedditPosts(multiPath, afterKey, sortTime)
                                } else {
                                    api.getMultiRedditPostsOauth(
                                        multiPath, afterKey,
                                        sortTime, APIUtils.getOAuthHeader(accessToken)
                                    )
                                }
                            }

                            PostType.ANONYMOUS_FRONT_PAGE, PostType.ANONYMOUS_MULTIREDDIT -> response = sourceSubreddits?.let {
                                api.getAnonymousFrontPageOrMultiredditPosts(
                                    sourceSubreddits, listingSort,
                                    sortTime, afterKey, requestLimit,
                                    APIUtils.ANONYMOUS_USER_AGENT
                                )
                            }

                            else -> response = api.getBestPosts(
                                listingSort, sortTime, afterKey,
                                APIUtils.getOAuthHeader(accessToken), requestLimit
                            )
                        }
                    }

                    if (response?.isSuccessful != true) {
                        publishLoadState(LoadingMorePostsStatus.FAILED)
                        return@launch
                    }
                    val page = finalizePosts(response, postFilter, mediaOnly, readPostsList, generation)
                    if (postType == PostType.READ_POSTS) {
                        lastReadPostTime = historyCursor
                        listingExhausted = historyCursor == historyCursorBefore
                    } else {
                        lastListingCursor = page.cursor
                        listingExhausted = page.cursor == null || page.cursor == afterKey
                    }
                    barrenPages++
                    if (page.added > 0 || listingExhausted || barrenPages >= MAX_BARREN_SWIPE_PAGES) {
                        // A filtered batch is still a successful page. Only the upstream cursor
                        // determines exhaustion; callers can continue when no eligible items remain.
                        completeBatch(page.added, changePage)
                        return@launch
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (generation == requestGeneration) {
                    e.printStackTrace()
                    publishLoadState(LoadingMorePostsStatus.FAILED)
                }
            }
        }
    }

    private fun parsePostsSync(
        response: String?,
        postFilter: PostFilter?,
        mediaOnly: Boolean,
        readPostsList: ReadPostsListInterface?
    ): ParsedPostPage? {
        val newPosts = ArrayList<Post>()
        try {
            val jsonResponse = JSONObject(response ?: "")
            val allPostsData =
                jsonResponse.getJSONObject(JSONUtils.DATA_KEY).getJSONArray(JSONUtils.CHILDREN_KEY)

            val numberOfPosts = allPostsData.length()

            val newPostsIds = java.util.ArrayList<String>()
            for (i in 0..<numberOfPosts) {
                try {
                    if (allPostsData.getJSONObject(i).getString(JSONUtils.KIND_KEY) != "t3") {
                        continue
                    }
                    val data = allPostsData.getJSONObject(i).getJSONObject(JSONUtils.DATA_KEY)
                    val post = ParsePost.parseBasicData(data)
                    // mediaOnly is the gallery feed's "Media Posts Only" setting, carried over so
                    // swiping past the posts the feed handed us does not start turning up the text
                    // and link posts it was hiding (issue #377).
                    if (PostFilter.isPostAllowed(post, postFilter) && (!mediaOnly || post.isMediaPost)) {
                        newPosts.add(post)
                        newPostsIds.add(post.id)
                    }
                } catch (e: JSONException) {
                    e.printStackTrace()
                }
            }

            if (readPostsList != null) {
                val readPostsIds = readPostsList.getReadPostsIdsByIds(newPostsIds)
                for (post in newPosts) {
                    if (readPostsIds.contains(post.id)) {
                        post.markAsRead()
                    }
                }
            }

            return ParsedPostPage(newPosts, ParsePost.getLastItem(jsonResponse))
        } catch (e: JSONException) {
            e.printStackTrace()
            return null
        }
    }

    /** Parse off the UI thread, then append only if this request still owns the source. */
    private suspend fun finalizePosts(
        response: Response<String>,
        postFilter: PostFilter?,
        mediaOnly: Boolean,
        readPostsList: ReadPostsListInterface?,
        generation: Long,
    ): AppendedPostPage {
        val page = withContext(Dispatchers.Default) {
            parsePostsSync(response.body(), postFilter, mediaOnly, readPostsList)
        } ?: throw JSONException("Invalid Reddit listing response")
        currentCoroutineContext().ensureActive()
        if (generation != requestGeneration) {
            throw CancellationException("Feed source changed")
        }
        val target = posts ?: ArrayList<Post>().also { posts = it }
        val oldSize = target.size
        val existingIds = target.mapTo(mutableSetOf()) { it.id }
        for (post in page.posts) {
            if (existingIds.add(post.id)) target.add(post)
        }
        return AppendedPostPage(target.size - oldSize, page.cursor)
    }

    private data class ParsedPostPage(val posts: ArrayList<Post>, val cursor: String?)

    private data class AppendedPostPage(val added: Int, val cursor: String?)

    companion object {
        /**
         * How many pages the swipe list may pull while every one of them is filtered away to
         * nothing, before yielding to the UI. The upstream cursor still controls exhaustion.
         */
        private const val MAX_BARREN_SWIPE_PAGES = 5

        fun provideFactory(
            retrofit: Retrofit,
            oauthRetrofit: Retrofit,
            redditDataRoomDatabase: RedditDataRoomDatabase,
            accessToken: String?,
            loader: UserProfileImagesBatchLoader
        ): ViewModelProvider.Factory {
            return object: ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(
                    modelClass: Class<T>,
                    extras: CreationExtras
                ): T {
                    return ViewPostDetailActivityViewModel(retrofit, oauthRetrofit, redditDataRoomDatabase, accessToken, loader) as T
                }
            }
        }
    }
}
