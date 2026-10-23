package ml.docilealligator.infinityforreddit.post

import ml.docilealligator.infinityforreddit.TestInfinity
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = TestInfinity::class)
class ParsePostGalleryTest {
    // Shape of r/comics gallery 1xdci6e: image/jpeg, e=Image, s={u,x,y},
    // p=[{u,x,y}], no mp4 key. Content and URLs are synthetic.
    private fun media(id: String, mime: String) = JSONObject().apply {
        put("id", id)
        put("status", "valid")
        put("m", mime)
        put("e", if (mime == "image/gif") "AnimatedImage" else "Image")
        put("s", JSONObject().apply {
            val key = when (mime) {
                "image/gif" -> "gif"
                "video/mp4" -> "mp4"
                else -> "u"
            }
            put(key, "https://i.redd.it/$id.${mime.substringAfter('/')}")
            put("x", 1600)
            put("y", 2400)
        })
        put("p", JSONArray().put(JSONObject().apply {
            put("u", "https://preview.redd.it/$id.jpg?width=1080")
            put("x", 1080)
            put("y", 1620)
        }))
    }

    private fun postJson(vararg items: Pair<String, JSONObject?>) = JSONObject().apply {
        put("id", "gallery1")
        put("name", "t3_gallery1")
        put("subreddit", "comics")
        put("subreddit_name_prefixed", "r/comics")
        put("author", "someone")
        put("distinguished", JSONObject.NULL)
        put("created_utc", 1789017851L)
        put("title", "Gallery")
        put("score", 10)
        put("num_comments", 3)
        put("upvote_ratio", 0.95)
        for (key in listOf("hidden", "spoiler", "over_18", "stickied", "archived",
            "locked", "saved", "can_mod_post", "is_video")) put(key, false)
        put("send_replies", true)
        put("likes", JSONObject.NULL)
        put("permalink", "/r/comics/comments/gallery1/gallery/")
        put("thumbnail", "")
        put("domain", "reddit.com")
        put("url", "https://www.reddit.com/gallery/gallery1")
        put("selftext", "")
        put("selftext_html", JSONObject.NULL)
        put("is_gallery", true)
        put("gallery_data", JSONObject().put("items", JSONArray().apply {
            items.forEach { (id, _) -> put(JSONObject().put("media_id", id)) }
        }))
        put("media_metadata", JSONObject().apply {
            items.forEach { (id, metadata) -> if (metadata != null) put(id, metadata) }
        })
    }

    @Test
    fun `four JPEG images remain a gallery with previews and image viewer types`() {
        val post = ParsePost.parseBasicData(postJson(
            *(1..4).map { "image$it" to media("image$it", "image/jpeg") }.toTypedArray()
        ))
        assertEquals(Post.GALLERY_TYPE, post.postType)
        assertEquals(4, post.gallery.size)
        post.gallery.forEachIndexed { index, item ->
            val id = "image${index + 1}"
            assertEquals("image/jpeg", item.mimeType)
            assertEquals(Post.Gallery.TYPE_IMAGE, item.mediaType)
            assertEquals("https://i.redd.it/$id.jpeg", item.url)
            assertEquals("https://preview.redd.it/$id.jpg?width=1080", item.feedPreviewUrl)
            assertTrue(item.hasFallback())
            assertEquals("https://i.redd.it/$id.jpeg", item.fallbackUrl)
        }
        assertEquals("https://i.redd.it/image1.jpeg", post.previews[0].previewUrl)
        assertEquals(1600, post.previews[0].previewWidth)
        assertEquals(2400, post.previews[0].previewHeight)
    }

    @Test
    fun `PNG JPG and other still image MIME types use image sources`() {
        for (mime in listOf("image/png", "image/jpg", "image/webp")) {
            val post = ParsePost.parseBasicData(postJson("still" to media("still", mime)))
            assertEquals(mime, Post.GALLERY_TYPE, post.postType)
            assertEquals(Post.Gallery.TYPE_IMAGE, post.gallery.single().mediaType)
            assertEquals("https://i.redd.it/still.${mime.substringAfter('/')}", post.gallery.single().url)
        }
    }

    @Test
    fun `mixed gallery keeps image GIF and video sources in order`() {
        val post = ParsePost.parseBasicData(postJson(
            "still" to media("still", "image/png"),
            "animated" to media("animated", "image/gif"),
            "clip" to media("clip", "video/mp4")
        ))
        assertEquals(Post.GALLERY_TYPE, post.postType)
        assertEquals(listOf(Post.Gallery.TYPE_IMAGE, Post.Gallery.TYPE_GIF, Post.Gallery.TYPE_VIDEO),
            post.gallery.map { it.mediaType })
        assertEquals(listOf("https://i.redd.it/still.png", "https://i.redd.it/animated.gif",
            "https://i.redd.it/clip.mp4"), post.gallery.map { it.url })
    }

    @Test
    fun `deleted and malformed items preserve good neighbors`() {
        val missingSourceUrl = media("broken", "image/png").apply {
            getJSONObject("s").remove("u")
        }
        val brokenPreview = media("last", "image/png").apply {
            put("p", JSONArray().put(JSONObject.NULL).put(JSONObject().put("u", "bad").put("x", "bad")))
        }
        val post = ParsePost.parseBasicData(postJson(
            "first" to media("first", "image/png"), "deleted" to null,
            "failed" to JSONObject().put("status", "failed"),
            "broken" to missingSourceUrl, "last" to brokenPreview
        ))
        assertEquals(Post.GALLERY_TYPE, post.postType)
        assertEquals(listOf("https://i.redd.it/first.png", "https://i.redd.it/last.png"),
            post.gallery.map { it.url })
        assertNull(post.gallery.last().feedPreviewUrl)
    }

    @Test
    fun `gallery with no surviving media remains text`() {
        val post = ParsePost.parseBasicData(postJson("deleted" to null))
        assertEquals(Post.TEXT_TYPE, post.postType)
        assertNull(post.url)
    }
}
