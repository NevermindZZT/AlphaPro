package com.letter.alphapro.ui.gallery

import android.app.Application
import android.graphics.Bitmap
import androidx.exifinterface.media.ExifInterface
import com.letter.alphapro.core.heif.HeifInput
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Framework JPEG/EXIF and production decoder routing, not physical-device HEIF420 support. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "en-rUS")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CameraBitmapDecoderRoutingTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun jpeg(): ByteArray = ByteArrayOutputStream().use { output ->
        val image = Bitmap.createBitmap(800, 400, Bitmap.Config.ARGB_8888)
        try { check(image.compress(Bitmap.CompressFormat.JPEG, 95, output)) }
        finally { image.recycle() }
        output.toByteArray()
    }

    @Test fun platformReadableThumbnailIsBoundedAndEncodedBytesAreUnchanged() {
        val encoded = jpeg()
        val original = encoded.copyOf()
        val result = CameraBitmapDecoder.decodeDetailed(encoded, 200)!!
        assertEquals(PreviewDecoderKind.SYSTEM, result.decoder)
        assertEquals(200, result.image.width)
        assertEquals(100, result.image.height)
        assertArrayEquals(original, encoded)
        result.image.recycle()
    }

    @Test fun heifMetadataDoesNotForceFilePreviewThroughUnavailableNativeLibrary() {
        val file = temporary.newFile("camera-rendering.hif")
        val original = jpeg()
        file.writeBytes(original)
        // H3 unconditionally entered native when heif=true, even for a system-readable rendering.
        val result = CameraBitmapDecoder.decodeFilePreviewDetailed(file, 200, heif = true)!!
        assertEquals(PreviewDecoderKind.SYSTEM, result.decoder)
        assertEquals(200, result.image.width)
        assertEquals(100, result.image.height)
        assertArrayEquals(original, file.readBytes())
        assertEquals(setOf(file.name), temporary.root.listFiles()!!.map { it.name }.toSet())
        result.image.recycle()
    }

    @Test fun systemFileExifOrientationIsAppliedExactlyOnce() {
        val file = temporary.newFile("portrait.jpg")
        file.writeBytes(jpeg())
        ExifInterface(file).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }
        val original = file.readBytes()
        val result = CameraBitmapDecoder.decodeFilePreviewDetailed(file, 200, heif = true)!!
        assertEquals(PreviewDecoderKind.SYSTEM, result.decoder)
        assertEquals(100, result.image.width)
        assertEquals(200, result.image.height)
        assertArrayEquals(original, file.readBytes())
        result.image.recycle()
    }

    @Test fun failedPrimaryCanDisplayActualCompanionJpegWithoutSoftwareOrOriginalRead() = runBlocking {
        // Deliberately undecodable HEIF header, not a claimed real Sony422 fixture.
        val badHeif = byteArrayOf(0, 0, 0, 16) + "ftypheix".toByteArray() + ByteArray(4)
        val renderedJpeg = jpeg()
        val sourceBytes = renderedJpeg.copyOf()
        val result = ThumbnailPipeline.load(
            sources = listOf("HIF", "RAW"), sourceName = { it },
            small = { if (it == "HIF") badHeif else renderedJpeg },
            rendered = { error("Companion small rendering already works") },
            platform = { _, bytes -> CameraBitmapDecoder.decodeDetailed(bytes, 200, allowSoftwareFallback = false)?.image },
            mayUseSoftware = HeifInput::isHeif,
            software = { _, _ -> error("Must not enter native when camera JPEG works") }
        )
        assertEquals("RAW", result.source)
        assertFalse(result.software)
        assertEquals(200, result.image.width)
        assertEquals(100, result.image.height)
        assertArrayEquals(sourceBytes, renderedJpeg)
        assertTrue(temporary.root.listFiles()!!.isEmpty())
        result.image.recycle()
    }
}
