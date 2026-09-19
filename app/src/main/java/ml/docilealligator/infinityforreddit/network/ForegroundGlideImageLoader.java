package ml.docilealligator.infinityforreddit.network;

import android.content.Context;
import android.net.Uri;
import com.bumptech.glide.Priority;
import com.bumptech.glide.request.target.Target;
import com.github.piasy.biv.loader.glide.GlideImageLoader;
import java.io.File;
import ml.docilealligator.infinityforreddit.ImageOkHttpClient;
import okhttp3.OkHttpClient;

/** Keeps user-opened originals ahead of speculative image work, using the existing loader/cache. */
public final class ForegroundGlideImageLoader extends GlideImageLoader {
    private ForegroundGlideImageLoader(Context context, OkHttpClient client) {
        super(context, client);
    }

    public static ForegroundGlideImageLoader with(Context context, OkHttpClient client) {
        return new ForegroundGlideImageLoader(context, client);
    }

    public static ForegroundGlideImageLoader with(Context context) {
        return with(context.getApplicationContext(), ImageOkHttpClient.get(context));
    }

    @Override
    protected void downloadImageInto(Uri uri, Target<File> target) {
        mRequestManager.downloadOnly().load(uri).priority(Priority.HIGH).into(target);
    }

    @Override
    public void prefetch(Uri uri) {
        mRequestManager.downloadOnly().load(uri).priority(Priority.LOW).preload();
    }
}
