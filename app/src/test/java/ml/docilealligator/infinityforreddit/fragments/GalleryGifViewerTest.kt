package ml.docilealligator.infinityforreddit.fragments

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Looper
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import androidx.viewpager.widget.ViewPager
import ml.docilealligator.infinityforreddit.R
import ml.docilealligator.infinityforreddit.TestInfinity
import ml.docilealligator.infinityforreddit.activities.ViewRedditGalleryActivity
import ml.docilealligator.infinityforreddit.post.Post
import ml.docilealligator.infinityforreddit.videoautoplay.ExoCreator
import ml.docilealligator.infinityforreddit.videoautoplay.NextClipPreloader
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = TestInfinity::class, sdk = [34])
class GalleryGifViewerTest {
    @Test fun `viewer streams current GIF preloads next and releases players when swiping and pausing`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val first = Post.Gallery("image/gif", "https://i.redd.it/first.gif", "", "first.gif", "", "").apply {
            mp4Variant = "https://preview.redd.it/first.gif?format=mp4"
            feedPreviewUrl = "https://preview.redd.it/first-still.png"
        }
        val next = Post.Gallery("image/gif", "https://i.redd.it/next.gif", "", "next.gif", "", "").apply {
            mp4Variant = "https://preview.redd.it/next.gif?format=mp4"
        }
        val post = mock<Post>()
        whenever(post.gallery).thenReturn(arrayListOf(first, next))
        whenever(post.subredditName).thenReturn("test")
        whenever(post.id).thenReturn("gallery-test")
        val creator = mock<ExoCreator>()
        val firstPlayer = mock<ExoPlayer>()
        val nextPlayer = mock<ExoPlayer>()
        for (player in listOf(firstPlayer, nextPlayer)) {
            whenever(player.trackSelectionParameters).thenReturn(TrackSelectionParameters.DEFAULT)
        }
        whenever(creator.createPlayer()).thenReturn(firstPlayer, nextPlayer)
        whenever(creator.createMediaSource(any(), isNull())).thenReturn(mock())
        val preloader = mock<NextClipPreloader>()
        whenever(creator.createPreloader()).thenReturn(preloader)
        val intent = Intent(context, ViewRedditGalleryActivity::class.java)
            .putExtra(ViewRedditGalleryActivity.EXTRA_POST, post)
        val controller = Robolectric.buildActivity(ViewRedditGalleryActivity::class.java, intent)
        val activity = controller.create().start().visible().get()
        try {
            val field = ViewRedditGalleryActivity::class.java.getDeclaredField("gifCreator")
            field.isAccessible = true
            field.set(activity, creator)
            activity.supportFragmentManager.executePendingTransactions()
            val fragments = activity.supportFragmentManager.fragments.filterIsInstance<ViewRedditGalleryImageOrGifFragment>()
            assertEquals(2, fragments.size)
            fragments.forEach { it.gifCreator = creator }
            controller.resume()
            shadowOf(Looper.getMainLooper()).idle()
            verify(creator).createMediaSource(Uri.parse(first.mp4Variant), null)
            verify(creator, never()).createMediaSource(Uri.parse(next.mp4Variant), null)
            val listener = argumentCaptor<Player.Listener>()
            verify(firstPlayer).addListener(listener.capture())
            listener.firstValue.onRenderedFirstFrame()
            shadowOf(Looper.getMainLooper()).idle()
            verify(preloader).preload(Uri.parse(next.mp4Variant), 0, false)
            val currentFragment = fragments.first { it.isResumed }
            assertEquals("Original viewer must not remain behind the MP4 surface", android.view.View.GONE,
                currentFragment.requireView().findViewById<android.view.View>(R.id.image_view_view_reddit_gallery_image_or_gif_fragment).visibility)
            activity.findViewById<ViewPager>(R.id.view_pager_view_reddit_gallery_activity).setCurrentItem(1, false)
            activity.supportFragmentManager.executePendingTransactions()
            shadowOf(Looper.getMainLooper()).idle()
            verify(firstPlayer).release()
            verify(creator).createMediaSource(Uri.parse(next.mp4Variant), null)
            controller.pause()
            verify(nextPlayer).release()
            assertEquals("https://i.redd.it/first.gif", first.url)
            assertEquals("first.gif", first.fileName)
        } finally {
            controller.stop().destroy()
        }
    }
}
