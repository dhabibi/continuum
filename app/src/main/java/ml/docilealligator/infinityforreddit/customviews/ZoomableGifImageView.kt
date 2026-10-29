package ml.docilealligator.infinityforreddit.customviews

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import com.otaliastudios.zoom.ZoomImageView

/**
 * A [ZoomImageView] for displaying animated GIFs with pinch-to-zoom and pan.
 *
 * The bare ZoomImageView swallows touch events in its zoom engine, which breaks two things we
 * rely on, so this subclass restores them:
 *  - Single taps are forwarded to the view's OnClickListener. BigImageView sets that listener on
 *    the GIF view to toggle the toolbar, so without this a tap would do nothing.
 *  - While the user is pinching (two fingers) or panning a zoomed-in GIF, parent views (the
 *    swipe-to-dismiss HaulerView, the gallery ViewPager and the NestedScrollView) are asked to
 *    stop intercepting touches, mirroring what SubsamplingScaleImageView already does for static
 *    images. At rest (zoom == 1, one finger) the parents keep their gestures, so swipe-to-dismiss
 *    and paging between gallery items still work.
 */
class ZoomableGifImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : ZoomImageView(context, attrs, defStyleAttr) {

    private var zoomGestureListener: (() -> Unit)? = null
    private var zoomGestureNotified = false
    private var viewReady = false
    private var resetOnNextDrawable = false
    private var transformToRestore: NormalizedZoomTransform? = null
    private var videoGeometry: Drawable? = null
    private var videoPoster: Drawable? = null
    private var savedVideoTransform: NormalizedZoomTransform? = null

    /** A video owns the zoom coordinate system once its first frame is ready. */
    fun showVideoContent(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        val current = videoGeometry
        if (current?.intrinsicWidth == width && current.intrinsicHeight == height) return
        val transform = if (current == null) savedVideoTransform else normalizedZoomTransform()
        if (current == null) videoPoster = drawable
        val geometry = GradientDrawable().apply { setColor(android.graphics.Color.TRANSPARENT); setSize(width, height) }
        videoGeometry = geometry
        transformToRestore = null
        resetOnNextDrawable = false
        // Do not inherit a thumbnail's absolute fit scale. Video dimensions define a separate
        // coordinate system; apply its fit (or saved user zoom) before revealing the frame.
        super.setImageDrawable(geometry)
        if (transform == null) moveToCenter(1f, false)
        else moveTo(transform.zoom, transform.normalizedPanX * width,
            transform.normalizedPanY * height, false)
    }

    fun restoreImageContent() {
        if (videoGeometry == null) return
        val poster = videoPoster
        savedVideoTransform = normalizedZoomTransform()
        videoGeometry = null
        videoPoster = null
        setDisplayedDrawable(poster)
    }

    private val tapDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onLongPress(e: MotionEvent) {
            if (isLongClickable) performLongClick()
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            performClick()
            return true
        }
    })

    init {
        viewReady = true
    }

    fun setOnZoomGestureStartedListener(listener: (() -> Unit)?) {
        zoomGestureListener = listener
        zoomGestureNotified = false
    }

    fun resetZoom() {
        zoomGestureNotified = false
        transformToRestore = null
        resetOnNextDrawable = true
        moveToCenter(1f, false)
    }

    fun preserveZoomForNextDrawable() {
        transformToRestore = normalizedZoomTransform()
    }

    fun finishImageUpgradeAfterFailure() {
        transformToRestore = null
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        val tapHandled = tapDetector.onTouchEvent(ev)

        if (ev.pointerCount >= 2 || zoom > 1.00001f) {
            parent?.requestDisallowInterceptTouchEvent(true)
            if (!zoomGestureNotified) {
                zoomGestureNotified = true
                zoomGestureListener?.invoke()
            }
        }
        if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
            zoomGestureNotified = false
        }

        // ZoomImageView also calls ImageView.onTouchEvent, which can emit a click on the final
        // UP of a pinch. Let the gesture detector own clicks and the engine own pan/zoom.
        val handled = engine.onTouchEvent(ev)
        if (transformToRestore != null) {
            transformToRestore = normalizedZoomTransform() ?: transformToRestore
        }
        return handled || tapHandled
    }

    override fun setImageDrawable(drawable: Drawable?) {
        if (videoGeometry != null) {
            // Glide can finish a still-preview request after video playback starts. Keep it for
            // release, but do not let it change the active video's aspect ratio or pan bounds.
            videoPoster = drawable
            return
        }
        setDisplayedDrawable(drawable)
    }

    private fun setDisplayedDrawable(drawable: Drawable?) {
        if (!viewReady || drawable == null) {
            super.setImageDrawable(drawable)
            return
        }

        if (resetOnNextDrawable) {
            resetOnNextDrawable = false
            transformToRestore = null
            super.setImageDrawable(drawable)
            post {
                if (this.drawable === drawable) {
                    moveToCenter(1f, false)
                }
            }
            return
        }

        val transform = transformToRestore ?: normalizedZoomTransform()
        super.setImageDrawable(drawable)
        if (transform != null && drawable.intrinsicWidth > 0 && drawable.intrinsicHeight > 0) {
            post {
                if (this.drawable === drawable) {
                    moveTo(
                        transform.zoom,
                        transform.normalizedPanX * drawable.intrinsicWidth,
                        transform.normalizedPanY * drawable.intrinsicHeight,
                        false,
                    )
                    if (transformToRestore == transform) transformToRestore = null
                }
            }
        }
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun normalizedZoomTransform(): NormalizedZoomTransform? {
        val currentDrawable = drawable ?: return null
        val width = currentDrawable.intrinsicWidth
        val height = currentDrawable.intrinsicHeight
        if (width <= 0 || height <= 0) return null
        return NormalizedZoomTransform(zoom, panX / width, panY / height)
    }
}

private data class NormalizedZoomTransform(
    val zoom: Float,
    val normalizedPanX: Float,
    val normalizedPanY: Float,
)
