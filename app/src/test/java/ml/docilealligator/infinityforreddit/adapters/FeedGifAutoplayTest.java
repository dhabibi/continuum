package ml.docilealligator.infinityforreddit.adapters;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import android.app.Activity;
import android.content.Context;
import android.view.ContextThemeWrapper;
import android.view.ViewGroup;
import android.widget.ImageView;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Set;
import ml.docilealligator.infinityforreddit.R;
import ml.docilealligator.infinityforreddit.activities.BaseActivity;
import ml.docilealligator.infinityforreddit.videoautoplay.ToroUtil;
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
        RecyclerView list = new RecyclerView(activity);
        controller.windowFocusChanged(true);
        list.setLayoutManager(new androidx.recyclerview.widget.LinearLayoutManager(activity));
        holder.itemView.setLayoutParams(new RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        holder.imageView.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        holder.imageView.setRatio(0.5f);
        list.setAdapter(new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            @Override public RecyclerView.ViewHolder onCreateViewHolder(android.view.ViewGroup parent, int type) { return holder; }
            @Override public void onBindViewHolder(RecyclerView.ViewHolder h, int position) {}
            @Override public int getItemCount() { return 1; }
        });
        activity.setContentView(list);
        set("mSimultaneousAutoplayLimit", 1);
        set("mStartAutoplayVisibleAreaOffset", 0.75);
        set("mGlide", com.bumptech.glide.Glide.with(activity));
        set("mSaveMemoryCenterInsideDownsampleStrategy", new ml.docilealligator.infinityforreddit.SaveMemoryCenterInisdeDownsampleStrategy(1_000_000));
        holder.preview = new Post.Preview("android.resource://" + activity.getPackageName() + "/" + ml.docilealligator.infinityforreddit.R.drawable.ic_gallery_day_night_24dp, 200, 100, "", "");
        try {
            adapter.onAttachedToRecyclerView(list);
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(150));
            assertTrue(list.hasWindowFocus());
            assertTrue(ToroUtil.visibleAreaOffset(holder.imageView, list) >= 0.75);
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

    @Test public void lateLayoutAndFocusStartWithoutScrolling() throws Exception {
        org.robolectric.android.controller.ActivityController<Activity> controller =
                org.robolectric.Robolectric.buildActivity(Activity.class).setup().visible();
        Activity activity = controller.get();
        RecyclerView list = attachRows(activity, holder);
        holder.imageView.setVisibility(View.GONE);
        set("mSimultaneousAutoplayLimit", 1);
        set("mStartAutoplayVisibleAreaOffset", 0.75);
        configureStill(activity);
        try {
            adapter.onAttachedToRecyclerView(list);
            idle();
            verify(player, never()).prepare();
            controller.windowFocusChanged(true);
            idle();
            verify(player, never()).prepare();
            holder.imageView.setVisibility(View.VISIBLE);
            idle();
            assertTrue(ToroUtil.visibleAreaOffset(holder.imageView, list) >= 0.75);
            verify(player).prepare();
            controller.windowFocusChanged(false);
            idle();
            verify(player).release();
            controller.windowFocusChanged(true);
            idle();
            verify(player, times(2)).prepare();
        } finally {
            adapter.onDetachedFromRecyclerView(list);
            controller.pause().stop().destroy();
        }
    }

    @Test public void galleryAndSingleGifShareDefaultBudgetInARealList() throws Exception {
        org.robolectric.android.controller.ActivityController<Activity> controller =
                org.robolectric.Robolectric.buildActivity(Activity.class).setup().visible();
        Activity activity = controller.get();
        ContextThemeWrapper context = new ContextThemeWrapper(activity, R.style.AppTheme);
        for (int style : new int[] { R.style.Theme_Normal,
                ml.docilealligator.infinityforreddit.font.FontStyle.Normal.getResId(),
                ml.docilealligator.infinityforreddit.font.TitleFontStyle.Normal.getResId(),
                ml.docilealligator.infinityforreddit.font.ContentFontStyle.Normal.getResId(),
                ml.docilealligator.infinityforreddit.font.FontFamily.Default.getResId(),
                ml.docilealligator.infinityforreddit.font.TitleFontFamily.Default.getResId(),
                ml.docilealligator.infinityforreddit.font.ContentFontFamily.Default.getResId() }) {
            context.getTheme().applyStyle(style, true);
        }
        BaseActivity host = mock(BaseActivity.class);
        when(host.getResources()).thenReturn(activity.getResources());
        set("mActivity", host);
        configureStill(activity);
        set("mSimultaneousAutoplayLimit", 1);
        set("mStartAutoplayVisibleAreaOffset", 0.75);
        FrameLayout frame = new FrameLayout(context);
        RecyclerView pages = new RecyclerView(context);
        frame.addView(pages, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        PostRecyclerViewAdapter.PostGalleryBaseGalleryTypeViewHolder gallery =
                adapter.new PostGalleryBaseGalleryTypeViewHolder(frame, frame, pages,
                        new com.libRG.CustomTextView(context), new ImageView(context));
        Post.Gallery animated = new Post.Gallery("image/gif", holder.preview.getPreviewUrl(),
                "", "animated.gif", "", "");
        animated.feedPreviewUrl = holder.preview.getPreviewUrl();
        animated.mp4Variant = "https://preview.redd.it/gallery.gif?format=mp4";
        Post.Gallery still = new Post.Gallery("image/png", holder.preview.getPreviewUrl(),
                "", "still.png", "", "");
        gallery.post = mock(Post.class);
        when(gallery.post.getGallery()).thenReturn(new ArrayList<>(Arrays.asList(animated, still)));
        gallery.adapter.setRatio(0.25f);
        gallery.adapter.setAutoplayGif(true);
        gallery.adapter.setGalleryImages(gallery.post.getGallery());
        RecyclerView list = attachRows(activity, gallery, holder);
        controller.windowFocusChanged(true);
        try {
            adapter.onAttachedToRecyclerView(list);
            idle();
            assertTrue(gallery.wantsToPlay());
            assertTrue(ToroUtil.visibleAreaOffset(holder.imageView, list) >= 0.75);
            assertEquals(Collections.singleton(0), selectedPositions(list));
            verify(player, never()).prepare();
            // A still gallery page must yield the only slot to the following single GIF.
            gallery.adapter.setCurrentPosition(1);
            assertFalse(gallery.wantsToPlay());
            assertEquals(Collections.singleton(1), selectedPositions(list));
            Method update = PostRecyclerViewAdapter.class.getDeclaredMethod("updateAnimatedGifs", RecyclerView.class);
            update.setAccessible(true);
            update.invoke(adapter, list);
            verify(player).prepare();
            // No slot may be spent while autoplay is explicitly limited to zero.
            set("mSimultaneousAutoplayLimit", 0);
            assertEquals(Collections.emptySet(), selectedPositions(list));
            update.invoke(adapter, list);
            verify(player).release();
        } finally {
            adapter.onDetachedFromRecyclerView(list);
            controller.pause().stop().destroy();
        }
    }

    private RecyclerView attachRows(Activity activity, RecyclerView.ViewHolder... rows) {
        holder.imageView.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        holder.imageView.setRatio(0.25f);
        for (RecyclerView.ViewHolder row : rows) {
            row.itemView.setLayoutParams(new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        RecyclerView list = new RecyclerView(activity);
        list.setLayoutManager(new androidx.recyclerview.widget.LinearLayoutManager(activity));
        list.setAdapter(new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            @Override public int getItemViewType(int position) { return position; }
            @Override public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int type) { return rows[type]; }
            @Override public void onBindViewHolder(RecyclerView.ViewHolder row, int position) {}
            @Override public int getItemCount() { return rows.length; }
        });
        activity.setContentView(list);
        return list;
    }

    private void configureStill(Activity activity) throws Exception {
        set("mGlide", com.bumptech.glide.Glide.with(activity));
        set("mSaveMemoryCenterInsideDownsampleStrategy", new ml.docilealligator.infinityforreddit.SaveMemoryCenterInisdeDownsampleStrategy(1_000_000));
        holder.preview = new Post.Preview("android.resource://" + activity.getPackageName() + "/"
                + R.drawable.ic_gallery_day_night_24dp, 200, 100, "", "");
    }

    private void idle() {
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
                .idleFor(java.time.Duration.ofMillis(200));
    }

    @SuppressWarnings("unchecked")
    private Set<Integer> selectedPositions(RecyclerView list) throws Exception {
        Method method = PostRecyclerViewAdapter.class.getDeclaredMethod("unifiedAutoplaySelection", RecyclerView.class);
        method.setAccessible(true);
        return (Set<Integer>) method.invoke(adapter, list);
    }

    @Test public void animatedVideoUsesGifPreferenceInAllCardLayouts() throws Exception {
        when(post.getPostType()).thenReturn(Post.VIDEO_TYPE);
        when(post.getPreviews()).thenReturn(new ArrayList<>(Collections.singletonList(holder.preview)));
        Method viewType = PostRecyclerViewAdapter.class.getDeclaredMethod("viewTypeFor", Post.class);
        viewType.setAccessible(true);
        for (int layout : new int[] {
                ml.docilealligator.infinityforreddit.utils.SharedPreferencesUtils.POST_LAYOUT_CARD,
                ml.docilealligator.infinityforreddit.utils.SharedPreferencesUtils.POST_LAYOUT_CARD_2,
                ml.docilealligator.infinityforreddit.utils.SharedPreferencesUtils.POST_LAYOUT_CARD_3 }) {
            set("mPostLayout", layout);
            adapter.setAutoplay(false);
            adapter.setGifAutoplay(true);
            set("mDataSavingMode", false);
            when(post.isAnimatedVideo()).thenReturn(false);
            Object stillType = viewType.invoke(adapter, post);
            when(post.isAnimatedVideo()).thenReturn(true);
            Object playingType = viewType.invoke(adapter, post);
            assertNotEquals(stillType, playingType);
            adapter.setGifAutoplay(false);
            assertEquals(stillType, viewType.invoke(adapter, post));
            adapter.setAutoplay(true);
            assertEquals(stillType, viewType.invoke(adapter, post));
            when(post.isAnimatedVideo()).thenReturn(false);
            assertEquals(playingType, viewType.invoke(adapter, post));
            adapter.setGifAutoplay(true);
            set("mDataSavingMode", true);
            when(post.isAnimatedVideo()).thenReturn(true);
            assertEquals(stillType, viewType.invoke(adapter, post));
            when(post.isAnimatedVideo()).thenReturn(false);
            assertEquals(playingType, viewType.invoke(adapter, post));
        }
    }

    @Test public void animatedVideoInImageHolderStreamsDirectRenditionAndPreservesPrivacy() throws Exception {
        when(post.getPostType()).thenReturn(Post.VIDEO_TYPE);
        when(post.isAnimatedVideo()).thenReturn(true);
        when(post.getUrl()).thenReturn(null);
        when(post.getMp4Variant()).thenReturn(null);
        when(post.getVideoFallBackDirectUrl()).thenReturn("https://v.redd.it/animated/DASH_720.mp4");
        when(post.getVideoUrl()).thenReturn("https://v.redd.it/animated/HLSPlaylist.m3u8");
        assertFalse(adapter.isAutoplay());
        assertSame(holder.imageView, eligibleView());
        Method start = PostRecyclerViewAdapter.class.getDeclaredMethod("setGifAnimating", RecyclerView.ViewHolder.class, boolean.class);
        start.setAccessible(true);
        start.invoke(adapter, holder, true);
        verify(creator).createMediaSource(Uri.parse(post.getVideoFallBackDirectUrl()), null);
        verify(player).prepare();
        Method release = PostRecyclerViewAdapter.class.getDeclaredMethod("releaseInlineGif", RecyclerView.ViewHolder.class);
        release.setAccessible(true);
        release.invoke(adapter, holder);
        verify(player).release();
        adapter.setGifAutoplay(false);
        assertNull(eligibleView());
        adapter.setGifAutoplay(true);
        set("mDataSavingMode", true);
        assertNull(eligibleView());
        set("mDataSavingMode", false);
        when(post.isSpoiler()).thenReturn(true);
        assertNull(eligibleView());
        when(post.isSpoiler()).thenReturn(false);
        when(post.isNSFW()).thenReturn(true);
        set("mNeedBlurNsfw", true);
        assertNull(eligibleView());
        set("mNeedBlurNsfw", false);
        set("mAutoplayNsfwVideos", false);
        assertNull(eligibleView());
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
