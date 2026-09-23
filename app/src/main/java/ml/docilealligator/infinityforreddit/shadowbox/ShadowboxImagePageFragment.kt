package ml.docilealligator.infinityforreddit.shadowbox

import android.graphics.drawable.Animatable
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import com.github.piasy.biv.loader.ImageLoader
import ml.docilealligator.infinityforreddit.SaveMemoryCenterInisdeDownsampleStrategy
import ml.docilealligator.infinityforreddit.customviews.GlideGifImageViewFactory
import ml.docilealligator.infinityforreddit.customviews.ImageZoomConfiguration
import ml.docilealligator.infinityforreddit.databinding.ShadowboxMediaImageBinding
import java.io.File

/** An image or GIF page: BigImageView with the same loader setup as ViewImageOrGifActivity. */
class ShadowboxImagePageFragment : ShadowboxPageFragment() {

    private var _binding: ShadowboxMediaImageBinding? = null
    private val binding: ShadowboxMediaImageBinding
        get() = _binding!!
    private lateinit var url: String
    private var isGif = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        url = requireArguments().getString(ARG_URL) ?: ""
        isGif = requireArguments().getBoolean(ARG_IS_GIF, false)
    }

    override fun onCreateMediaView(inflater: LayoutInflater, container: ViewGroup) {
        val binding = ShadowboxMediaImageBinding.inflate(inflater, container, true)
        _binding = binding
        binding.imageViewShadowboxMediaImage.setImageViewFactory(
            GlideGifImageViewFactory(SaveMemoryCenterInisdeDownsampleStrategy(maxResolution))
        )
        binding.imageViewShadowboxMediaImage.setImageLoaderCallback(object : ImageLoader.Callback {
            override fun onCacheHit(imageType: Int, image: File) {}

            override fun onCacheMiss(imageType: Int, image: File) {}

            override fun onStart() {}

            override fun onProgress(progress: Int) {}

            override fun onFinish() {}

            override fun onSuccess(image: File) {
                val binding = _binding ?: return
                binding.progressBarShadowboxMediaImage.visibility = View.GONE
                val view = binding.imageViewShadowboxMediaImage.ssiv
                if (view == null) {
                    // A gif: there is no subsampling view to wait on, and BigImageView has put a
                    // still of the downloaded file up itself, so the preview has nothing left to
                    // stand in for.
                    hidePreview()
                    return
                }
                view.setOnImageEventListener(object : SubsamplingScaleImageView.DefaultOnImageEventListener() {
                    override fun onImageLoaded() {
                        ImageZoomConfiguration.configure(view)
                        hidePreview()
                    }
                })
            }

            override fun onFail(error: Exception) {
                val binding = _binding ?: return
                binding.progressBarShadowboxMediaImage.visibility = View.GONE
                // Black behind the error, not the preview: the message is white text with no
                // background of its own and has to stay readable.
                binding.previewImageViewShadowboxMediaImage.visibility = View.GONE
                binding.loadImageErrorLinearLayoutShadowboxMediaImage.visibility = View.VISIBLE
            }
        })
        binding.imageViewShadowboxMediaImage.setOnClickListener { toggleChrome() }
        binding.loadImageErrorLinearLayoutShadowboxMediaImage.setOnClickListener {
            binding.progressBarShadowboxMediaImage.visibility = View.VISIBLE
            binding.loadImageErrorLinearLayoutShadowboxMediaImage.visibility = View.GONE
            loadMedia()
        }
        binding.progressBarShadowboxMediaImage.visibility = View.INVISIBLE
    }

    override fun loadMedia() {
        showPreview()
        binding.progressBarShadowboxMediaImage.visibility = View.VISIBLE
        binding.imageViewShadowboxMediaImage.showImage(Uri.parse(url))
    }

    /**
     * Puts the post's preview up behind the image while the full-size file is downloaded and
     * decoded. It is the picture the feed already showed, and a fraction of the size, so it is
     * there long before the image is and the page is never blank; both are fitted to the same
     * rectangle, so nothing moves when the real image lands on top of it.
     */
    private fun showPreview() {
        val binding = _binding ?: return
        val preview = ShadowboxPreviews.bestPreview(post, maxResolution, dataSavingMode)
        if (preview == null) {
            binding.previewImageViewShadowboxMediaImage.visibility = View.GONE
            return
        }
        binding.previewImageViewShadowboxMediaImage.alpha = 1f
        binding.previewImageViewShadowboxMediaImage.visibility = View.VISIBLE
        ShadowboxPreviews.previewRequest(glide, preview.previewUrl)
            .into(binding.previewImageViewShadowboxMediaImage)
    }

    /**
     * Fades the preview out from under the real image. A fade rather than a hide: the subsampling
     * view says it has loaded one frame before it draws, and taking the preview away in that gap
     * is a black flash between two identical pictures.
     */
    private fun hidePreview() {
        val preview = _binding?.previewImageViewShadowboxMediaImage ?: return
        if (preview.visibility != View.VISIBLE) {
            return
        }
        preview.animate().alpha(0f).setDuration(PREVIEW_FADE_MS).withEndAction {
            _binding?.previewImageViewShadowboxMediaImage?.visibility = View.GONE
        }.start()
    }

    override fun onDestroyView() {
        _binding?.let { binding ->
            val imageView = binding.imageViewShadowboxMediaImage
            imageView.setImageLoaderCallback(null)
            imageView.cancel()
            // BigImageView.cancel() cancels downloads only. Release the decoded tiles and
            // native decoder as soon as this page leaves the pager, including after zooming.
            imageView.ssiv?.let { tiledImage ->
                tiledImage.setOnImageEventListener(null)
                tiledImage.recycle()
            }
            // The GIF factory uses an Activity-scoped Glide request, so fragment destruction
            // alone does not clear its target or stop animation.
            (imageView.mainView as? ImageView)?.let { animatedImage ->
                (animatedImage.drawable as? Animatable)?.stop()
                glide.clear(animatedImage)
            }
            binding.previewImageViewShadowboxMediaImage.animate().cancel()
            glide.clear(binding.previewImageViewShadowboxMediaImage)
        }
        _binding = null
        super.onDestroyView()
    }

    companion object {
        private const val PREVIEW_FADE_MS = 150L
        private const val ARG_URL = "AU"
        private const val ARG_IS_GIF = "AIG"

        fun newInstance(position: Int, blur: Boolean, url: String, isGif: Boolean): ShadowboxImagePageFragment {
            val fragment = ShadowboxImagePageFragment()
            val args = baseArguments(position, blur)
            args.putString(ARG_URL, url)
            args.putBoolean(ARG_IS_GIF, isGif)
            fragment.arguments = args
            return fragment
        }
    }
}
