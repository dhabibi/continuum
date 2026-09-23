package ml.docilealligator.infinityforreddit.viewmodels

import ml.docilealligator.infinityforreddit.postfilter.PostFilter
import ml.docilealligator.infinityforreddit.readpost.ReadPostType
import ml.docilealligator.infinityforreddit.readpost.ReadPostsListInterface
import ml.docilealligator.infinityforreddit.thing.SortType

/** A feed's source and account settings, independent of any already displayed post list. */
data class PostFeedRequest(
    val postType: Int,
    val accountName: String,
    val accessToken: String? = null,
    val subredditName: String? = null,
    val concatenatedSubredditNames: String? = null,
    val username: String? = null,
    val userWhere: String? = null,
    val multiPath: String? = null,
    val query: String? = null,
    val sortType: SortType.Type = SortType.Type.HOT,
    val sortTime: SortType.Time? = null,
    val postFilter: PostFilter? = null,
    @ReadPostType val readPostType: Int = ReadPostType.READ_POSTS,
    val readPostsList: ReadPostsListInterface? = null,
    val mediaOnly: Boolean = false,
)
