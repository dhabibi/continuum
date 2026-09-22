package ml.docilealligator.infinityforreddit.customviews

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PointF
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.davemorrissey.labs.subscaleview.ImageSource
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ImageZoomConfigurationTest {
    @Test
    fun smallBitmapCanZoomPastItsFitScale() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = SubsamplingScaleImageView(context)
        val source = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
        val surface = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888)
        val imageLoaded = CountDownLatch(1)

        view.measure(
            View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, 300, 300)
        view.setOnImageEventListener(object : SubsamplingScaleImageView.DefaultOnImageEventListener() {
            override fun onImageLoaded() {
                ImageZoomConfiguration.configure(view)
                imageLoaded.countDown()
            }
        })
        view.setImage(ImageSource.bitmap(source))
        view.draw(Canvas(surface))
        shadowOf(android.os.Looper.getMainLooper()).idle()

        assertTrue("the bitmap should finish loading", imageLoaded.await(2, TimeUnit.SECONDS))
        val fitScale = view.minScale
        view.setScaleAndCenter(fitScale * 2f, PointF(5f, 5f))
        view.draw(Canvas(surface))

        assertTrue("pinch scale should remain above the fitted scale", view.scale > fitScale)
    }
}
