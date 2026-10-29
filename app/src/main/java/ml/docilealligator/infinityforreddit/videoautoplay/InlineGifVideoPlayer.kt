package ml.docilealligator.infinityforreddit.videoautoplay

import android.graphics.Matrix
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import java.util.function.Consumer
import kotlin.math.roundToInt
import ml.docilealligator.infinityforreddit.customviews.ZoomableGifImageView

/** A silent, looping MP4 beneath the GIF thumbnail; the image still owns touch and sizing. */
@UnstableApi
class InlineGifVideoPlayer private constructor(
    private val image: ImageView,
    private val parent: FrameLayout,
    private val creator: ExoCreator,
    private val onFailure: Consumer<InlineGifVideoPlayer>,
    private val onReady: Runnable,
    private val onBuffering: Runnable,
) {
    private val player = creator.createPlayer()
    private val handler = Handler(Looper.getMainLooper())
    private val originalImageAlpha = image.alpha
    private val texture = TextureView(image.context).apply {
        isOpaque = false
        alpha = 1f
        isClickable = false
        isFocusable = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val lastTransform = Matrix()
    private var videoSize = VideoSize.UNKNOWN
    private var released = false
    private var hasRenderedFrame = false
    private val drawListener = android.view.ViewTreeObserver.OnPreDrawListener { resize(); true }
    private val layoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> resize() }

    private fun start(uri: Uri) {
        // Keep a drawable texture underneath the preview. Waiting for a first frame with a
        // transparent/zero-sized TextureView can prevent its SurfaceTexture from being created.
        parent.addView(texture, parent.indexOfChild(image), textureLayoutParams())
        image.addOnLayoutChangeListener(layoutListener)
        if (image.scaleType == ImageView.ScaleType.MATRIX) {
            image.viewTreeObserver.addOnPreDrawListener(drawListener)
        }
        player.volume = 0f
        player.repeatMode = Player.REPEAT_MODE_ONE
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).build()
        player.setVideoTextureView(texture)
        player.addListener(object : Player.Listener {
            override fun onVideoSizeChanged(size: VideoSize) {
                videoSize = size
                if (hasRenderedFrame) updateVideoGeometry()
                // A zoomable image needs intrinsic bounds even when Reddit supplied no still.
                if (image.scaleType == ImageView.ScaleType.MATRIX && image.drawable == null) {
                    image.setImageDrawable(GradientDrawable().apply { setSize(size.width, size.height) })
                }
                resize()
            }

            override fun onRenderedFirstFrame() {
                if (!released) {
                    hasRenderedFrame = true
                    image.alpha = if (image is ZoomableGifImageView) originalImageAlpha else 0f
                    updateVideoGeometry()
                    resize()
                    onReady.run()
                }
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (released) return
                if (state == Player.STATE_BUFFERING) onBuffering.run()
                if (state == Player.STATE_READY && hasRenderedFrame) onReady.run()
            }

            override fun onPlayerError(error: PlaybackException) {
                // The holder may have been rebound before this main-loop turn.
                handler.post { if (!released) onFailure.accept(this@InlineGifVideoPlayer) }
            }
        })
        player.setMediaSource(creator.createMediaSource(uri, null))
        player.prepare()
        player.playWhenReady = true
        resize()
    }

    private fun updateVideoGeometry() {
        if (image is ZoomableGifImageView) {
            image.showVideoContent((videoSize.width * videoSize.pixelWidthHeightRatio).roundToInt(), videoSize.height)
        }
    }

    private fun textureLayoutParams() =
        FrameLayout.LayoutParams(image.layoutParams as FrameLayout.LayoutParams).apply {
            // A gallery page can resume before its formerly GONE image has been laid out.
            // Retain MATCH_PARENT/WRAP_CONTENT (or explicit dimensions) until measurement.
            if (image.width > 0) width = image.width
            if (image.height > 0) height = image.height
        }

    private fun resize() {
        if (released) return
        val params = textureLayoutParams()
        if (texture.layoutParams.width != params.width || texture.layoutParams.height != params.height) {
            texture.layoutParams = params
        }
        if (image.width == 0 || image.height == 0 || videoSize.width == 0 || videoSize.height == 0) return
        if (image.scaleType == ImageView.ScaleType.MATRIX) {
            val drawable = image.drawable
            if (drawable != null && drawable.intrinsicWidth > 0 && drawable.intrinsicHeight > 0) {
                applyTransform(Matrix().apply {
                    setScale(drawable.intrinsicWidth.toFloat() / image.width,
                        drawable.intrinsicHeight.toFloat() / image.height)
                    postConcat(image.imageMatrix)
                })
                return
            }
        }
        val videoAspect = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
        val viewAspect = image.width.toFloat() / image.height
        val crop = image.scaleType == ImageView.ScaleType.CENTER_CROP
        val widthScale = videoAspect / viewAspect
        val heightScale = viewAspect / videoAspect
        val scaleX = if (crop) maxOf(1f, widthScale) else minOf(1f, widthScale)
        val scaleY = if (crop) maxOf(1f, heightScale) else minOf(1f, heightScale)
        val alignment = when (image.scaleType) {
            ImageView.ScaleType.FIT_START -> 0f
            ImageView.ScaleType.FIT_END -> 1f
            else -> 0.5f
        }
        applyTransform(Matrix().apply {
            setScale(scaleX, scaleY, image.width * alignment, image.height * alignment)
        })
    }

    private fun applyTransform(matrix: Matrix) {
        if (matrix != lastTransform) {
            lastTransform.set(matrix)
            texture.setTransform(matrix)
        }
    }

    fun isBuffering(): Boolean = player.playbackState == Player.STATE_BUFFERING

    fun release() {
        if (released) return
        released = true
        handler.removeCallbacksAndMessages(null)
        image.removeOnLayoutChangeListener(layoutListener)
        if (image.viewTreeObserver.isAlive) image.viewTreeObserver.removeOnPreDrawListener(drawListener)
        player.clearVideoTextureView(texture)
        player.release()
        if (image is ZoomableGifImageView) image.restoreImageContent()
        image.alpha = originalImageAlpha
        parent.removeView(texture)
    }

    companion object {
        /** Unsupported containers or failed setup keep the original GIF path. */
        @JvmStatic
        @JvmOverloads
        fun create(
            image: ImageView, uri: Uri, creator: ExoCreator,
            onFailure: Consumer<InlineGifVideoPlayer>,
            onReady: Runnable,
            onBuffering: Runnable = Runnable {},
        ): InlineGifVideoPlayer? {
            val parent = image.parent as? FrameLayout ?: return null
            if (image.layoutParams !is FrameLayout.LayoutParams) return null
            var helper: InlineGifVideoPlayer? = null
            return try {
                InlineGifVideoPlayer(image, parent, creator, onFailure, onReady, onBuffering).also {
                    helper = it
                    it.start(uri)
                }
            } catch (_: RuntimeException) {
                helper?.release()
                null
            }
        }
    }
}
