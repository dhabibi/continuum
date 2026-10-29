package ml.docilealligator.infinityforreddit.adapters;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import android.content.Context;
import android.net.Uri;
import android.view.View;
import android.widget.FrameLayout;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.core.app.ApplicationProvider;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Locale;
import ml.docilealligator.infinityforreddit.TestInfinity;
import ml.docilealligator.infinityforreddit.customviews.AspectRatioGifImageView;
import ml.docilealligator.infinityforreddit.post.Post;
import ml.docilealligator.infinityforreddit.videoautoplay.ExoCreator;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(application = TestInfinity.class, sdk = 34)
public class FeedGifAutoplayTest {
    private PostRecyclerViewAdapter adapter;
    private PostRecyclerViewAdapter.PostWithPreviewTypeViewHolder holder;
    private ExoCreator creator;
    private ExoPlayer player;
    private Post post;

    @Before public void setup() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        creator = mock(ExoCreator.class);
        player = mock(ExoPlayer.class);
        when(player.getTrackSelectionParameters()).thenReturn(TrackSelectionParameters.DEFAULT);
        when(creator.createPlayer()).thenReturn(player);
        // The adapter permits null activity so its pure row behavior can be exercised without
        // booting a Home activity, feed database or account network request.
        adapter = new PostRecyclerViewAdapter(null, null, null, null, null, null, null,
                null, null, null, Locale.US, null, "", 0, 0, false,
                null, null, null, null, creator, null);
        set("mExoCreator", creator);
        set("mAutoplayNsfwVideos", true);
        FrameLayout frame = new FrameLayout(context);
        holder = adapter.new PostWithPreviewTypeViewHolder(frame);
        holder.imageView = new AspectRatioGifImageView(context);
        frame.addView(holder.imageView, new FrameLayout.LayoutParams(200, 100));
        frame.measure(View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY));
        frame.layout(0, 0, 200, 100);
        post = mock(Post.class);
        when(post.getPostType()).thenReturn(Post.GIF_TYPE);
        when(post.getUrl()).thenReturn("https://i.redd.it/animation.gif");
        when(post.getMp4Variant()).thenReturn("https://preview.redd.it/animation.gif?format=mp4&s=signed");
        holder.post = post;
        holder.preview = new Post.Preview("https://preview.redd.it/still.png", 200, 100, "", "");
    }

    @Test public void defaultGifAutoplayWorksWhileVideoAutoplayRemainsOff() throws Exception {
        assertFalse(adapter.isAutoplay());
        assertSame(holder.imageView, eligibleView());
        Method start = PostRecyclerViewAdapter.class.getDeclaredMethod("setGifAnimating", RecyclerView.ViewHolder.class, boolean.class);
        start.setAccessible(true);
        start.invoke(adapter, holder, true);
        verify(creator).createMediaSource(Uri.parse(post.getMp4Variant()), null);
        verify(player).prepare();
        verify(player).setPlayWhenReady(true);
        assertFalse(adapter.isAutoplay());
        Method release = PostRecyclerViewAdapter.class.getDeclaredMethod("releaseInlineGif", RecyclerView.ViewHolder.class);
        release.setAccessible(true);
        release.invoke(adapter, holder);
        verify(player).release();
    }

    @Test public void visibleRowStartsWithoutAScrollAndStopsWhenHostPauses() throws Exception {
        org.robolectric.android.controller.ActivityController<android.app.Activity> controller =
                org.robolectric.Robolectric.buildActivity(android.app.Activity.class).setup().visible();
        android.app.Activity activity = controller.get();
        RecyclerView list = new RecyclerView(activity) {
            @Override public boolean hasWindowFocus() { return true; }
        };
        list.setLayoutManager(new androidx.recyclerview.widget.LinearLayoutManager(activity));
        holder.itemView.setLayoutParams(new RecyclerView.LayoutParams(200, 100));
        list.setAdapter(new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            @Override public RecyclerView.ViewHolder onCreateViewHolder(android.view.ViewGroup parent, int type) { return holder; }
            @Override public void onBindViewHolder(RecyclerView.ViewHolder h, int position) {}
            @Override public int getItemCount() { return 1; }
        });
        activity.setContentView(list);
        set("mSimultaneousAutoplayLimit", -1);
        set("mStartAutoplayVisibleAreaOffset", 0.5);
        set("mGlide", com.bumptech.glide.Glide.with(activity));
        set("mSaveMemoryCenterInsideDownsampleStrategy", new ml.docilealligator.infinityforreddit.SaveMemoryCenterInisdeDownsampleStrategy(1_000_000));
        holder.preview = new Post.Preview("android.resource://" + activity.getPackageName() + "/" + ml.docilealligator.infinityforreddit.R.drawable.ic_gallery_day_night_24dp, 200, 100, "", "");
        try {
            adapter.onAttachedToRecyclerView(list);
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(150));
            verify(player).prepare();
            adapter.setCanPlayVideo(false);
            verify(player).release();
            adapter.setCanPlayVideo(true);
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(150));
            verify(player, times(2)).prepare();
        } finally {
            adapter.onDetachedFromRecyclerView(list);
            controller.pause().stop().destroy();
        }
    }

    @Test public void toggleSavingAndHiddenMediaKeepTheirGates() throws Exception {
        adapter.setGifAutoplay(false);
        assertNull(eligibleView());
        adapter.setGifAutoplay(true);
        set("mDataSavingMode", true);
        assertNull(eligibleView());
        set("mDataSavingMode", false);
        when(post.isNSFW()).thenReturn(true);
        set("mNeedBlurNsfw", true);
        assertNull(eligibleView());
        set("mNeedBlurNsfw", false);
        set("mAutoplayNsfwVideos", false);
        assertNull(eligibleView());
        when(post.isNSFW()).thenReturn(false);
        when(post.isSpoiler()).thenReturn(true);
        set("mNeedBlurSpoiler", true);
        assertNull(eligibleView());
        when(post.isSpoiler()).thenReturn(false);
        when(post.getPostType()).thenReturn(Post.IMAGE_TYPE);
        assertNull(eligibleView());
        when(post.getPostType()).thenReturn(Post.VIDEO_TYPE);
        assertNull(eligibleView());
    }

    private Object eligibleView() throws Exception {
        Method method = PostRecyclerViewAdapter.class.getDeclaredMethod("gifMediaView", RecyclerView.ViewHolder.class);
        method.setAccessible(true);
        return method.invoke(adapter, holder);
    }

    private void set(String field, Object value) throws Exception {
        Field declared = PostRecyclerViewAdapter.class.getDeclaredField(field);
        declared.setAccessible(true);
        declared.set(adapter, value);
    }
}
