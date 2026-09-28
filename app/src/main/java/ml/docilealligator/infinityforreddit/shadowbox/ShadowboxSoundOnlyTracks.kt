package ml.docilealligator.infinityforreddit.shadowbox

import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.source.TrackGroupArray

/** Track-group checks shared by the silent-clip preflight and its prepared-player fallback. */
internal object ShadowboxSoundOnlyTracks {
    fun containsTrack(trackGroups: TrackGroupArray, trackType: Int): Boolean =
        (0 until trackGroups.length).any { groupIndex ->
            val group = trackGroups[groupIndex]
            (0 until group.length).any { trackIndex ->
                val mimeType = group.getFormat(trackIndex).sampleMimeType
                when (trackType) {
                    androidx.media3.common.C.TRACK_TYPE_AUDIO -> MimeTypes.isAudio(mimeType)
                    androidx.media3.common.C.TRACK_TYPE_VIDEO -> MimeTypes.isVideo(mimeType)
                    else -> false
                }
            }
        }
}
