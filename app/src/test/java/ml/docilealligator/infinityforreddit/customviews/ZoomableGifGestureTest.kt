package ml.docilealligator.infinityforreddit.customviews

import android.app.Activity
import android.graphics.drawable.GradientDrawable
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ViewGroup
import ml.docilealligator.infinityforreddit.TestInfinity
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(application = TestInfinity::class, sdk = [34])
class ZoomableGifGestureTest {
    @Test fun `multitouch never clicks while a deliberate tap clicks once`() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        try {
            val image = ZoomableGifImageView(controller.get())
            image.setImageDrawable(GradientDrawable().apply { setSize(200, 200) })
            controller.get().setContentView(image, ViewGroup.LayoutParams(200, 200))
            shadowOf(Looper.getMainLooper()).idle()
            var clicks = 0
            image.setOnClickListener { clicks++ }
            val properties = Array(2) { i -> MotionEvent.PointerProperties().apply { id = i; toolType = MotionEvent.TOOL_TYPE_FINGER } }
            val coords = Array(2) { i -> MotionEvent.PointerCoords().apply { x = if (i == 0) 60f else 140f; y = 100f; pressure = 1f; size = 1f } }
            fun send(action: Int, count: Int) {
                val now = SystemClock.uptimeMillis()
                val event = MotionEvent.obtain(now, now, action, count, properties, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
                image.dispatchTouchEvent(event)
                event.recycle()
            }
            send(MotionEvent.ACTION_DOWN, 1)
            send(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2)
            send(MotionEvent.ACTION_MOVE, 2)
            send(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2)
            send(MotionEvent.ACTION_UP, 1)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
            assertEquals("A pinch must not also click", 0, clicks)
            send(MotionEvent.ACTION_DOWN, 1)
            send(MotionEvent.ACTION_UP, 1)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
            assertEquals("A tap must not be dispatched twice", 1, clicks)
            image.performClick()
            assertEquals("Accessibility click remains available", 2, clicks)
        } finally { controller.pause().stop().destroy() }
    }
}
