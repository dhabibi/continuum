package ml.docilealligator.infinityforreddit.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import ml.docilealligator.infinityforreddit.post.Post;
import org.junit.jupiter.api.Test;

class ImagePreviewSelectorTest {
    private static Post.Preview preview(int width, int height) {
        return new Post.Preview("https://example.com/" + width, width, height, "", "");
    }

    @Test
    void choosesSuppliedPixelsForTheActualDisplayInsteadOfTheLargestSource() {
        List<Post.Preview> sizes = List.of(preview(3731, 4975), preview(320, 427),
                preview(640, 853), preview(1080, 1440));
        assertEquals(640, ImagePreviewSelector.select(sizes, 540, 720, 5_000_000).getPreviewWidth());
        assertEquals(1080, ImagePreviewSelector.select(sizes, 1080, 1440, 5_000_000).getPreviewWidth());
    }

    @Test
    void accountsForLetterboxingAndHonorsThePixelCap() {
        List<Post.Preview> sizes = List.of(preview(1600, 1200), preview(320, 240),
                preview(640, 480), preview(1080, 810));
        assertEquals(640, ImagePreviewSelector.select(sizes, 1080, 400, 5_000_000).getPreviewWidth());
        assertEquals(1080, ImagePreviewSelector.select(sizes, 1440, 1080, 1_000_000).getPreviewWidth());
    }

    @Test
    void toleratesMissingSizesWithoutInventingUrls() {
        assertNull(ImagePreviewSelector.select(List.of(), 400, 400, 5_000_000));
        assertNull(ImagePreviewSelector.select(List.of(preview(0, 0)), 400, 400, 5_000_000));
        assertNull(ImagePreviewSelector.select(List.of(preview(640, 480)), 0, 0, 5_000_000));
    }
}
