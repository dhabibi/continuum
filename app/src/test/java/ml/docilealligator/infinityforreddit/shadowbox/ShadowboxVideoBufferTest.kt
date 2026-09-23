package ml.docilealligator.infinityforreddit.shadowbox

import android.app.Application
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.SinglePeriodTimeline
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocation
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ShadowboxVideoBufferTest {
    private val playerId = PlayerId("tiktok-buffer-test")
    private val timeline = SinglePeriodTimeline(
        120_000_000L, true, false, false, null,
        MediaItem.fromUri("https://media.example/video.mp4"),
    )

    @Test fun `video buffering stops within a small per-player memory budget`() {
        val control = ShadowboxVideoPageFragment.createLoadControl()
        val group = TrackGroup(Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).build())
        val selection = mock(ExoTrackSelection::class.java)
        `when`(selection.trackGroup).thenReturn(group)
        val parameters = parameters(5_000_000L)
        control.onPrepared(playerId)
        control.onTracksSelected(parameters, TrackGroupArray(group), arrayOf(selection))
        val allocator = control.getAllocator(playerId)
        val allocations = mutableListOf<Allocation>()
        try {
            assertTrue(control.shouldContinueLoading(parameters))
            // The default selected-video budget exceeds 100 MiB. A short-form pager must stop
            // well before that, even while it has less than the normal 50 seconds buffered.
            val safetyLimit = 20 * 1024 * 1024
            while (control.shouldContinueLoading(parameters) && allocator.totalBytesAllocated < safetyLimit) {
                allocations.add(allocator.allocate())
            }
            assertFalse("Video still loading after ${allocator.totalBytesAllocated} bytes",
                control.shouldContinueLoading(parameters))
            assertTrue(allocator.totalBytesAllocated <= safetyLimit)
        } finally {
            try {
                allocations.forEach { allocator.release(it) }
                assertEquals(0, allocator.totalBytesAllocated)
            } finally {
                // Media3 removes the player's allocation tracker here; its scoped allocator
                // cannot be queried after release.
                control.onReleased(playerId)
            }
        }
    }

    @Test fun `memory budgeting preserves normal start and rebuffer timing`() {
        val control = ShadowboxVideoPageFragment.createLoadControl()
        control.onPrepared(playerId)
        try {
            assertFalse(control.shouldStartPlayback(parameters(500_000L)))
            assertTrue(control.shouldStartPlayback(parameters(1_000_000L)))
            assertFalse(control.shouldStartPlayback(parameters(1_000_000L, rebuffering = true)))
            assertTrue(control.shouldStartPlayback(parameters(2_000_000L, rebuffering = true)))
        } finally {
            control.onReleased(playerId)
        }
    }

    private fun parameters(bufferedDurationUs: Long, rebuffering: Boolean = false) = LoadControl.Parameters(
        playerId, timeline, MediaSource.MediaPeriodId(timeline.getUidOfPeriod(0)),
        0L, bufferedDurationUs, 1f, true, rebuffering, C.TIME_UNSET, C.TIME_UNSET,
    )
}
