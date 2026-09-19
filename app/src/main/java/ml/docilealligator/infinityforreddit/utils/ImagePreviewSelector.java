package ml.docilealligator.infinityforreddit.utils;

import androidx.annotation.Nullable;
import java.util.List;
import ml.docilealligator.infinityforreddit.post.Post;

/** Chooses supplied image representations; never fabricates signed CDN URLs or changes originals. */
public final class ImagePreviewSelector {
    private ImagePreviewSelector() {}

    @Nullable
    public static Post.Preview select(List<Post.Preview> previews, int width, int height, long maxPixels) {
        if (width <= 0 || height <= 0) return null;
        Post.Preview source = null;
        for (Post.Preview candidate : previews) {
            if (candidate.getPreviewWidth() > 0 && candidate.getPreviewHeight() > 0
                    && (source == null || pixels(candidate) > pixels(source))) {
                source = candidate;
            }
        }
        if (source == null) return null;
        double scale = Math.min(1d, Math.min((double) width / source.getPreviewWidth(),
                (double) height / source.getPreviewHeight()));
        int neededWidth = (int) Math.ceil(source.getPreviewWidth() * scale);
        int neededHeight = (int) Math.ceil(source.getPreviewHeight() * scale);
        Post.Preview best = null;
        Post.Preview fallback = null;
        for (Post.Preview candidate : previews) {
            if (candidate.getPreviewWidth() <= 0 || candidate.getPreviewHeight() <= 0) continue;
            long pixelCount = pixels(candidate);
            if (pixelCount <= 0 || pixelCount > maxPixels) continue;
            if (fallback == null || pixelCount > pixels(fallback)) fallback = candidate;
            if (candidate.getPreviewWidth() >= neededWidth && candidate.getPreviewHeight() >= neededHeight
                    && (best == null || pixelCount < pixels(best))) {
                best = candidate;
            }
        }
        return best == null ? fallback : best;
    }

    private static long pixels(Post.Preview preview) {
        return (long) preview.getPreviewWidth() * preview.getPreviewHeight();
    }
}
