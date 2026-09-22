package ml.docilealligator.infinityforreddit.customviews

import android.view.View
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import kotlin.math.max

/** Applies useful zoom bounds while retaining the original density-based behavior for large images. */
object ImageZoomConfiguration {
    private const val MINIMUM_DPI = 80
    private const val DOUBLE_TAP_DPI = 240
    private const val MIN_ZOOM_FROM_FIT = 3f
    private const val DOUBLE_TAP_ZOOM_FROM_FIT = 2f

    @JvmStatic
    fun configure(view: SubsamplingScaleImageView) {
        if (view.width == 0 || view.height == 0) {
            val layoutListener = object : View.OnLayoutChangeListener {
                override fun onLayoutChange(
                    changedView: View,
                    left: Int,
                    top: Int,
                    right: Int,
                    bottom: Int,
                    oldLeft: Int,
                    oldTop: Int,
                    oldRight: Int,
                    oldBottom: Int,
                ) {
                    if (view.width == 0 || view.height == 0) return
                    view.removeOnLayoutChangeListener(this)
                    configureLaidOutView(view)
                }
            }
            view.addOnLayoutChangeListener(layoutListener)
            return
        }
        configureLaidOutView(view)
    }

    private fun configureLaidOutView(view: SubsamplingScaleImageView) {
        view.setMinimumDpi(MINIMUM_DPI)
        view.setDoubleTapZoomDpi(DOUBLE_TAP_DPI)
        view.setDoubleTapZoomStyle(SubsamplingScaleImageView.ZOOM_FOCUS_FIXED)
        view.setQuickScaleEnabled(true)

        val fitScale = view.minScale
        if (fitScale > 0f && !fitScale.isInfinite() && !fitScale.isNaN()) {
            val displayMetrics = view.resources.displayMetrics
            val averageDpi = (displayMetrics.xdpi + displayMetrics.ydpi) / 2f
            val maximumScale = max(view.maxScale, fitScale * MIN_ZOOM_FROM_FIT)
            val doubleTapScale = max(
                averageDpi / DOUBLE_TAP_DPI,
                fitScale * DOUBLE_TAP_ZOOM_FROM_FIT,
            ).coerceAtMost(maximumScale)
            view.setMaxScale(maximumScale)
            view.setDoubleTapZoomScale(doubleTapScale)
        }

        view.resetScaleAndCenter()
    }
}
