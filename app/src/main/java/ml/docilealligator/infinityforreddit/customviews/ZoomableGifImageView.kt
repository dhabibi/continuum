package ml.docilealligator.infinityforreddit.customviews

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.Drawable
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

    private val tapDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
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
        tapDetector.onTouchEvent(ev)

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

        val handled = super.onTouchEvent(ev)
        if (transformToRestore != null) {
            transformToRestore = normalizedZoomTransform() ?: transformToRestore
        }
        return handled
    }

    override fun setImageDrawable(drawable: Drawable?) {
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
