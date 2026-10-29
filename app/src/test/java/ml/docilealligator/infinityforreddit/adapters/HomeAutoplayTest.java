package ml.docilealligator.infinityforreddit.adapters;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.app.Application;
import android.content.Context;
import android.net.Uri;
import android.os.Looper;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.widget.ImageView;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.SilenceMediaSource;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.DefaultTimeBar;
import androidx.media3.ui.PlayerView;
import androidx.test.core.app.ApplicationProvider;
import java.lang.reflect.Field;
import java.time.Duration;
import ml.docilealligator.infinityforreddit.R;
import ml.docilealligator.infinityforreddit.activities.BaseActivity;
import ml.docilealligator.infinityforreddit.customviews.MaxHeightSquareFrameLayout;
import ml.docilealligator.infinityforreddit.fragments.PostFragmentBase;
import ml.docilealligator.infinityforreddit.post.Post;
import ml.docilealligator.infinityforreddit.videoautoplay.DefaultExoCreator;
import ml.docilealligator.infinityforreddit.videoautoplay.ToroExo;
import ml.docilealligator.infinityforreddit.videoautoplay.media.PlaybackInfo;
import ml.docilealligator.infinityforreddit.videoautoplay.widget.Container;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import pl.droidsonroids.gif.GifImageView;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 34)
public class HomeAutoplayTest {
    @Test
    public void firstAutoplayCanPrepareReceiveAudioTracksAndRelease() throws Exception {
        Context app = ApplicationProvider.getApplicationContext();
        Context context = new ContextThemeWrapper(app, R.style.AppTheme);
        PostRecyclerViewAdapter adapter = mock(PostRecyclerViewAdapter.class);
        DefaultExoCreator creator = new DefaultExoCreator(app,
                new ml.docilealligator.infinityforreddit.videoautoplay.Config.Builder(app).build()) {
            @Override
            public MediaSource createMediaSource(Uri uri, String ext) {
                return new SilenceMediaSource.Factory().setDurationUs(5_000_000).createMediaSource();
            }
        };
        android.content.SharedPreferences preferences = app.getSharedPreferences("autoplay-test", Context.MODE_PRIVATE);
        setField(adapter, "mSharedPreferences", preferences);
        setField(adapter, "mExoCreator", creator);
        setField(adapter, "mActivity", mock(BaseActivity.class));
        setField(adapter, "mFragment", mock(PostFragmentBase.class));
        Post post = mock(Post.class);
        when(post.getVideoUrl()).thenReturn("https://example.com/video.mp4");
        when(post.isNormalVideo()).thenReturn(true);
        PlayerView playerView = new PlayerView(context);
        PostRecyclerViewAdapter.VideoAutoplayImpl row = adapter.new VideoAutoplayImpl(
                new View(context), new MaxHeightSquareFrameLayout(context),
                new AspectRatioFrameLayout(context), new GifImageView(context),
                new ImageView(context), playerView, new ImageView(context), new ImageView(context),
                new ImageView(context), new ImageView(context), new DefaultTimeBar(context), null, null) {
            @Override int getAdapterPosition() { return 0; }
            @Override Post getPost() { return post; }
            @Override void markPostRead(Post p, boolean changeColor) {}
            @Override public int getPlayerOrder() { return 0; }
        };
        try {
            row.loadVideo();
            row.initialize(new Container(context), new PlaybackInfo());
            // The preloader asks this after initialize but before play allocates the player.
            org.junit.Assert.assertFalse(row.isBuffering());
            row.play();
            for (int i = 0; i < 100 && row.helper.getPlayer().getPlaybackState() != Player.STATE_READY; i++) {
                Thread.sleep(10);
                Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10));
            }
            assertNull(row.helper.getPlayer().getPlayerError());
            assertEquals(Player.STATE_READY, row.helper.getPlayer().getPlaybackState());
            assertEquals(Player.REPEAT_MODE_ALL, row.helper.getPlayer().getRepeatMode());
            preferences.edit().putBoolean(ml.docilealligator.infinityforreddit.utils.SharedPreferencesUtils.LOOP_VIDEO, false).apply();
            row.play();
            assertEquals(Player.REPEAT_MODE_OFF, row.helper.getPlayer().getRepeatMode());
            when(post.isAnimatedVideo()).thenReturn(true);
            row.play();
            assertEquals(Player.REPEAT_MODE_ONE, row.helper.getPlayer().getRepeatMode());
            when(post.isAnimatedVideo()).thenReturn(false);
            row.play();
            assertEquals(Player.REPEAT_MODE_OFF, row.helper.getPlayer().getRepeatMode());
        } finally {
            ml.docilealligator.infinityforreddit.videoautoplay.ExoPlayerViewHelper retained = row.helper;
            row.release();
            org.junit.Assert.assertFalse(row.isBuffering());
            if (retained != null) org.junit.Assert.assertFalse(retained.isBuffering());
            ToroExo.with(app).cleanUp();
        }
    }

    @Test
    public void unboundVideoRowCannotEnterAutoplaySelection() throws Exception {
        Context app = ApplicationProvider.getApplicationContext();
        Context context = new ContextThemeWrapper(app, R.style.AppTheme);
        PostRecyclerViewAdapter adapter = mock(PostRecyclerViewAdapter.class);
        setField(adapter, "canPlayVideo", true);
        PostRecyclerViewAdapter.VideoAutoplayImpl row = adapter.new VideoAutoplayImpl(
                new View(context), new MaxHeightSquareFrameLayout(context),
                new AspectRatioFrameLayout(context), new GifImageView(context),
                new ImageView(context), new PlayerView(context), new ImageView(context), new ImageView(context),
                new ImageView(context), new ImageView(context), new DefaultTimeBar(context), null, null) {
            @Override int getAdapterPosition() { return -1; }
            @Override Post getPost() { return null; }
            @Override void markPostRead(Post p, boolean changeColor) { }
            @Override public int getPlayerOrder() { return -1; }
        };
        org.junit.Assert.assertFalse("Unbound rows are not playable", row.wantsToPlay());
    }

    private static void setField(Object instance, String name, Object value) throws Exception {
        Field field = PostRecyclerViewAdapter.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(instance, value);
    }
}
