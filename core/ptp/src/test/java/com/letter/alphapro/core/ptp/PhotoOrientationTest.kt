package com.letter.alphapro.core.ptp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class PhotoOrientationTest {
    @Test
    fun allEightTransformPlansMatchExif() {
        val expected = listOf(
            OrientationTransform(0f, 1f, 1f),
            OrientationTransform(0f, -1f, 1f),
            OrientationTransform(180f, 1f, 1f),
            OrientationTransform(0f, 1f, -1f),
            OrientationTransform(90f, -1f, 1f),
            OrientationTransform(90f, 1f, 1f),
            OrientationTransform(90f, 1f, -1f),
            OrientationTransform(270f, 1f, 1f)
        )
        expected.forEachIndexed { index, plan ->
            assertEquals("orientation ${index + 1}", plan, PhotoOrientation.transform(index + 1))
        }
        for (invalid in listOf(Int.MIN_VALUE, -1, 0, 9, Int.MAX_VALUE)) {
            assertEquals(expected.first(), PhotoOrientation.transform(invalid))
        }
    }

    @Test
    fun rotateThenOutputAxisScaleMapsEveryCornerIncludingTransposeAndTransverse() {
        // Labelled corners of a non-square image expose wrong mirror axes and rotate order.
        val corners = listOf(0f to 0f, 3f to 0f, 3f to 2f, 0f to 2f)
        val expected = listOf(
            listOf(0f to 0f, 3f to 0f, 3f to 2f, 0f to 2f),
            listOf(3f to 0f, 0f to 0f, 0f to 2f, 3f to 2f),
            listOf(3f to 2f, 0f to 2f, 0f to 0f, 3f to 0f),
            listOf(0f to 2f, 3f to 2f, 3f to 0f, 0f to 0f),
            listOf(0f to 0f, 0f to 3f, 2f to 3f, 2f to 0f),
            listOf(2f to 0f, 2f to 3f, 0f to 3f, 0f to 0f),
            listOf(2f to 3f, 2f to 0f, 0f to 0f, 0f to 3f),
            listOf(0f to 3f, 0f to 0f, 2f to 0f, 2f to 3f)
        )
        for (orientation in 1..8) {
            val plan = PhotoOrientation.transform(orientation)
            val radians = Math.toRadians(plan.rotationDegrees.toDouble())
            val transformed = corners.map { (x, y) ->
                // Android Matrix.setRotate().postScale() is S * R, not R * S.
                ((cos(radians) * x - sin(radians) * y) * plan.scaleX).toFloat() to
                    ((sin(radians) * x + cos(radians) * y) * plan.scaleY).toFloat()
            }
            val minX = transformed.minOf { it.first }
            val minY = transformed.minOf { it.second }
            transformed.forEachIndexed { index, (x, y) ->
                val (expectedX, expectedY) = expected[orientation - 1][index]
                assertEquals("orientation $orientation corner $index x", expectedX, x - minX, 0.0001f)
                assertEquals("orientation $orientation corner $index y", expectedY, y - minY, 0.0001f)
            }
        }
    }

    @Test
    fun readsAllOrientationsFromBothEndianTiffAndJpeg() {
        for (littleEndian in listOf(true, false)) {
            for (orientation in 1..8) {
                val tiff = tiff(orientation, littleEndian)
                assertEquals(orientation, PhotoOrientation.readExifOrientation(tiff))
                assertEquals(orientation, PhotoOrientation.readExifOrientation(jpeg(tiff)))
            }
        }
    }

    @Test
    fun distinguishesExplicitNormalFromAbsentOrInvalidTag() {
        for (littleEndian in listOf(true, false)) {
            val absent = tiff(1, littleEndian).apply { putShort(10, 0x0100, littleEndian) }
            assertNull(PhotoOrientation.readExifOrientation(absent))
            assertNull(PhotoOrientation.readExifOrientation(jpeg(absent)))
            val emptyIfd = tiff(1, littleEndian).apply { putShort(8, 0, littleEndian) }
            assertNull(PhotoOrientation.readExifOrientation(emptyIfd))
            for (invalid in listOf(0, 9, 0xffff)) {
                assertNull(PhotoOrientation.readExifOrientation(tiff(invalid, littleEndian)))
            }
        }
    }

    @Test
    fun partialOriginalHeaderNeedsCompleteEntryButNotImageOrIfdTrailer() {
        for (littleEndian in listOf(true, false)) {
            val tiff = tiff(7, littleEndian)
            // TIFF header (8), entry count (2), complete orientation entry (12).
            for (size in 0 until 22) {
                assertNull("TIFF prefix " + size, PhotoOrientation.readExifOrientation(tiff.copyOf(size)))
            }
            assertEquals(7, PhotoOrientation.readExifOrientation(tiff.copyOf(22)))
            val jpeg = jpeg(tiff)
            // JPEG SOI (2), APP1 marker/length (4), Exif signature (6), TIFF entry (22).
            for (size in 0 until 34) {
                assertNull("JPEG prefix " + size, PhotoOrientation.readExifOrientation(jpeg.copyOf(size)))
            }
            assertEquals(7, PhotoOrientation.readExifOrientation(jpeg.copyOf(34)))
        }
    }

    @Test
    fun skipsOtherSegmentsAndUnknownIfdTags() {
        val original = tiff(5, false)
        val twoEntries = ByteArray(38)
        original.copyInto(twoEntries, endIndex = 10)
        twoEntries.putShort(8, 2, false)
        // First entry is an unrelated width tag; second holds orientation.
        original.copyInto(twoEntries, destinationOffset = 10, startIndex = 10, endIndex = 22)
        twoEntries.putShort(10, 0x0100, false)
        original.copyInto(twoEntries, destinationOffset = 22, startIndex = 10, endIndex = 22)
        assertEquals(5, PhotoOrientation.readExifOrientation(twoEntries))
        val prefix = segment(0xe0, byteArrayOf(1, 2, 3)) + segment(0xe1, "not Exif".toByteArray())
        val image = byteArrayOf(0xff.toByte(), 0xd8.toByte()) + prefix + jpeg(twoEntries).drop(2).toByteArray()
        assertEquals(5, PhotoOrientation.readExifOrientation(image))
        // Marker fill bytes must not shift the segment-length interpretation.
        val padded = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte()) + jpeg(original).drop(2).toByteArray()
        assertEquals(5, PhotoOrientation.readExifOrientation(padded))
    }

    @Test
    fun rejectsMalformedTiffWithoutFollowingOffsetsOrInventingOrientation() {
        for (littleEndian in listOf(true, false)) {
            val malformed = listOf(
                tiff(6, littleEndian).apply { this[0] = 0 },
                tiff(6, littleEndian).apply { putShort(2, 43, littleEndian) },
                tiff(6, littleEndian).apply { putInt(4, 0, littleEndian) },
                tiff(6, littleEndian).apply { putInt(4, -1, littleEndian) },
                tiff(6, littleEndian).apply { putInt(4, Int.MAX_VALUE, littleEndian) },
                tiff(6, littleEndian).apply { putShort(8, 0xffff, littleEndian) },
                tiff(6, littleEndian).apply { putShort(12, 4, littleEndian) },
                tiff(6, littleEndian).apply { putInt(14, 0, littleEndian) },
                tiff(6, littleEndian).apply { putInt(14, 2, littleEndian) },
                tiff(6, littleEndian).apply { putInt(14, -1, littleEndian) }
            )
            for (bytes in malformed) {
                assertNull(PhotoOrientation.readExifOrientation(bytes))
                assertNull(PhotoOrientation.readExifOrientation(jpeg(bytes)))
            }
        }
    }

    @Test
    fun jpegLengthsAndScanBoundaryAreRespected() {
        val valid = jpeg(tiff(6, true))
        val malformed = listOf(
            byteArrayOf(),
            byteArrayOf(0xff.toByte()),
            valid.copyOf().apply { this[4] = 0; this[5] = 1 }, // Impossible length.
            valid.copyOf().apply { this[4] = 0; this[5] = 8 }, // TIFF is outside APP1.
            valid.copyOf().apply { this[11] = 1 }, // Bad Exif signature.
            byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0, 0),
            byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte()),
            byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xda.toByte()) + valid,
            byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xd9.toByte()) + valid,
            // A truncated unrelated segment cannot be skipped to find a spurious Exif tag.
            byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xe0.toByte(), 0x7f, 0xff.toByte()) + valid
        )
        malformed.forEach { assertNull(PhotoOrientation.readExifOrientation(it)) }
    }

    @Test
    fun headerScanAndIfdEntryCountAreBounded() {
        val header = tiff(8, true)
        val beyondLimit = ByteArray(1024 * 1024 + 32)
        header.copyInto(beyondLimit, endIndex = 8)
        beyondLimit.putInt(4, 1024 * 1024, true)
        header.copyInto(beyondLimit, destinationOffset = 1024 * 1024, startIndex = 8)
        assertNull(PhotoOrientation.readExifOrientation(beyondLimit))
        header.putShort(8, 4097, true)
        assertNull(PhotoOrientation.readExifOrientation(header))
    }

    @Test
    fun arbitraryShortInputsAreSafe() {
        val random = Random(112)
        repeat(2000) {
            val bytes = random.nextBytes(random.nextInt(0, 256))
            assertNull(PhotoOrientation.readExifOrientation(bytes))
            if (bytes.size >= 2) {
                bytes[0] = 0xff.toByte()
                bytes[1] = 0xd8.toByte()
                assertNull(PhotoOrientation.readExifOrientation(bytes))
            }
        }
    }

    private fun tiff(orientation: Int, littleEndian: Boolean): ByteArray = ByteArray(26).apply {
        this[0] = if (littleEndian) 0x49 else 0x4d
        this[1] = this[0]
        putShort(2, 42, littleEndian)
        putInt(4, 8, littleEndian)
        putShort(8, 1, littleEndian)
        putShort(10, 0x0112, littleEndian)
        putShort(12, 3, littleEndian)
        putInt(14, 1, littleEndian)
        putShort(18, orientation, littleEndian)
    }

    private fun jpeg(tiff: ByteArray): ByteArray =
        byteArrayOf(0xff.toByte(), 0xd8.toByte()) +
            segment(0xe1, byteArrayOf(0x45, 0x78, 0x69, 0x66, 0, 0) + tiff) +
            byteArrayOf(0xff.toByte(), 0xd9.toByte())

    private fun segment(marker: Int, payload: ByteArray): ByteArray {
        val length = payload.size + 2
        return byteArrayOf(0xff.toByte(), marker.toByte(), (length shr 8).toByte(), length.toByte()) + payload
    }

    private fun ByteArray.putShort(offset: Int, value: Int, littleEndian: Boolean) {
        ByteBuffer.wrap(this).order(if (littleEndian) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN)
            .putShort(offset, value.toShort())
    }

    private fun ByteArray.putInt(offset: Int, value: Int, littleEndian: Boolean) {
        ByteBuffer.wrap(this).order(if (littleEndian) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN)
            .putInt(offset, value)
    }
}
