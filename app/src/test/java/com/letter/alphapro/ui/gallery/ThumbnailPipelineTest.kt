package com.letter.alphapro.ui.gallery

import java.io.IOException
import java.util.concurrent.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ThumbnailPipelineTest {
    private val encoded = byteArrayOf(1, 2, 3, 4)

    @Test fun supportedPrimaryUsesNoAlternatePreviewSoftwareOrExport() = runBlocking {
        val calls = mutableListOf<String>()
        val before = encoded.copyOf()
        val result = ThumbnailPipeline.load(
            sources = listOf("HIF", "RAW"), sourceName = { it },
            small = { calls += "small:$it"; encoded }, rendered = { error("No larger image needed") },
            platform = { source, _ -> calls += "platform:$source"; "system image" },
            mayUseSoftware = { error("Platform succeeded") }, software = { _, _ -> error("Platform succeeded") }
        )
        assertEquals(listOf("small:HIF", "platform:HIF"), calls)
        assertEquals("HIF", result.source)
        assertFalse(result.software)
        assertArrayEquals(before, encoded)
    }

    @Test fun undecodableHeifUsesCompanionRawRenderingBeforeAnySoftware() = runBlocking {
        val calls = mutableListOf<String>()
        val result = ThumbnailPipeline.load(
            sources = listOf("HIF", "RAW"), sourceName = { it },
            small = { calls += "small:$it"; encoded }, rendered = { error("RAW thumb succeeded") },
            platform = { source, _ -> calls += "platform:$source"; if (source == "RAW") "JPEG thumbnail" else null },
            mayUseSoftware = { error("No software inspection") }, software = { _, _ -> error("No software") }
        )
        assertEquals("RAW", result.source)
        assertEquals("JPEG thumbnail", result.image)
        assertFalse(result.software)
        assertEquals(listOf("small:HIF", "platform:HIF", "small:RAW", "platform:RAW"), calls)
    }

    @Test fun emptyPrimaryAlsoFallsBackToCompanion() = runBlocking {
        val result = ThumbnailPipeline.load(
            sources = listOf("HIF", "RAW"), sourceName = { it },
            small = { if (it == "HIF") byteArrayOf() else encoded }, rendered = { null },
            platform = { _, _ -> "RAW JPEG" }, mayUseSoftware = { false }, software = { _, _ -> null }
        )
        assertEquals("RAW", result.source)
    }

    @Test fun failedSmallReadUsesBoundedCameraRendering() = runBlocking {
        val result = ThumbnailPipeline.load(
            sources = listOf("HIF"), sourceName = { it },
            small = { throw IOException("oversized response was drained") }, rendered = { encoded },
            platform = { _, _ -> "camera rendering" }, mayUseSoftware = { false }, software = { _, _ -> null }
        )
        assertEquals("camera rendering", result.image)
        assertFalse(result.software)
    }

    @Test fun systemDecodeMissUsesCameraLargeRenderingBeforeSoftware() = runBlocking {
        val small = byteArrayOf(1)
        val large = byteArrayOf(2)
        val result = ThumbnailPipeline.load(
            sources = listOf("HIF"), sourceName = { it }, small = { small }, rendered = { large },
            platform = { _, bytes -> if (bytes === large) "system large preview" else null },
            mayUseSoftware = { error("System large preview succeeded") }, software = { _, _ -> error("Not needed") }
        )
        assertEquals("system large preview", result.image)
    }

    @Test fun lastResortSoftwareRunsAfterAllSystemCandidates() = runBlocking {
        val calls = mutableListOf<String>()
        val result = ThumbnailPipeline.load(
            sources = listOf("HIF", "RAW"), sourceName = { it },
            small = { calls += "small:$it"; encoded }, rendered = { calls += "rendered:$it"; null },
            platform = { source, _ -> calls += "system:$source"; null }, mayUseSoftware = { true },
            software = { source, _ -> calls += "software:$source"; "decoded 422" }
        )
        assertEquals(listOf("small:HIF", "system:HIF", "small:RAW", "system:RAW", "rendered:HIF", "software:HIF"), calls)
        assertTrue(result.software)
    }

    @Test fun duplicateEncodedResponseDoesNotRepeatFailedSoftwareDecode() = runBlocking {
        var nativeCalls = 0
        val failure = try {
            ThumbnailPipeline.load<String, String>(sources = listOf("HIF"), sourceName = { it },
                small = { encoded }, rendered = { encoded.copyOf() }, platform = { _, _ -> null },
                mayUseSoftware = { true }, software = { _, _ -> nativeCalls++; throw IOException("HEIF failure") })
            error("Should fail")
        } catch (failure: ThumbnailLoadException) { failure }
        assertEquals(1, nativeCalls)
        assertEquals(ThumbnailStage.SOFTWARE, failure.attempts.last().stage)
        assertEquals("HEIF failure", failure.attempts.last().failure.message)
    }

    @Test fun failuresRetainSourceAndStageForErrorDetails() = runBlocking {
        val failure = try {
            ThumbnailPipeline.load<String, String>(sources = listOf("HIF", "RAW"), sourceName = { it },
                small = { if (it == "RAW") throw IOException("RAW refused") else encoded },
                rendered = { null }, platform = { _, _ -> null }, mayUseSoftware = { true },
                software = { _, _ -> throw IOException("native pixel limit") })
            error("Should fail")
        } catch (failure: ThumbnailLoadException) { failure }
        assertTrue(failure.attempts.any { it.source == "RAW" && it.stage == ThumbnailStage.SMALL && it.failure.message == "RAW refused" })
        assertEquals("native pixel limit", failure.attempts.last().failure.message)
    }

    @Test fun cancellationDoesNotContinueIntoFallback() = runBlocking {
        var calls = 0
        try {
            ThumbnailPipeline.load<String, String>(sources = listOf("HIF", "RAW"), sourceName = { it },
                small = { calls++; throw CancellationException("cancel") }, rendered = { error("Cancelled") },
                platform = { _, _ -> error("Cancelled") }, mayUseSoftware = { true },
                software = { _, _ -> error("Cancelled") })
            fail("Expected cancellation")
        } catch (_: CancellationException) {}
        assertEquals(1, calls)
    }

    @Test fun memoryFailureDoesNotTryCompanionOrSoftware() = runBlocking {
        var reads = 0
        val failure = IOException("memory", OutOfMemoryError("simulated"))
        try {
            ThumbnailPipeline.load<String, String>(sources = listOf("HIF", "RAW"), sourceName = { it },
                small = { reads++; encoded }, rendered = { error("No more allocation") },
                platform = { _, _ -> throw failure }, mayUseSoftware = { true },
                software = { _, _ -> error("No more allocation") })
            fail("Expected allocation failure")
        } catch (observed: IOException) { assertSame(failure, observed) }
        assertEquals(1, reads)
    }
}
