package com.letter.alphapro.ui.gallery

/**
 * Ownership lasts until every pointer is up, even if a pinch returns to fit or loses a finger.
 * At fit, a one-finger sequence belongs exclusively to the pager (including its touch slop).
 * This policy is independent of Compose so its boundary cases can be tested deterministically.
 */
internal class PhotoViewerGesturePolicy {
    private var ownsGesture = false

    fun shouldConsume(pressedPointers: Int, isZoomed: Boolean): Boolean {
        if (pressedPointers >= 2 || (pressedPointers > 0 && isZoomed)) {
            ownsGesture = true
        }
        return ownsGesture
    }
}
