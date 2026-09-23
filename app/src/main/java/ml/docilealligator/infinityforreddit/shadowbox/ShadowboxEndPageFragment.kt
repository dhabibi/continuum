package ml.docilealligator.infinityforreddit.shadowbox

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import ml.docilealligator.infinityforreddit.R
import ml.docilealligator.infinityforreddit.databinding.FragmentShadowboxEndPageBinding
import ml.docilealligator.infinityforreddit.post.LoadingMorePostsStatus
import ml.docilealligator.infinityforreddit.post.PostType
import ml.docilealligator.infinityforreddit.viewmodels.ViewPostDetailActivityViewModel

/**
 * The page after the last post: shows whether more posts are loading, failed (tap to retry) or
 * ran out. Once a load succeeds this page is replaced by the first new post.
 */
class ShadowboxEndPageFragment : Fragment() {

    private var _binding: FragmentShadowboxEndPageBinding? = null
    private val binding: FragmentShadowboxEndPageBinding
        get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentShadowboxEndPageBinding.inflate(inflater, container, false)
        val activity = requireActivity() as ShadowboxActivity
        activity.typeface?.let { binding.statusTextViewShadowboxEndPageFragment.typeface = it }
        binding.root.setOnClickListener {
            // Retry a failed or idle load; the model refuses while loading or when done.
            activity.fetchMorePosts()
        }
        // The activity's own instance: asking ViewModelProvider for it without the factory it was
        // created with would try to construct one, and this model has no no-arg constructor.
        activity.viewModel.loadMorePostsState.observe(viewLifecycleOwner) { state -> render(state) }
        return binding.root
    }

    private fun render(state: ViewPostDetailActivityViewModel.LoadMorePostsState) {
        val binding = _binding ?: return
        val activity = requireActivity() as ShadowboxActivity
        binding.progressBarShadowboxEndPageFragment.visibility =
            if (state.status == LoadingMorePostsStatus.LOADING) View.VISIBLE else View.INVISIBLE
        val message = when {
            state.emptySource -> {
                if (activity.viewModel.currentFeedRequest?.postType == PostType.ANONYMOUS_MULTIREDDIT) {
                    R.string.anonymous_multireddit_no_subreddit
                } else {
                    R.string.anonymous_front_page_no_subscriptions
                }
            }
            state.status == LoadingMorePostsStatus.LOADING -> R.string.loading
            state.status == LoadingMorePostsStatus.FAILED -> R.string.load_more_posts_failed
            state.status == LoadingMorePostsStatus.NO_MORE_POSTS ||
                (state.status == LoadingMorePostsStatus.LOADED && !state.hasMore) -> {
                if (activity.isSoundOnlyFeedEmpty()) R.string.tiktok_sound_only_empty else R.string.no_more_posts
            }
            else -> R.string.tiktok_load_more
        }
        binding.statusTextViewShadowboxEndPageFragment.setText(message)
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
