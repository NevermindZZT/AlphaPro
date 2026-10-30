package com.letter.alphapro.ui.gallery

import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.function.BooleanSupplier
import org.junit.Assert.*
import org.junit.Test

class PreviewDecodePolicyTest {
    @Test fun supportedPlatform420NeverLoadsOrCallsSoftware() {
        val result = PreviewDecodePolicy.decode(
            platform = { "platform 420" },
            mayUseSoftware = { error("Software capability must not even be inspected") },
            software = { error("Must not force software") }
        )!!
        assertEquals("platform 420", result.image)
        assertEquals(PreviewDecoderKind.SYSTEM, result.decoder)
    }
    @Test fun unsupportedSystemCanUseSoftwareExactlyOnce() {
        var calls = 0
        val result = PreviewDecodePolicy.decode(platform = { null }, mayUseSoftware = { true },
            software = { calls++; "software 422" })!!
        assertEquals(PreviewDecoderKind.SOFTWARE, result.decoder)
        assertEquals(1, calls)
    }
    @Test fun disabledSoftwareReturnsNullAfterSystemMiss() {
        assertNull(PreviewDecodePolicy.decode<String>(platform = { null }, mayUseSoftware = { false },
            software = { error("Disabled") }))
    }
    @Test fun filePreviewUsesTheSamePlatformFirstPolicy() {
        var originalUnchanged = "original file bytes"
        val result = PreviewDecodePolicy.decode(platform = { originalUnchanged },
            mayUseSoftware = { error("File HEIF metadata must not force native") },
            software = { originalUnchanged = "modified"; "bad" })!!
        assertEquals("original file bytes", result.image)
        assertEquals("original file bytes", originalUnchanged)
    }
    @Test fun cancellationBeforeDecodeDoesNotCallEitherBackend() {
        assertThrows(CancellationException::class.java) {
            PreviewDecodePolicy.decode<String>(platform = { error("Cancelled") },
                mayUseSoftware = { error("Cancelled") }, software = { error("Cancelled") },
                cancelled = BooleanSupplier { true })
        }
    }
    @Test fun cancellationAfterPlatformMissPreventsSoftware() {
        var cancelled = false
        assertThrows(CancellationException::class.java) {
            PreviewDecodePolicy.decode<String>(platform = { cancelled = true; null },
                mayUseSoftware = { error("Cancelled") }, software = { error("Cancelled") },
                cancelled = BooleanSupplier { cancelled })
        }
    }
    @Test fun platformAllocationFailureDoesNotTriggerLargerSoftwareAllocation() {
        val failure = IOException("memory", OutOfMemoryError("simulated"))
        val observed = assertThrows(IOException::class.java) {
            PreviewDecodePolicy.decode<String>(platform = { throw failure }, mayUseSoftware = { true },
                software = { error("Do not retry allocation") })
        }
        assertSame(failure, observed)
    }
    @Test fun softwareFailureIsNotHiddenOrRetried() {
        var calls = 0
        val failure = IOException("real HEIF error")
        assertSame(failure, assertThrows(IOException::class.java) {
            PreviewDecodePolicy.decode<String>(platform = { null }, mayUseSoftware = { true },
                software = { calls++; throw failure })
        })
        assertEquals(1, calls)
    }
}
