package ml.docilealligator.infinityforreddit.shadowbox

import android.content.SharedPreferences
import android.graphics.Color
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.github.piasy.biv.BigImageViewer
import ml.docilealligator.infinityforreddit.network.ForegroundGlideImageLoader
import ml.docilealligator.infinityforreddit.ImageOkHttpClient
import ml.docilealligator.infinityforreddit.Infinity
import ml.docilealligator.infinityforreddit.R
import ml.docilealligator.infinityforreddit.RedditDataRoomDatabase
import ml.docilealligator.infinityforreddit.FetchPostFilterAndConcatenatedSubredditNames
import ml.docilealligator.infinityforreddit.account.AccountScope
import ml.docilealligator.infinityforreddit.activities.BaseActivity
import ml.docilealligator.infinityforreddit.bottomsheetfragments.SortTimeBottomSheetFragment
import ml.docilealligator.infinityforreddit.bottomsheetfragments.SortTypeBottomSheetFragment
import ml.docilealligator.infinityforreddit.customtheme.CustomThemeWrapper
import ml.docilealligator.infinityforreddit.databinding.ActivityShadowboxBinding
import ml.docilealligator.infinityforreddit.events.PostUpdateEventToPostList
import ml.docilealligator.infinityforreddit.events.SwitchAccountEvent
import ml.docilealligator.infinityforreddit.post.LoadingMorePostsStatus
import ml.docilealligator.infinityforreddit.post.Post
import ml.docilealligator.infinityforreddit.post.PostType
import ml.docilealligator.infinityforreddit.postfilter.PostFilterUsage
import ml.docilealligator.infinityforreddit.readpost.ReadPostModification
import ml.docilealligator.infinityforreddit.readpost.ReadPostType
import ml.docilealligator.infinityforreddit.readpost.ReadPostsList
import ml.docilealligator.infinityforreddit.readpost.ReadPostsUtils
import ml.docilealligator.infinityforreddit.thing.SortType
import ml.docilealligator.infinityforreddit.thing.SortTypeSelectionCallback
import ml.docilealligator.infinityforreddit.user.UserProfileImagesBatchLoader
import ml.docilealligator.infinityforreddit.utils.SharedPreferencesUtils
import ml.docilealligator.infinityforreddit.viewmodels.ViewGalleryViewModel
import ml.docilealligator.infinityforreddit.viewmodels.ViewPostDetailActivityViewModel
import ml.docilealligator.infinityforreddit.viewmodels.PostFeedRequest
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import retrofit2.Retrofit
import java.util.concurrent.Executor
import javax.inject.Inject
import javax.inject.Named

/** A fullscreen post pager that owns its feed request and shows one post per page. */
class ShadowboxActivity : BaseActivity(), SortTypeSelectionCallback {

    @Inject
    @Named("no_oauth")
    lateinit var retrofit: Retrofit

    @Inject
    @Named("oauth")
    lateinit var oauthRetrofit: Retrofit

    @Inject
    lateinit var redditDataRoomDatabase: RedditDataRoomDatabase

    @Inject
    @Named("default")
    lateinit var sharedPreferences: SharedPreferences

    @Inject
    @Named("current_account")
    lateinit var mCurrentAccountSharedPreferences: SharedPreferences

    @Inject
    @Named("post_history")
    lateinit var postHistorySharedPreferences: SharedPreferences

    @Inject
    @Named("nsfw_and_spoiler")
    lateinit var nsfwAndSpoilerSharedPreferences: SharedPreferences

    @Inject
    lateinit var mCustomThemeWrapper: CustomThemeWrapper

    @Inject
    lateinit var executor: Executor

    @Inject
    lateinit var loader: UserProfileImagesBatchLoader

    lateinit var viewModel: ViewPostDetailActivityViewModel
        private set
    lateinit var insetsViewModel: ViewGalleryViewModel
        private set

    private lateinit var binding: ActivityShadowboxBinding
    private var adapter: ShadowboxPagerAdapter? = null
    var feedGeneration = 0L
        private set
    private var lastHandledBatchId = Long.MIN_VALUE
    private val excludedSoundOnlyPostIds = mutableSetOf<String>()
    private var restorePostFullName: String? = null

    /** Index of the page the pager is on; only that page plays media. */
    val currentPage = MutableLiveData<Int>()

    /** Whether the info panel (and the system bars) are shown; pages fade their panel on it. */
    val panelVisible = MutableLiveData(true)

    var isNsfwSubreddit = false
        private set
    var isTikTokWithSound = false
        private set
    private var chromeVisible = true
    private var markPostsAsRead = false
    /** Sound level chosen during this Shadowbox session, shared by its video pages. */
    var playbackVolume: Float? = null
    var lastAudibleVolume = 1f
    private var hideTextAndPreviewlessPosts = false

    override fun onCreate(savedInstanceState: Bundle?) {
        (application as Infinity).appComponent.inject(this)
        // The pager's saved FragmentStateAdapter IDs refer to its old post-index fragments. The
        // feed ViewModel and the selected post are restored separately below.
        super.onCreate(null)

        setUpWindow()

        // Image pages inflate a BigImageView, which takes the loader installed here.
        BigImageViewer.initialize(
            ForegroundGlideImageLoader.with(applicationContext, ImageOkHttpClient.get(applicationContext))
        )

        binding = ActivityShadowboxBinding.inflate(layoutInflater)
        binding.viewPager2ShadowboxActivity.isSaveEnabled = false
        binding.viewPager2ShadowboxActivity.isSaveFromParentEnabled = false
        setContentView(binding.root)
        binding.closeShadowbox.setOnClickListener { finish() }
        binding.sortShadowbox.setOnClickListener { showSortTypeBottomSheet() }
        binding.sortShadowbox.isEnabled = false
        volumeControlStream = AudioManager.STREAM_MUSIC

        markPostsAsRead = postHistorySharedPreferences.getBoolean(
            AccountScope.key(accountName, SharedPreferencesUtils.MARK_POSTS_AS_READ_BASE), false
        )
        hideTextAndPreviewlessPosts = sharedPreferences.getBoolean(
            SharedPreferencesUtils.SHADOWBOX_HIDE_TEXT_AND_PREVIEWLESS_POSTS, false
        )

        isNsfwSubreddit = intent.getBooleanExtra(EXTRA_IS_NSFW_SUBREDDIT, false)
        isTikTokWithSound = intent.getBooleanExtra(EXTRA_TIKTOK_WITH_SOUND, false)
        savedInstanceState?.getStringArrayList(STATE_EXCLUDED_SOUND_ONLY_POST_IDS)
            ?.let(excludedSoundOnlyPostIds::addAll)
        restorePostFullName = savedInstanceState?.getString(STATE_CURRENT_POST_FULL_NAME)
        savedInstanceState?.let { state ->
            if (state.containsKey(STATE_PLAYBACK_VOLUME)) playbackVolume = state.getFloat(STATE_PLAYBACK_VOLUME)
            if (state.containsKey(STATE_LAST_AUDIBLE_VOLUME)) lastAudibleVolume = state.getFloat(STATE_LAST_AUDIBLE_VOLUME)
            if (state.containsKey(STATE_PANEL_VISIBLE)) {
                chromeVisible = state.getBoolean(STATE_PANEL_VISIBLE)
                panelVisible.value = chromeVisible
            }
        }
        val systemBarsController = WindowCompat.getInsetsController(window, window.decorView)
        if (chromeVisible) {
            systemBarsController.show(WindowInsetsCompat.Type.systemBars())
        } else {
            systemBarsController.hide(WindowInsetsCompat.Type.systemBars())
        }

        viewModel = ViewModelProvider(
            this,
            ViewPostDetailActivityViewModel.provideFactory(
                retrofit, oauthRetrofit, redditDataRoomDatabase, accessToken, loader
            )
        )[ViewPostDetailActivityViewModel::class.java]
        insetsViewModel = ViewModelProvider(this)[ViewGalleryViewModel::class.java]

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            binding.closeShadowbox.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = bars.top + (8 * resources.displayMetrics.density).toInt()
                leftMargin = bars.left + (8 * resources.displayMetrics.density).toInt()
            }
            binding.sortShadowbox.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = bars.top + (8 * resources.displayMetrics.density).toInt()
                rightMargin = bars.right + (8 * resources.displayMetrics.density).toInt()
            }
            // Ignoring visibility keeps the panel's padding the same whether the bars are shown or
            // hidden, so toggling the chrome never moves it.
            insetsViewModel.setInsets(
                insets.getInsetsIgnoringVisibility(
                    WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
                )
            )
            WindowInsetsCompat.CONSUMED
        }

        EventBus.getDefault().register(this)

        val hasExistingFeed = viewModel.currentFeedRequest != null
        val intentFeedRequest = feedRequestFromIntent()?.let { request ->
            if (savedInstanceState?.getBoolean(STATE_HAS_SORT_STATE) != true) {
                request
            } else {
                val restoredSortType = savedInstanceState.getString(STATE_SORT_TYPE)
                    ?.let { name -> SortType.Type.values().firstOrNull { it.name == name } }
                    ?: request.sortType
                val restoredSortTime = savedInstanceState.getString(STATE_SORT_TIME)
                    ?.let { name -> SortType.Time.values().firstOrNull { it.name == name } }
                request.copy(sortType = restoredSortType, sortTime = restoredSortTime)
            }
        }
        if (hasExistingFeed) {
            // A recreated activity keeps its independent request and already loaded list.
            feedGeneration = 1L
            lastHandledBatchId = viewModel.loadMorePostsState.value?.batchId ?: Long.MIN_VALUE
        } else if (intentFeedRequest != null) {
            startFeedWithProfileFilter(intentFeedRequest)
        } else {
            Toast.makeText(this, R.string.shadowbox_no_posts, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val adapter = ShadowboxPagerAdapter(this, viewModel, ::shouldBlur, ::shouldShowPost)
        adapter.buildPages()
        this.adapter = adapter
        // Vertical gestures navigate posts here. Keep this screen free of the media viewers'
        // swipe-to-dismiss wrapper, regardless of the global vertical-dismiss preference.
        binding.viewPager2ShadowboxActivity.orientation = ViewPager2.ORIENTATION_VERTICAL
        binding.viewPager2ShadowboxActivity.adapter = adapter
        // Build the page either side of the one in front, rather than when the swipe starts.
        //
        // This is what stops a page from drawing its picture in place after the swipe has landed.
        // A pager left at its default builds the next page during the drag, so its images are
        // fetched with a frame or two to spare, and a gallery -- three full-width tiles, fifteen
        // megabytes decoded -- cannot be held in Glide's memory cache across two neighbours to
        // make up for it. A page built early has its pictures in its own views, which no cache
        // evicts. The cost is that a video page either side prepares its player, the same trade
        // the feed makes for autoplay.
        binding.viewPager2ShadowboxActivity.offscreenPageLimit = 1
        val posts = viewModel.posts
        val restoredPostIndex = if (hasExistingFeed && restorePostFullName != null) {
            posts?.indexOfFirst { it.fullName == restorePostFullName } ?: -1
        } else {
            -1
        }
        if (restoredPostIndex >= 0) restorePostFullName = null
        val launchPostIndex = restoredPostIndex.takeIf { it >= 0 } ?: 0
        val launchPage = if (posts.isNullOrEmpty()) 0 else adapter.pageForPostIndex(launchPostIndex)
        binding.viewPager2ShadowboxActivity.setCurrentItem(launchPage, false)
        binding.viewPager2ShadowboxActivity.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                onPageShown(position)
            }
        })
        // setCurrentItem(launch, false) before the callback was registered dispatched nothing to
        // it, so the launch page gets its side effects by hand.
        onPageShown(launchPage)

        viewModel.loadMorePostsState.observe(this) { state ->
            binding.sortShadowbox.isEnabled = viewModel.currentFeedRequest != null
            onLoadMoreState(state)
        }
    }

    private fun setUpWindow() {
        val window = window
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // BaseActivity applied the theme's bar colours and, on Android 15+, a decor listener that
        // paints the primary colour behind a toolbar. This screen is black edge to edge.
        ViewCompat.setOnApplyWindowInsetsListener(window.decorView, null)
        window.decorView.setBackgroundColor(Color.BLACK)
        @Suppress("DEPRECATION")
        window.statusBarColor = Color.TRANSPARENT
        @Suppress("DEPRECATION")
        window.navigationBarColor = Color.TRANSPARENT
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.isAppearanceLightStatusBars = false
        controller.isAppearanceLightNavigationBars = false
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    /** [page] indexes the pager; the post it shows may sit further along the list when filtering. */
    private fun onPageShown(page: Int) {
        val adapter = this.adapter ?: return
        val postIndex = adapter.postIndexForPage(page)
        currentPage.value = postIndex
        if (adapter.pageCount - page <= PREFETCH_LEAD_PAGES) {
            fetchMorePosts()
        }
        stopPlaybackExcept(postIndex)
        val posts = viewModel.posts ?: return
        if (postIndex in posts.indices) {
            markPostRead(posts[postIndex], postIndex)
        }
    }

    /**
     * Stops every page except the one in front. Driven from here rather than left to each page's
     * own page-change observer: only one page may play at a time, and that has to hold even if a
     * page never hears that it is no longer current.
     */
    private fun stopPlaybackExcept(postIndex: Int) {
        for (fragment in supportFragmentManager.fragments) {
            if (fragment is ShadowboxPageFragment && fragment.position != postIndex) {
                fragment.pausePlayback()
            }
        }
    }

    /** Rebuilds the standalone feed request from the small source descriptor in the Intent. */
    private fun feedRequestFromIntent(): PostFeedRequest? {
        if (!intent.hasExtra(EXTRA_FEED_POST_TYPE)) return null
        val postType = intent.getIntExtra(EXTRA_FEED_POST_TYPE, PostType.FRONT_PAGE)
        val sortType = intent.getStringExtra(EXTRA_FEED_SORT_TYPE)
            ?.let { name -> SortType.Type.values().firstOrNull { it.name == name } }
            ?: SortType.Type.HOT
        val sortTime = intent.getStringExtra(EXTRA_FEED_SORT_TIME)
            ?.let { name -> SortType.Time.values().firstOrNull { it.name == name } }
        return PostFeedRequest(
            postType = postType,
            accountName = accountName,
            accessToken = accessToken,
            subredditName = intent.getStringExtra(EXTRA_FEED_SUBREDDIT_NAME),
            concatenatedSubredditNames = if (
                postType == PostType.ANONYMOUS_FRONT_PAGE || postType == PostType.ANONYMOUS_MULTIREDDIT
            ) null else intent.getStringExtra(EXTRA_FEED_CONCATENATED_SUBREDDIT_NAMES),
            username = intent.getStringExtra(EXTRA_FEED_USERNAME),
            userWhere = intent.getStringExtra(EXTRA_FEED_USER_WHERE),
            multiPath = intent.getStringExtra(EXTRA_FEED_MULTI_PATH),
            query = intent.getStringExtra(EXTRA_FEED_QUERY),
            sortType = sortType,
            sortTime = sortTime,
            readPostType = intent.getIntExtra(EXTRA_FEED_READ_POST_TYPE, ReadPostType.READ_POSTS),
            readPostsList = ReadPostsList(
                redditDataRoomDatabase.readPostDao(),
                accountName,
                intent.getBooleanExtra(EXTRA_FEED_DISABLE_READ_POSTS, false),
            ),
            mediaOnly = isTikTokWithSound || intent.getBooleanExtra(EXTRA_FEED_MEDIA_ONLY, false),
        )
    }

    private fun showSortTypeBottomSheet() {
        val request = viewModel.currentFeedRequest ?: return
        val selectedSort = SortType(request.sortType, request.sortTime)
        val sortSheet = SortTypeBottomSheetFragment.getNewInstance(
            request.postType != PostType.FRONT_PAGE,
            selectedSort,
        )
        sortSheet.show(supportFragmentManager, sortSheet.tag)
    }

    override fun sortTypeSelected(sortType: SortType) {
        val request = viewModel.currentFeedRequest ?: return
        intent.putExtra(EXTRA_FEED_SORT_TYPE, sortType.type.name)
        val selectedSortTime = sortType.time
        if (selectedSortTime != null) {
            intent.putExtra(EXTRA_FEED_SORT_TIME, selectedSortTime.name)
        } else {
            intent.removeExtra(EXTRA_FEED_SORT_TIME)
        }
        val updatedRequest = request.copy(sortType = sortType.type, sortTime = sortType.time)
        startFeedWithProfileFilter(updatedRequest)
        adapter?.let { pagerAdapter ->
            pagerAdapter.buildPages()
            pagerAdapter.notifyDataSetChanged()
            binding.viewPager2ShadowboxActivity.setCurrentItem(0, false)
            onPageShown(0)
        }
    }

    override fun sortTypeSelected(sortType: String) {
        val timeSheet = SortTimeBottomSheetFragment().apply {
            arguments = Bundle().apply {
                putString(SortTimeBottomSheetFragment.EXTRA_SORT_TYPE, sortType)
            }
        }
        timeSheet.show(supportFragmentManager, timeSheet.tag)
    }

    private fun startFeedWithProfileFilter(request: PostFeedRequest) {
        val generation = ++feedGeneration
        lastHandledBatchId = Long.MIN_VALUE

        val allowNSFW = nsfwAndSpoilerSharedPreferences.getBoolean(
            AccountScope.key(accountName, SharedPreferencesUtils.NSFW_BASE), false
        )
        request.postFilter?.let {
            it.allowNSFW = allowNSFW
            viewModel.startFeed(request)
            return
        }

        val (usage, name) = postFilterUsage(request)
        FetchPostFilterAndConcatenatedSubredditNames.fetchPostFilter(
            redditDataRoomDatabase,
            executor,
            Handler(Looper.getMainLooper()),
            usage,
            name,
        ) { postFilter ->
            if (isFinishing || isDestroyed || generation != feedGeneration) return@fetchPostFilter
            postFilter.allowNSFW = allowNSFW
            viewModel.startFeed(request.copy(postFilter = postFilter))
        }
    }

    private fun postFilterUsage(request: PostFeedRequest): Pair<Int, String?> = when (request.postType) {
        PostType.SUBREDDIT -> PostFilterUsage.SUBREDDIT_TYPE to request.subredditName
        PostType.USER -> PostFilterUsage.USER_TYPE to request.username
        PostType.SEARCH -> PostFilterUsage.SEARCH_TYPE to PostFilterUsage.NO_USAGE
        PostType.MULTIREDDIT, PostType.ANONYMOUS_MULTIREDDIT ->
            PostFilterUsage.MULTIREDDIT_TYPE to request.multiPath
        else -> PostFilterUsage.HOME_TYPE to PostFilterUsage.NO_USAGE
    }

    fun fetchMorePosts() {
        if (isFinishing || isDestroyed || viewModel.currentFeedRequest == null) return
        val state = viewModel.loadMorePostsState.value ?: return
        if (state.status == LoadingMorePostsStatus.LOADING || !state.hasMore) return
        viewModel.loadNextFeedPage()
    }

    fun isSoundOnlyFeedEmpty(): Boolean = isTikTokWithSound && (adapter?.pageCount == 0)

    private fun onLoadMoreState(state: ViewPostDetailActivityViewModel.LoadMorePostsState) {
        if (isFinishing || isDestroyed || state.status != LoadingMorePostsStatus.LOADED) {
            return
        }
        if (state.batchId == lastHandledBatchId) return
        val generation = feedGeneration
        val recyclerView = binding.viewPager2ShadowboxActivity.getChildAt(0) as RecyclerView
        if (recyclerView.isComputingLayout) {
            recyclerView.post {
                if (generation == feedGeneration) onLoadMoreState(state)
            }
            return
        }
        val adapter = this.adapter ?: return
        lastHandledBatchId = state.batchId
        val oldPageCount = adapter.pageCount
        val wasOnEndPage = binding.viewPager2ShadowboxActivity.currentItem >= oldPageCount
        val added = adapter.appendPages()
        if (restoreSelectedPostIfAvailable(state, adapter)) return
        if (added == 0) {
            // Filters can consume any number of complete batches. Only the loader's exhaustion
            // signal ends the search for visible pages, and fetchMorePosts keeps this one-at-a-time.
            if (state.hasMore) fetchMorePosts()
            return
        }
        if (wasOnEndPage) {
            // Parked on the end page while it loaded: show the first post that arrived.
            binding.viewPager2ShadowboxActivity.post {
                if (!isFinishing && !isDestroyed && generation == feedGeneration) {
                    binding.viewPager2ShadowboxActivity.setCurrentItem(oldPageCount, false)
                    // Replacing the footer can keep the same numeric position and therefore
                    // emit no onPageSelected callback. Activate the arriving post explicitly.
                    onPageShown(oldPageCount)
                }
            }
        }
    }

    private fun restoreSelectedPostIfAvailable(
        state: ViewPostDetailActivityViewModel.LoadMorePostsState,
        adapter: ShadowboxPagerAdapter,
    ): Boolean {
        val fullName = restorePostFullName ?: return false
        val posts = viewModel.posts ?: return false
        val postIndex = posts.indexOfFirst { it.fullName == fullName }
        if (postIndex < 0) {
            if (state.hasMore) {
                movePagerToPageWhenReady(adapter.pageCount)
                return true
            }
            restorePostFullName = null
            movePagerToPageWhenReady(0)
            return true
        }

        val page = firstPageAtOrAfterPostIndex(postIndex)
        if (page >= adapter.pageCount && state.hasMore) {
            movePagerToPageWhenReady(adapter.pageCount)
            return true
        }
        restorePostFullName = null
        movePagerToPageWhenReady(page)
        return true
    }

    private fun movePagerToPageWhenReady(page: Int) {
        val moveToPage = object : Runnable {
            override fun run() {
                if (isFinishing || isDestroyed) return
                val pager = binding.viewPager2ShadowboxActivity
                val recyclerView = pager.getChildAt(0) as? RecyclerView
                if (recyclerView?.isComputingLayout == true) {
                    recyclerView.post(this)
                    return
                }
                val target = page.coerceAtMost(adapter?.pageCount ?: 0)
                pager.setCurrentItem(target, false)
                onPageShown(target)
            }
        }
        binding.viewPager2ShadowboxActivity.post(moveToPage)
    }

    /**
     * Whether this mode shows the post at all: everything, unless "hide text posts and posts with
     * no preview" is on, in which case a post has to be something other than a text post and have
     * a preview image.
     */
    private fun shouldShowPost(post: Post): Boolean {
        if (isTikTokWithSound) {
            return isPlayableClipCandidate(post) && post.fullName !in excludedSoundOnlyPostIds
        }
        return !hideTextAndPreviewlessPosts || ShadowboxPreviews.hasPreviewToShow(post)
    }

    private fun isPlayableClipCandidate(post: Post): Boolean = when (post.postType) {
        Post.VIDEO_TYPE -> post.videoUrl != null &&
            (!(post.isStreamable || post.isShortClip) || post.isLoadedStreamableVideoAlready)
        Post.GIF_TYPE -> post.mp4Variant != null
        else -> false
    }

    /** Called only after Media3 reports a ready, supported track set for this exact post. */
    fun onSoundOnlyAudioAvailability(post: Post, hasPlayableAudio: Boolean, sourceGeneration: Long) {
        if (!isTikTokWithSound || isFinishing || isDestroyed ||
            sourceGeneration != feedGeneration || hasPlayableAudio
        ) return
        excludeSoundOnlyPost(post, sourceGeneration)
    }

    /** A confirmed playback failure after all known URL fallbacks cannot play in this feed. */
    fun onSoundOnlyPostUnplayable(post: Post, sourceGeneration: Long) {
        if (!isTikTokWithSound || isFinishing || isDestroyed || sourceGeneration != feedGeneration) return
        excludeSoundOnlyPost(post, sourceGeneration)
    }

    private fun excludeSoundOnlyPost(post: Post, sourceGeneration: Long) {
        val posts = viewModel.posts ?: return
        val excludedPostIndex = posts.indexOfFirst { it.fullName == post.fullName }
        if (excludedPostIndex < 0 || !excludedSoundOnlyPostIds.add(post.fullName)) return

        val applyExclusion = object : Runnable {
            override fun run() {
                if (isFinishing || isDestroyed || sourceGeneration != feedGeneration) return
                val recyclerView = binding.viewPager2ShadowboxActivity.getChildAt(0) as? RecyclerView
                if (recyclerView?.isComputingLayout == true) {
                    recyclerView.post(this)
                    return
                }
                val currentAdapter = adapter ?: return
                val currentPostIndex = currentAdapter.postIndexForPage(
                    binding.viewPager2ShadowboxActivity.currentItem
                )
                currentAdapter.buildPages()
                currentAdapter.notifyDataSetChanged()

                val targetPage = when {
                    currentPostIndex < 0 -> currentAdapter.pageCount
                    currentPostIndex == excludedPostIndex -> firstPageAtOrAfterPostIndex(excludedPostIndex + 1)
                    else -> firstPageAtOrAfterPostIndex(currentPostIndex)
                }
                val moveToTarget = object : Runnable {
                    override fun run() {
                        if (isFinishing || isDestroyed || sourceGeneration != feedGeneration) return
                        val pagerRecyclerView = binding.viewPager2ShadowboxActivity.getChildAt(0) as? RecyclerView
                        if (pagerRecyclerView?.isComputingLayout == true) {
                            pagerRecyclerView.post(this)
                            return
                        }
                        if (adapter !== currentAdapter) return
                        binding.viewPager2ShadowboxActivity.setCurrentItem(targetPage, false)
                        onPageShown(targetPage)
                    }
                }
                binding.viewPager2ShadowboxActivity.post(moveToTarget)
            }
        }
        applyExclusion.run()
    }

    private fun firstPageAtOrAfterPostIndex(postIndex: Int): Int {
        val adapter = this.adapter ?: return 0
        for (page in 0 until adapter.pageCount) {
            if (adapter.postIndexForPage(page) >= postIndex) return page
        }
        return adapter.pageCount
    }

    /**
     * Marks the post read the way opening it from the feed would, under the same "mark posts as
     * read" setting, and tells the feed row to recolour.
     */
    private fun markPostRead(post: Post, position: Int) {
        if (post.isRead || !markPostsAsRead) {
            return
        }
        markRead(post, position)
    }

    /** The panel's read-after-voting path; that setting is checked by the panel itself. */
    fun markPostReadAfterVoting(post: Post, position: Int) {
        if (post.isRead) {
            return
        }
        markRead(post, position)
    }

    private fun markRead(post: Post, position: Int) {
        post.markAsRead()
        ReadPostModification.insertReadPost(
            redditDataRoomDatabase, executor, accountName, post.id, ReadPostType.READ_POSTS,
            ReadPostsUtils.GetReadPostsLimit(accountName, postHistorySharedPreferences)
        )
        EventBus.getDefault().post(PostUpdateEventToPostList(post, position))
    }

    private fun shouldBlur(post: Post): Boolean {
        val needBlurNsfw = nsfwAndSpoilerSharedPreferences.getBoolean(
            AccountScope.key(accountName, SharedPreferencesUtils.BLUR_NSFW_BASE), true
        )
        val doNotBlurNsfwInNsfwSubreddits = nsfwAndSpoilerSharedPreferences.getBoolean(
            AccountScope.key(accountName, SharedPreferencesUtils.DO_NOT_BLUR_NSFW_IN_NSFW_SUBREDDITS), false
        )
        val needBlurSpoiler = nsfwAndSpoilerSharedPreferences.getBoolean(
            AccountScope.key(accountName, SharedPreferencesUtils.BLUR_SPOILER_BASE), false
        )
        return (post.isNSFW && needBlurNsfw && !(doNotBlurNsfwInNsfwSubreddits && isNsfwSubreddit))
                || (post.isSpoiler && needBlurSpoiler)
    }

    /** Shows or hides the info panel together with the system bars. */
    fun toggleChrome() {
        chromeVisible = !chromeVisible
        panelVisible.value = chromeVisible
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (chromeVisible) {
            controller.show(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    /** The page fragment showing [position], if the pager has one built. */
    private fun pageAt(position: Int): ShadowboxPageFragment? {
        for (fragment in supportFragmentManager.fragments) {
            if (fragment is ShadowboxPageFragment && fragment.position == position) {
                return fragment
            }
        }
        return null
    }

    /**
     * A vote, save, hide or moderation action from the comments screen or the options sheet. The
     * comments screen sends a copy of the post rather than ours, so the fields are copied over
     * before the page's panel is redrawn.
     */
    @Subscribe
    fun onPostUpdateEvent(event: PostUpdateEventToPostList) {
        val posts = viewModel.posts ?: return
        val index = posts.indexOfFirst { it.fullName == event.post.fullName }
        if (index < 0) {
            return
        }
        val post = posts[index]
        if (post !== event.post) {
            post.voteType = event.post.voteType
            post.score = event.post.score
            post.nComments = event.post.nComments
            post.isSaved = event.post.isSaved
            post.isHidden = event.post.isHidden
            if (event.post.isRead) {
                post.markAsRead()
            }
        }
        pageAt(index)?.rebindPanel()
    }

    @Subscribe
    fun onAccountSwitchEvent(event: SwitchAccountEvent) {
        if (javaClass.name != event.excludeActivityClassName) {
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        for (fragment in supportFragmentManager.fragments) {
            if (fragment is ShadowboxPageFragment) {
                fragment.restoreMedia()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        // Leaving the screen -- the full viewer, the comments, anywhere -- silences the pages that
        // are not in front: the page in front remembers to resume on the way back; these have
        // nothing to come back to. Then every page lets go of its player, so the screen opening
        // on top finds the decoders free; see ShadowboxPageFragment.releaseMedia.
        stopPlaybackExcept(currentPage.value ?: -1)
        for (fragment in supportFragmentManager.fragments) {
            if (fragment is ShadowboxPageFragment) {
                fragment.releaseMedia()
            }
        }
    }

    override fun onDestroy() {
        if (isFinishing && ::viewModel.isInitialized) {
            viewModel.stopFeedLoading()
        }
        EventBus.getDefault().unregister(this)
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArrayList(STATE_EXCLUDED_SOUND_ONLY_POST_IDS, ArrayList(excludedSoundOnlyPostIds))
        val selectedPostIndex = adapter?.postIndexForPage(binding.viewPager2ShadowboxActivity.currentItem) ?: -1
        viewModel.posts?.getOrNull(selectedPostIndex)?.fullName?.let {
            outState.putString(STATE_CURRENT_POST_FULL_NAME, it)
        }
        playbackVolume?.let { outState.putFloat(STATE_PLAYBACK_VOLUME, it) }
        outState.putFloat(STATE_LAST_AUDIBLE_VOLUME, lastAudibleVolume)
        outState.putBoolean(STATE_PANEL_VISIBLE, panelVisible.value != false)
        viewModel.currentFeedRequest?.let { request ->
            outState.putBoolean(STATE_HAS_SORT_STATE, true)
            outState.putString(STATE_SORT_TYPE, request.sortType.name)
            request.sortTime?.let { outState.putString(STATE_SORT_TIME, it.name) }
        }
    }

    override fun getDefaultSharedPreferences(): SharedPreferences = sharedPreferences

    override fun getCurrentAccountSharedPreferences(): SharedPreferences = mCurrentAccountSharedPreferences

    override fun getCustomThemeWrapper(): CustomThemeWrapper = mCustomThemeWrapper

    override fun applyCustomTheme() {
        // Black edge to edge; nothing here takes a theme colour.
    }

    companion object {
        const val EXTRA_IS_NSFW_SUBREDDIT = "EINS"
        const val EXTRA_TIKTOK_WITH_SOUND = "ETS"
        const val EXTRA_FEED_POST_TYPE = "EFPT"
        const val EXTRA_FEED_SUBREDDIT_NAME = "EFSN"
        const val EXTRA_FEED_MULTI_PATH = "EFMP"
        const val EXTRA_FEED_CONCATENATED_SUBREDDIT_NAMES = "EFCSN"
        const val EXTRA_FEED_USERNAME = "EFUN"
        const val EXTRA_FEED_USER_WHERE = "EFUW"
        const val EXTRA_FEED_QUERY = "EFQ"
        const val EXTRA_FEED_SORT_TYPE = "EFST"
        const val EXTRA_FEED_SORT_TIME = "EFSTM"
        const val EXTRA_FEED_READ_POST_TYPE = "EFRPT"
        const val EXTRA_FEED_MEDIA_ONLY = "EFMO"
        const val EXTRA_FEED_DISABLE_READ_POSTS = "EFDRP"
        private const val STATE_EXCLUDED_SOUND_ONLY_POST_IDS = "ESSOPI"
        private const val STATE_CURRENT_POST_FULL_NAME = "SCPFN"
        private const val STATE_PLAYBACK_VOLUME = "SPV"
        private const val STATE_LAST_AUDIBLE_VOLUME = "SLAV"
        private const val STATE_PANEL_VISIBLE = "SPVSB"
        private const val STATE_HAS_SORT_STATE = "SHSS"
        private const val STATE_SORT_TYPE = "SST"
        private const val STATE_SORT_TIME = "SSTM"
        private const val PREFETCH_LEAD_PAGES = 15
    }
}
