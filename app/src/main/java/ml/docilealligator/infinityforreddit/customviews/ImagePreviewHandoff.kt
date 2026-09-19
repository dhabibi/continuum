package ml.docilealligator.infinityforreddit.customviews

import android.graphics.drawable.Drawable
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import com.bumptech.glide.Glide
import com.bumptech.glide.Priority
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.github.piasy.biv.view.BigImageView

/** Paints a supplied preview before the original download starts; original bytes remain separate. */
class ImagePreviewHandoff(private val view: BigImageView, private val onPreviewReady: Runnable) {
    private val glide = Glide.with(view)
    private var preview: ImageView? = null

    @JvmOverloads
    fun showPreview(url: String?, foreground: Boolean = false) {
        clear()
        if (url.isNullOrBlank()) return
        val image = ImageView(view.context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            // The tiling view may be attached later while its first image is decoding.
            elevation = 1f
        }
        preview = image
        view.addView(image, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT))
        loadPreview(image, url, foreground)
    }

    fun show(previewUrl: String?, originalUrl: String) {
        showPreview(previewUrl?.takeUnless { it == originalUrl }, true)
        view.showImage(Uri.parse(originalUrl))
    }

    /** Called when the original has decoded, rather than merely finished downloading. */
    fun originalReady() = clear()

    fun clear() {
        val image = preview ?: return
        preview = null
        glide.clear(image)
        view.removeView(image)
    }

    private fun loadPreview(image: ImageView, url: String, foreground: Boolean) {
        glide.load(url).priority(if (foreground) Priority.IMMEDIATE else Priority.LOW)
            // Offscreen gallery pages can show an existing preview without starting downloads.
            .onlyRetrieveFromCache(!foreground)
            .fitCenter().dontAnimate()
            .listener(object : RequestListener<Drawable> {
                override fun onLoadFailed(
                    e: GlideException?, model: Any?, target: Target<Drawable>, isFirstResource: Boolean,
                ): Boolean = false // A missing preview never prevents the original from loading.

                override fun onResourceReady(
                    resource: Drawable, model: Any, target: Target<Drawable>,
                    dataSource: DataSource, isFirstResource: Boolean,
                ): Boolean {
                    if (preview === image) onPreviewReady.run()
                    return false
                }
            }).into(image)
    }
}
