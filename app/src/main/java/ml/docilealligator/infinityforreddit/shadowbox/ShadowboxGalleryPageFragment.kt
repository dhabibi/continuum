package ml.docilealligator.infinityforreddit.shadowbox

import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.graphics.Insets
import androidx.core.view.doOnLayout
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import androidx.media3.common.util.UnstableApi
import com.bumptech.glide.Priority
import com.bumptech.glide.RequestBuilder
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.DrawableImageViewTarget
import com.bumptech.glide.request.target.Target
import ml.docilealligator.infinityforreddit.SaveMemoryCenterInisdeDownsampleStrategy
import ml.docilealligator.infinityforreddit.customviews.LinearLayoutManagerBugFixed
import ml.docilealligator.infinityforreddit.databinding.ItemShadowboxGalleryBinding
import ml.docilealligator.infinityforreddit.databinding.ShadowboxMediaGalleryBinding
import ml.docilealligator.infinityforreddit.post.Post
import ml.docilealligator.infinityforreddit.utils.SharedPreferencesUtils
import ml.docilealligator.infinityforreddit.videoautoplay.InlineGifVideoPlayer
import ml.docilealligator.infinityforreddit.videoautoplay.NextClipPreloader
import kotlin.math.abs

/**
 * A Reddit gallery: horizontal pages within Shadowbox's vertical post pager. The fullscreen
 * button opens the visible item in the gallery viewer; captions stay clear of the info panel.
 */
@OptIn(UnstableApi::class)
class ShadowboxGalleryPageFragment : ShadowboxPageFragment() {

    private var _binding: ShadowboxMediaGalleryBinding? = null
    private val binding: ShadowboxMediaGalleryBinding
        get() = _binding!!
    private var adapter: GalleryAdapter? = null
    private var panelHeight = 0
    private var insets = Insets.NONE

    /**
     * The size a tile is measured to, and so the size its picture is asked for.
     *
     * The list is the page, edge to edge, so the tile width is the window's; the height follows
     * from the ratio under the same cap the tiles are given. Naming it on the request is what lets
     * a tile be decoded as it binds, instead of a frame later once the list has been laid out and
     * Glide has a view size to work from.
     */
    private var tileWidth = 0
    private var tileHeight = 0

    /** Whether this page's gallery may animate its gifs, under the app's autoplay rule. */
    private var autoplayGif = false
    private var galleryItems: List<Post.Gallery> = emptyList()
    private var currentGalleryPage = 0
    private var galleryPagePositionInitialized = false
    private var galleryPageActive = false
    private var mediaForeground = false
    private var galleryScrolling = false
    private var gifPreloader: NextClipPreloader? = null
    private val boundHolders = mutableSetOf<GalleryViewHolder>()
    private val pendingTileRefreshes = mutableSetOf<Int>()
    private var adjacentPreviewUrl: String? = null
    private var adjacentPreviewTarget: Target<Drawable>? = null

    override fun onCreateMediaView(inflater: LayoutInflater, container: ViewGroup) {
        val binding = ShadowboxMediaGalleryBinding.inflate(inflater, container, true)
        _binding = binding
        binding.recyclerViewShadowboxMediaGallery.layoutManager =
            LinearLayoutManagerBugFixed(host, RecyclerView.HORIZONTAL, false)
        val pagerSnapHelper = PagerSnapHelper()
        pagerSnapHelper.attachToRecyclerView(binding.recyclerViewShadowboxMediaGallery)
        binding.galleryPageIndicatorShadowboxMediaGallery.setPageCount(post.gallery?.size ?: 0)
        binding.recyclerViewShadowboxMediaGallery.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                updatePageIndicator(recyclerView, pagerSnapHelper)
            }

            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                galleryScrolling = newState != RecyclerView.SCROLL_STATE_IDLE
                if (galleryScrolling) {
                    stopGalleryPlayback()
                } else {
                    updatePageIndicator(recyclerView, pagerSnapHelper, updateAdjacentPreview = true)
                    refreshTile(currentGalleryPage)
                }
            }
        })
        val galleryRecyclerView = binding.recyclerViewShadowboxMediaGallery
        galleryRecyclerView.doOnLayout {
            updatePageIndicator(galleryRecyclerView, pagerSnapHelper, updateAdjacentPreview = true)
        }
        // Taps toggle the chrome; horizontal gestures move through the gallery.
        addTapToToggleChrome(binding.recyclerViewShadowboxMediaGallery)
    }

    override fun loadMedia() {
        val preview = ShadowboxPreviews.bestPreview(post, maxResolution, dataSavingMode)
        val tileRatio = ShadowboxPreviews.galleryTileRatio(preview)
        val metrics = resources.displayMetrics
        tileWidth = metrics.widthPixels
        tileHeight = ShadowboxPreviews.galleryTileHeight(tileRatio, tileWidth, metrics.heightPixels)
        autoplayGif = sharedPreferences.getBoolean(SharedPreferencesUtils.GIF_AUTOPLAY, true) &&
            !dataSavingMode
        galleryItems = post.gallery ?: emptyList()
        currentGalleryPage = 0
        galleryPagePositionInitialized = false
        val adapter = GalleryAdapter(galleryItems)
        this.adapter = adapter
        binding.recyclerViewShadowboxMediaGallery.adapter = adapter
    }

    override fun onInsetsChanged(insets: Insets) {
        this.insets = insets
        applyListPadding()
    }

    override fun onPanelHeightChanged(height: Int) {
        panelHeight = height
        applyListPadding()
    }

    private fun applyListPadding() {
        // The panel's own height already includes the bottom inset it is padded by.
        val binding = _binding ?: return
        binding.recyclerViewShadowboxMediaGallery.updatePadding(
            left = insets.left, top = insets.top, right = insets.right,
            bottom = maxOf(panelHeight, insets.bottom)
        )
        val indicatorParams = binding.galleryPageIndicatorShadowboxMediaGallery.layoutParams as ViewGroup.MarginLayoutParams
        indicatorParams.topMargin = insets.top + (12 * resources.displayMetrics.density).toInt()
        binding.galleryPageIndicatorShadowboxMediaGallery.layoutParams = indicatorParams
    }

    private fun updatePageIndicator(
        recyclerView: RecyclerView,
        snapHelper: PagerSnapHelper,
        updateAdjacentPreview: Boolean = false,
    ) {
        val layoutManager = recyclerView.layoutManager ?: return
        val snappedView = snapHelper.findSnapView(layoutManager) ?: return
        val page = layoutManager.getPosition(snappedView)
        if (page != RecyclerView.NO_POSITION) {
            _binding?.galleryPageIndicatorShadowboxMediaGallery?.setCurrentPage(page)
            if (!galleryPagePositionInitialized) {
                currentGalleryPage = page
                galleryPagePositionInitialized = true
                if (needsCurrentTileRefresh(page)) refreshTile(page)
                preloadNextGalleryPreview(page)
            } else if (updateAdjacentPreview && page != currentGalleryPage) {
                val oldPage = currentGalleryPage
                stopGalleryPlayback()
                currentGalleryPage = page
                if (needsCurrentTileRefresh(oldPage)) refreshTile(oldPage)
                if (needsCurrentTileRefresh(page)) refreshTile(page)
                preloadNextGalleryPreview(page)
            }
        }
    }

    private fun needsCurrentTileRefresh(page: Int): Boolean {
        val item = galleryItems.getOrNull(page) ?: return false
        return when (item.mediaType) {
            Post.Gallery.TYPE_IMAGE -> item.feedPreviewUrl.isNullOrBlank() || item.feedPreviewUrl == item.url
            Post.Gallery.TYPE_GIF -> true
            else -> false
        }
    }

    private fun refreshTile(page: Int) {
        val recyclerView = _binding?.recyclerViewShadowboxMediaGallery ?: return
        val currentAdapter = adapter ?: return
        if (!pendingTileRefreshes.add(page)) return
        recyclerView.post {
            pendingTileRefreshes.remove(page)
            if (adapter !== currentAdapter) return@post
            val holder = recyclerView.findViewHolderForAdapterPosition(page) as? GalleryViewHolder
            if (page in galleryItems.indices &&
                (needsCurrentTileRefresh(page) || holder?.hasDrawable() != true)
            ) {
                if (holder != null) {
                    // Refresh the attached holder directly. A change animation can allocate a
                    // second holder/player while retaining the old one until its fade ends.
                    holder.bind(galleryItems[page], page, if (page == currentGalleryPage) Priority.IMMEDIATE else Priority.NORMAL)
                } else {
                    currentAdapter.notifyItemChanged(page)
                }
            }
        }
    }

    private fun preloadNextGalleryPreview(page: Int) {
        // Adjacent post pages may be built for vertical swipe readiness, but only the gallery in
        // front should start an extra network request. Data Saving still loads a tile on demand.
        if (!galleryPageActive || !mediaForeground || !revealed || !galleryPagePositionInitialized || dataSavingMode) {
            clearAdjacentPreviewPreload()
            return
        }
        val nextPreview = galleryItems.getOrNull(page + 1)
            ?.takeIf { it.mediaType == Post.Gallery.TYPE_IMAGE && it.feedPreviewUrl != it.url }
            ?.feedPreviewUrl
            ?.takeIf { it.isNotBlank() }
        if (nextPreview == adjacentPreviewUrl) return

        clearAdjacentPreviewPreload()
        adjacentPreviewUrl = nextPreview
        if (nextPreview != null && tileWidth > 0 && tileHeight > 0) {
            adjacentPreviewTarget = ShadowboxPreviews.previewRequest(glide, nextPreview)
                .override(tileWidth, tileHeight)
                .priority(Priority.HIGH)
                .preload(tileWidth, tileHeight)
        }
    }

    private fun clearAdjacentPreviewPreload() {
        adjacentPreviewTarget?.let { glide.clear(it) }
        adjacentPreviewTarget = null
        adjacentPreviewUrl = null
    }

    override fun onPageActive() {
        galleryPageActive = true
        super.onPageActive()
        if (galleryPagePositionInitialized) refreshTile(currentGalleryPage)
        preloadNextGalleryPreview(currentGalleryPage)
    }

    override fun onPageInactive() {
        pausePlayback()
        super.onPageInactive()
    }

    private fun canAnimateTile(page: Int): Boolean = autoplayGif && revealed && galleryPageActive &&
        mediaForeground && !galleryScrolling && galleryPagePositionInitialized && page == currentGalleryPage

    private fun stopGalleryPlayback() {
        gifPreloader?.stop()
        boundHolders.toList().forEach { it.stopPlayback() }
    }

    private fun preloadNextGif() {
        if (!canAnimateTile(currentGalleryPage)) return
        val next = galleryItems.getOrNull(currentGalleryPage + 1) ?: return
        if (next.mediaType != Post.Gallery.TYPE_GIF || next.mp4Variant.isNullOrBlank()) return
        if (gifPreloader == null) gifPreloader = exoCreator.createPreloader()
        gifPreloader?.preload(Uri.parse(next.mp4Variant), 0, false)
    }

    override fun pausePlayback() {
        galleryPageActive = false
        stopGalleryPlayback()
        clearAdjacentPreviewPreload()
    }

    override fun releaseMedia() {
        mediaForeground = false
        stopGalleryPlayback()
        clearAdjacentPreviewPreload()
    }

    override fun restoreMedia() {
        mediaForeground = true
        if (galleryPageActive && revealed) {
            refreshTile(currentGalleryPage)
            preloadNextGalleryPreview(currentGalleryPage)
        }
    }

    override fun onPause() {
        releaseMedia()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        restoreMedia()
    }

    override fun onDestroyView() {
        releaseMedia()
        boundHolders.toList().forEach { it.prepareForRecycling() }
        boundHolders.clear()
        pendingTileRefreshes.clear()
        _binding?.recyclerViewShadowboxMediaGallery?.adapter = null
        adapter = null
        gifPreloader = null
        galleryItems = emptyList()
        galleryPagePositionInitialized = false
        _binding = null
        super.onDestroyView()
    }

    private inner class GalleryAdapter(private val items: List<Post.Gallery>) : RecyclerView.Adapter<GalleryViewHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GalleryViewHolder {
            return GalleryViewHolder(ItemShadowboxGalleryBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        }

        override fun onBindViewHolder(holder: GalleryViewHolder, position: Int) {
            val distance = abs(position - currentGalleryPage)
            val priority = if (!galleryPageActive || !galleryPagePositionInitialized) {
                Priority.NORMAL
            } else {
                when (distance) {
                    0 -> Priority.IMMEDIATE
                    1 -> Priority.HIGH
                    else -> Priority.NORMAL
                }
            }
            holder.bind(items[position], position, priority)
        }

        override fun getItemCount(): Int = items.size

        override fun onViewDetachedFromWindow(holder: GalleryViewHolder) {
            holder.stopPlayback()
            super.onViewDetachedFromWindow(holder)
        }

        override fun onViewAttachedToWindow(holder: GalleryViewHolder) {
            super.onViewAttachedToWindow(holder)
            val page = holder.bindingAdapterPosition
            if (canAnimateTile(page) && page in items.indices) {
                holder.bind(items[page], page, Priority.IMMEDIATE)
            }
        }

        override fun onViewRecycled(holder: GalleryViewHolder) {
            holder.prepareForRecycling()
            boundHolders.remove(holder)
            glide.clear(holder.binding.imageViewItemShadowboxGallery)
        }
    }

    private inner class GalleryViewHolder(val binding: ItemShadowboxGalleryBinding) : RecyclerView.ViewHolder(binding.root) {
        private var boundItemUrl: String? = null
        private var boundItem: Post.Gallery? = null
        private var boundPage = RecyclerView.NO_POSITION
        private var gifPlayer: InlineGifVideoPlayer? = null
        private var failedMp4: String? = null
        private var originalGifRequested = false
        private var zoomSourceRequestedFor: String? = null

        init {
            host.typeface?.let { binding.captionTextViewItemShadowboxGallery.typeface = it }
        }

        fun bind(item: Post.Gallery, position: Int, priority: Priority) {
            val sameTile = boundItem === item && boundPage == position
            if (sameTile && canAnimateTile(position) &&
                (gifPlayer != null || originalGifRequested)
            ) {
                // Page activation, restore and attachment may all refresh the same tile.
                // Keep its existing decoder/request and zoom until it actually stops or changes.
                bindCaption(item)
                return
            }
            stopPlayback()
            if (boundItemUrl != item.url) failedMp4 = null
            boundHolders.add(this)
            boundItem = item
            boundPage = position
            boundItemUrl = item.url
            zoomSourceRequestedFor = null
            binding.imageViewItemShadowboxGallery.resetZoom()
            binding.imageViewItemShadowboxGallery.setOnZoomGestureStartedListener {
                if (item.mediaType == Post.Gallery.TYPE_IMAGE) {
                    loadFullResolutionImageForZoom(item, position)
                }
            }
            binding.errorImageViewItemShadowboxGallery.visibility = View.GONE
            binding.playBadgeImageViewItemShadowboxGallery.visibility =
                if (item.mediaType == Post.Gallery.TYPE_VIDEO) View.VISIBLE else View.GONE
            // The resolution-bounded still Reddit publishes for the item, which is what a tile
            // shows for every type: a fraction of the source, and what the feed's own inline
            // gallery loads.
            val still = item.feedPreviewUrl?.takeUnless { it.isBlank() || it == item.url }
            // What the tile ends up displaying, and the cheaper pictures it shows on the way
            // there, in the order it prefers them.
            val isCurrentTile = galleryPageActive && mediaForeground && revealed &&
                galleryPagePositionInitialized && position == currentGalleryPage
            val main: String?
            val standIns: MutableList<String> = mutableListOf()
            when (item.mediaType) {
                Post.Gallery.TYPE_GIF -> {
                    // The tile animates only where the app's autoplay rule allows it, the way the
                    // feed's inline gallery does; otherwise it shows the still, and the source --
                    // often tens of megabytes -- is never fetched with autoplay disabled.
                    val mp4 = item.mp4Variant?.takeIf { it.isNotBlank() && it != failedMp4 }
                    if (canAnimateTile(position) && mp4 != null) {
                        // The existing player shares the app's proxy/cache and follows the
                        // ZoomableGifImageView matrix, keeping poster, video and pinch aligned.
                        // Becoming current should keep an in-flight poster request alive.
                        if (still == null) clearTileContent()
                        else if (!sameTile) loadTile(still, emptyList(), priority)
                        gifPlayer = InlineGifVideoPlayer.create(
                            binding.imageViewItemShadowboxGallery, Uri.parse(mp4), exoCreator,
                            { failed ->
                                if (gifPlayer === failed && boundItemUrl == item.url) {
                                    failedMp4 = mp4
                                    gifPlayer = null
                                    failed.release()
                                    gifPreloader?.stop()
                                    if (canAnimateTile(position)) bind(item, position, Priority.IMMEDIATE)
                                }
                            },
                            { if (canAnimateTile(position)) preloadNextGif() },
                            { gifPreloader?.stop() },
                        )
                        if (gifPlayer != null) {
                            bindCaption(item)
                            return
                        }
                        failedMp4 = mp4
                    }
                    if (canAnimateTile(position)) {
                        main = item.url
                        originalGifRequested = true
                        still?.let { standIns.add(it) }
                    } else {
                        main = still
                    }
                }
                Post.Gallery.TYPE_VIDEO -> {
                    // Never item.url for a video item: that is the mp4, which Glide cannot decode
                    // into an ImageView, and asking it to left the tile blank under the badge for
                    // good. Without a still there is nothing to show, so the tile says so.
                    main = still
                }
                else -> {
                    // The source is the full-size image, several thousand pixels wide; it is the
                    // fallback only because an item without a still has nothing else to show.
                    main = still ?: item.url.takeIf { isCurrentTile }
                }
            }
            if (main == null) {
                val deferredOffscreenImage = !isCurrentTile
                val canWaitForActiveTile = item.mediaType == Post.Gallery.TYPE_IMAGE ||
                    item.mediaType == Post.Gallery.TYPE_GIF
                if (deferredOffscreenImage && canWaitForActiveTile) {
                    clearTileContent()
                } else {
                    showTileError()
                }
            } else {
                loadTile(main, standIns.filter { it != main }, priority)
            }
            bindCaption(item)
        }

        private fun bindCaption(item: Post.Gallery) {
            if (TextUtils.isEmpty(item.caption)) {
                binding.captionTextViewItemShadowboxGallery.visibility = View.GONE
            } else {
                binding.captionTextViewItemShadowboxGallery.visibility = View.VISIBLE
                binding.captionTextViewItemShadowboxGallery.text = item.caption
            }
            // No per-item click: it would fight the tap-to-toggle rule, and the fullscreen button
            // already opens the item the user is looking at.
            binding.root.isClickable = false
        }

        fun hasDrawable(): Boolean = binding.imageViewItemShadowboxGallery.drawable != null

        fun stopPlayback() {
            gifPlayer?.release()
            gifPlayer = null
            (binding.imageViewItemShadowboxGallery.drawable as? Animatable)?.stop()
            if (originalGifRequested) {
                originalGifRequested = false
                glide.clear(binding.imageViewItemShadowboxGallery)
                val still = boundItem?.feedPreviewUrl?.takeUnless { it.isBlank() || it == boundItemUrl }
                if (still != null) loadTile(still, emptyList(), Priority.NORMAL)
            }
        }

        fun prepareForRecycling() {
            stopPlayback()
            boundItem = null
            boundPage = RecyclerView.NO_POSITION
            boundItemUrl = null
            zoomSourceRequestedFor = null
            binding.imageViewItemShadowboxGallery.setOnZoomGestureStartedListener(null)
            binding.imageViewItemShadowboxGallery.resetZoom()
        }

        private fun loadFullResolutionImageForZoom(item: Post.Gallery, position: Int) {
            if (!galleryPageActive || position != currentGalleryPage || !mediaForeground) return
            if (zoomSourceRequestedFor == item.url) return
            zoomSourceRequestedFor = item.url

            val imageView = binding.imageViewItemShadowboxGallery
            val fullResolutionRequest = glide.load(item.url)
                .fitCenter()
                .downsample(SaveMemoryCenterInisdeDownsampleStrategy(maxResolution))
                .override(tileWidth * MAX_ZOOMED_TILE_SCALE, tileHeight * MAX_ZOOMED_TILE_SCALE)
                .dontAnimate()
            // Never use a post-level preview as a stand-in here: the parser can put the full
            // gallery source in that field when Reddit supplied no bounded preview.
            item.feedPreviewUrl?.takeUnless { it.isBlank() || it == item.url }?.let { previewUrl ->
                fullResolutionRequest.thumbnail(tileRequest(previewUrl, Priority.IMMEDIATE))
            }

            // Cancel the bounded tile request before starting the original. The thumbnail above
            // keeps it visible while the larger decode arrives; the transform stays with the view.
            imageView.preserveZoomForNextDrawable()
            glide.clear(imageView)
            fullResolutionRequest.into(object : DrawableImageViewTarget(imageView) {
                override fun onLoadFailed(errorDrawable: Drawable?) {
                    imageView.finishImageUpgradeAfterFailure()
                    if (boundItemUrl == item.url) {
                        // Keep the still visible and let a later gesture try the original again.
                        zoomSourceRequestedFor = null
                    }
                }
            })
        }

        /**
         * A tile fades in rather than appearing: the row is already measured to the post's ratio,
         * so the picture is the only thing that arrives, and it should not arrive with a snap. An
         * image Glide already holds is still set straight away, without any animation.
         */
        private fun tileRequest(url: String, priority: Priority): RequestBuilder<Drawable> =
            ShadowboxPreviews.previewRequest(glide, url)
                .override(tileWidth, tileHeight)
                .priority(priority)

        /**
         * Loads [url] into the tile, with [standIns] showing in turn until it arrives: the item's
         * own bounded still under an animating gif. Glide takes one thumbnail per request, so a
         * second stand-in hangs off the first rather than replacing it.
         */
        private fun loadTile(url: String, standIns: List<String>, priority: Priority) {
            var thumbnail: RequestBuilder<Drawable>? = null
            for (standIn in standIns.reversed()) {
                val request = tileRequest(standIn, priority)
                thumbnail = if (thumbnail == null) request else request.thumbnail(thumbnail)
            }
            val main = tileRequest(url, priority)
            val request = if (thumbnail == null) main else main.thumbnail(thumbnail)
            request.listener(tileListener).into(binding.imageViewItemShadowboxGallery)
        }

        /**
         * Marks the tile as having nothing to show, in the space the ratio already reserved for
         * it. Only from [bind], where there is no request of this tile's to get in the way of.
         */
        private fun showTileError() {
            clearTileContent()
            markTileFailed()
        }

        private fun clearTileContent() {
            glide.clear(binding.imageViewItemShadowboxGallery)
            binding.imageViewItemShadowboxGallery.setImageDrawable(null)
            binding.errorImageViewItemShadowboxGallery.visibility = View.GONE
        }

        private fun markTileFailed() {
            binding.playBadgeImageViewItemShadowboxGallery.visibility = View.GONE
            binding.errorImageViewItemShadowboxGallery.visibility = View.VISIBLE
        }

        /**
         * Puts the error mark up when a tile's own load fails and leaves nothing behind. Only the
         * tile's request reports here, and a stand-in that got there first is a picture: a gif
         * whose source failed over its still is a tile that has something to show, not one to
         * mark as empty.
         */
        private val tileListener = object : RequestListener<Drawable> {
            override fun onLoadFailed(
                e: GlideException?, model: Any?, target: Target<Drawable>, isFirstResource: Boolean
            ): Boolean {
                if (gifPlayer == null && binding.imageViewItemShadowboxGallery.drawable == null) {
                    markTileFailed()
                }
                return false
            }

            override fun onResourceReady(
                resource: Drawable, model: Any, target: Target<Drawable>,
                dataSource: DataSource, isFirstResource: Boolean
            ): Boolean {
                binding.errorImageViewItemShadowboxGallery.visibility = View.GONE
                if (!canAnimateTile(boundPage) && resource is Animatable) {
                    // The one gif that is loaded with autoplay off: the item had no still. Glide
                    // starts it after this callback returns, so stop it on the next loop, on the
                    // frame it reached, exactly as the feed does.
                    binding.imageViewItemShadowboxGallery.post {
                        val drawable = binding.imageViewItemShadowboxGallery.drawable
                        if (drawable is Animatable && drawable.isRunning) {
                            drawable.stop()
                        }
                    }
                }
                return false
            }
        }
    }

    companion object {
        fun newInstance(position: Int, blur: Boolean): ShadowboxGalleryPageFragment {
            val fragment = ShadowboxGalleryPageFragment()
            fragment.arguments = baseArguments(position, blur)
            return fragment
        }
    }
}

private const val MAX_ZOOMED_TILE_SCALE = 3
