package ml.docilealligator.infinityforreddit.shadowbox

import androidx.lifecycle.ViewModel

/** Rotation-retained admission decisions for the feed held by the activity's other ViewModel. */
internal class ShadowboxSoundOnlyState : ViewModel() {
    val confirmed = mutableMapOf<String, String>()
    val excluded = mutableMapOf<String, String>()
    var nextPostIndex = 0

    fun reset() {
        confirmed.clear()
        excluded.clear()
        nextPostIndex = 0
    }
}
