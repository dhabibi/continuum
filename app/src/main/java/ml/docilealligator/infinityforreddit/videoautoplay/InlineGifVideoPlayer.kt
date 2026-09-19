package ml.docilealligator.infinityforreddit.videoautoplay

import android.graphics.Matrix
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

/** A silent, looping MP4 over the existing GIF thumbnail; the card still owns touch and sizing. */
@UnstableApi
class InlineGifVideoPlayer private constructor(
    private val image: ImageView,
    private val parent: FrameLayout,
    private val creator: ExoCreator,
    private val onFailure: Consumer<InlineGifVideoPlayer>,
    private val onReady: Runnable,
) {
    private val player = creator.createPlayer()
    private val handler = Handler(Looper.getMainLooper())
    private val texture = TextureView(image.context).apply {
        isOpaque = false
        alpha = 0f
        isClickable = false
        isFocusable = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private var videoSize = VideoSize.UNKNOWN
    private var released = false
    private val layoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> resize() }

    private fun start(uri: Uri) {
        val params = FrameLayout.LayoutParams(image.layoutParams as FrameLayout.LayoutParams)
        params.width = image.width
        params.height = image.height
        // Before badges/loading controls, so they keep their existing drawing order.
        parent.addView(texture, parent.indexOfChild(image) + 1, params)
        image.addOnLayoutChangeListener(layoutListener)
        player.volume = 0f
        player.repeatMode = Player.REPEAT_MODE_ONE
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).build()
        player.setVideoTextureView(texture)
        player.addListener(object : Player.Listener {
            override fun onVideoSizeChanged(size: VideoSize) {
                videoSize = size
                resize()
            }

            override fun onRenderedFirstFrame() {
                if (!released) {
                    texture.alpha = 1f
                    onReady.run()
                }
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

    private fun resize() {
        if (released) return
        if (texture.layoutParams.width != image.width || texture.layoutParams.height != image.height) {
            texture.layoutParams = FrameLayout.LayoutParams(image.layoutParams as FrameLayout.LayoutParams).apply {
                width = image.width
                height = image.height
            }
        }
        if (image.width == 0 || image.height == 0 || videoSize.width == 0 || videoSize.height == 0) return
        val videoAspect = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
        val viewAspect = image.width.toFloat() / image.height
        val crop = image.scaleType == ImageView.ScaleType.CENTER_CROP
        val scaleX = if ((videoAspect < viewAspect) != crop) videoAspect / viewAspect else 1f
        val scaleY = if ((videoAspect > viewAspect) != crop) viewAspect / videoAspect else 1f
        val alignment = when (image.scaleType) {
            ImageView.ScaleType.FIT_START -> 0f
            ImageView.ScaleType.FIT_END -> 1f
            else -> 0.5f
        }
        texture.setTransform(Matrix().apply {
            setScale(scaleX, scaleY, image.width * alignment, image.height * alignment)
        })
    }

    fun isBuffering(): Boolean = player.playbackState == Player.STATE_BUFFERING

    fun release() {
        if (released) return
        released = true
        handler.removeCallbacksAndMessages(null)
        image.removeOnLayoutChangeListener(layoutListener)
        player.clearVideoTextureView(texture)
        player.release()
        parent.removeView(texture)
    }

    companion object {
        /** Unsupported containers or failed setup keep the original GIF path. */
        @JvmStatic
        fun create(
            image: ImageView, uri: Uri, creator: ExoCreator,
            onFailure: Consumer<InlineGifVideoPlayer>,
            onReady: Runnable,
        ): InlineGifVideoPlayer? {
            val parent = image.parent as? FrameLayout ?: return null
            if (image.layoutParams !is FrameLayout.LayoutParams) return null
            var helper: InlineGifVideoPlayer? = null
            return try {
                InlineGifVideoPlayer(image, parent, creator, onFailure, onReady).also {
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
