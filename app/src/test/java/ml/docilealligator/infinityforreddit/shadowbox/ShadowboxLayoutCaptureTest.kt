package ml.docilealligator.infinityforreddit.shadowbox

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import ml.docilealligator.infinityforreddit.R
import ml.docilealligator.infinityforreddit.databinding.ActivityShadowboxBinding
import ml.docilealligator.infinityforreddit.databinding.FragmentShadowboxPageBinding
import ml.docilealligator.infinityforreddit.databinding.ShadowboxMediaVideoBinding
import ml.docilealligator.infinityforreddit.font.ContentFontFamily
import ml.docilealligator.infinityforreddit.font.ContentFontStyle
import ml.docilealligator.infinityforreddit.font.FontFamily
import ml.docilealligator.infinityforreddit.font.FontStyle
import ml.docilealligator.infinityforreddit.font.TitleFontFamily
import ml.docilealligator.infinityforreddit.font.TitleFontStyle
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Render the production overlay with sample content for visual review, without network/player work. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ShadowboxLayoutCaptureTest {
    @Test fun portrait() = capture(393, 852)
    @Test fun smallPhone() = capture(360, 640)

    private fun capture(widthDp: Int, heightDp: Int) {
        RuntimeEnvironment.setQualifiers("+sw${widthDp}dp-w${widthDp}dp-h${heightDp}dp-port-xhdpi")
        val controller = Robolectric.buildActivity(Activity::class.java)
        val activity = controller.get()
        activity.setTheme(R.style.AppTheme)
        for (style in listOf(R.style.Theme_Normal_AmoledDark, FontStyle.Normal.resId,
            TitleFontStyle.Normal.resId, ContentFontStyle.Normal.resId, FontFamily.Default.resId,
            TitleFontFamily.Default.resId, ContentFontFamily.Default.resId)) activity.theme.applyStyle(style, true)
        controller.create()
        val screen = ActivityShadowboxBinding.inflate(activity.layoutInflater)
        val page = FragmentShadowboxPageBinding.inflate(activity.layoutInflater, screen.root, false)
        screen.root.removeView(screen.viewPager2ShadowboxActivity)
        screen.root.addView(page.root, 0)
        page.root.background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
            intArrayOf(Color.rgb(11, 45, 57), Color.rgb(30, 35, 50), Color.BLACK))
        val video = ShadowboxMediaVideoBinding.inflate(activity.layoutInflater, page.mediaContainerShadowboxPageFragment, true)
        video.root.setBackgroundColor(Color.TRANSPARENT)
        video.playerViewShadowboxMediaVideo.visibility = View.INVISIBLE
        video.previewImageViewShadowboxMediaVideo.visibility = View.GONE
        video.progressBarShadowboxMediaVideo.visibility = View.GONE
        video.playButtonShadowboxMediaVideo.visibility = View.GONE
        video.playbackControlsShadowbox.visibility = View.VISIBLE
        video.seekPositionShadowbox.progress = 3200
        video.seekPositionShadowbox.secondaryProgress = 6200
        video.seekPositionShadowbox.thumb.alpha = 0
        if (heightDp < 700) {
            video.playbackTimesShadowbox.visibility = View.VISIBLE
            video.playbackPositionShadowbox.text = "0:12"
            video.playbackDurationShadowbox.text = "0:38"
            video.playButtonShadowboxMediaVideo.visibility = View.VISIBLE
        }
        val info = page.infoPanelShadowboxPageFragment
        info.userTextViewShadowboxInfoPanel.text = "@skywatcher"
        info.subredditNameTextViewShadowboxInfoPanel.text = "r/EarthPorn"
        info.titleTextViewShadowboxInfoPanel.text = "Evening light over the city skyline. A quiet moment before the streets wake up."
        info.postTimeTextViewShadowboxInfoPanel.text = "3h"
        info.scoreTextViewShadowboxInfoPanel.text = "14.2k"
        info.commentsCountButtonShadowboxInfoPanel.text = "105"
        info.iconImageViewShadowboxInfoPanel.setImageResource(R.drawable.subreddit_default_icon)
        info.typeTextViewShadowboxInfoPanel.visibility = View.GONE
        info.muteButtonShadowboxInfoPanel.visibility = View.VISIBLE
        activity.setContentView(screen.root)
        controller.start().resume().visible()
        val density = activity.resources.displayMetrics.density
        val width = (widthDp * density).toInt()
        val height = (heightDp * density).toInt()
        screen.root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        screen.root.layout(0, 0, width, height)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        screen.root.draw(Canvas(bitmap))
        val output = File("build/outputs/shadowbox-preview/shadowbox-${widthDp}x${heightDp}.png")
        output.parentFile!!.mkdirs()
        output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        controller.pause().stop().destroy()
    }
}
