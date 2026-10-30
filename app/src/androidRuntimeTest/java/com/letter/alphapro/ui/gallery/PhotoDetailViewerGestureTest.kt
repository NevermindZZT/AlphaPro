package com.letter.alphapro.ui.gallery

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import com.letter.alphapro.core.ptp.CameraCaptureGroup
import com.letter.alphapro.core.ptp.PtpImageMetadata
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/**
 * Opt in with -PandroidUiRegression=true. Dispatches touches through the production viewer,
 * rather than testing a replacement pager. Robolectric is still not physical-device evidence.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "en-rUS")
@LooperMode(LooperMode.Mode.PAUSED)
class PhotoDetailViewerGestureTest {
    @get:Rule
    val compose = createComposeRule()

    private val groups = (0..2).map { index ->
        CameraCaptureGroup("PHOTO_$index", listOf(PtpImageMetadata(
            handle = index.toLong(), filename = "PHOTO_$index.JPG", fileSize = 1024,
            formatCode = 0x3801, isHeif = false, isRaw = false, captureDate = "2026-10-29"
        )))
    }
    private val callbacks = mutableListOf<Int>()
    private val selected = mutableStateListOf<String>()
    private var batch = emptyList<CameraCaptureGroup>()
    private val echoedIndex = mutableIntStateOf(0)

    @Test
    fun largeImageSwipesBothDirectionsAndSelectionBatchUseSettledPhoto() {
        showViewer()
        compose.onNodeWithContentDescription("Select photo: PHOTO_0").performClick()
        compose.onNodeWithTag(PAGER).performTouchInput { swipeLeft() }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(listOf(1), callbacks) }
        compose.onNodeWithContentDescription("Select photo: PHOTO_1").performClick()
        compose.onNodeWithText("Download selected (2)").performClick()
        compose.runOnIdle { assertEquals(groups.take(2), batch) }

        compose.onNodeWithTag(PAGER).performTouchInput { swipeLeft() }
        compose.waitForIdle()
        compose.onNodeWithTag(PAGER).performTouchInput { swipeRight() }
        compose.waitForIdle()
        compose.onNodeWithTag(PAGER).performTouchInput { swipeRight() }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(listOf(1, 2, 1, 0), callbacks) }
    }

    @Test
    fun pinchAndZoomedPanDoNotPageThenExplicitResetRestoresSwipe() {
        showViewer()
        compose.onNodeWithTag(PAGER).performTouchInput {
            val y = height * 0.5f
            down(0, Offset(width * 0.42f, y))
            down(1, Offset(width * 0.58f, y))
            repeat(12) { step ->
                val spread = (step + 1) * width * 0.015f
                moveTo(0, Offset(width * 0.42f - spread, y))
                moveTo(1, Offset(width * 0.58f + spread, y))
            }
            up(0)
            up(1)
        }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Reset zoom").assertIsDisplayed()
        compose.onNodeWithTag(PAGER).performTouchInput { swipeLeft() }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(emptyList<Int>(), callbacks) }

        compose.onNodeWithContentDescription("Reset zoom").performClick()
        compose.onNodeWithTag(PAGER).performTouchInput { swipeLeft() }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(listOf(1), callbacks) }
    }

    @Test
    fun partiallyDraggedPageIsNotReportedAndChangedEntrySeedDoesNotSnapPager() {
        showViewer()
        compose.onNodeWithTag(PAGER).performTouchInput {
            down(Offset(width * 0.9f, height * 0.5f))
            moveTo(Offset(width * 0.2f, height * 0.5f), delayMillis = 500)
        }
        compose.runOnIdle {
            assertEquals(emptyList<Int>(), callbacks)
            // Simulate a delayed VM/host update while the finger is still down.
            echoedIndex.intValue = 2
        }
        compose.onNodeWithTag(PAGER).performTouchInput { up() }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(listOf(1), callbacks) }
    }

    private fun showViewer() {
        val bitmap = Bitmap.createBitmap(1600, 1200, Bitmap.Config.ARGB_8888)
        compose.setContent {
            MaterialTheme {
                PhotoDetailViewerScreen(
                    photos = groups,
                    initialIndex = echoedIndex.intValue,
                    preview = PhotoPreviewState(handle = 0, bitmap = bitmap),
                    selectedIds = selected,
                    onToggleSelect = { group ->
                        if (group.id in selected) selected.remove(group.id) else selected.add(group.id)
                    },
                    onIndexChange = { index ->
                        callbacks += index
                        echoedIndex.intValue = index
                    },
                    getThumbnail = { bitmap },
                    onRetry = {}, onDismissRequest = {}, onDownloadClick = {},
                    onBatchDownloadClick = { batch = it }
                )
            }
        }
        compose.waitForIdle()
    }

    private companion object {
        const val PAGER = "gallery_viewer_pager"
    }
}
