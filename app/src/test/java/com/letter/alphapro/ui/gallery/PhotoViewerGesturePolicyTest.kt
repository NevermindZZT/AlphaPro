package com.letter.alphapro.ui.gallery

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Policy boundaries only: these tests do not dispatch or prove real Compose gestures. */
class PhotoViewerGesturePolicyTest {
    @Test
    fun fitScaleOneFingerSequenceNeverConsumesPagerDragOrRelease() {
        val policy = PhotoViewerGesturePolicy()
        repeat(20) { assertFalse(policy.shouldConsume(1, isZoomed = false)) }
        assertFalse(policy.shouldConsume(0, isZoomed = false))
    }

    @Test
    fun secondFingerClaimsSequenceEvenBeforeZoomChanges() {
        val policy = PhotoViewerGesturePolicy()
        assertFalse(policy.shouldConsume(1, isZoomed = false))
        assertTrue(policy.shouldConsume(2, isZoomed = false))
        assertTrue(policy.shouldConsume(3, isZoomed = false))
    }

    @Test
    fun pinchOwnershipSurvivesReturnToFitAndRemainingFingerThroughRelease() {
        val policy = PhotoViewerGesturePolicy()
        assertTrue(policy.shouldConsume(2, isZoomed = true))
        assertTrue(policy.shouldConsume(2, isZoomed = false))
        assertTrue(policy.shouldConsume(1, isZoomed = false))
        assertTrue(policy.shouldConsume(0, isZoomed = false))
        // The pointer handler creates a fresh policy only after all pointers are up.
        assertFalse(PhotoViewerGesturePolicy().shouldConsume(1, isZoomed = false))
    }

    @Test
    fun zoomedOneFingerPanOwnsSequenceEvenWhenResetWhileFingerDown() {
        val policy = PhotoViewerGesturePolicy()
        assertTrue(policy.shouldConsume(1, isZoomed = true))
        assertTrue(policy.shouldConsume(1, isZoomed = false))
        assertTrue(policy.shouldConsume(0, isZoomed = false))
    }
}
