package com.letter.alphapro.core.heif

import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class NativeHeifRuntimeTest {
    @Test fun missingLibraryIsRecoverableAndNeverCallsNativeCode() {
        val missing = UnsatisfiedLinkError("missing alpha_heif")
        var loads = 0
        var calls = 0
        val runtime = NativeHeifRuntime { loads++; throw missing }
        assertFalse(runtime.isAvailable)
        repeat(3) {
            val error = assertThrows(HeifDecoderUnavailableException::class.java) {
                runtime.call { calls++; "unexpected" }
            }
            assertSame(missing, error.cause)
            assertTrue(error is IOException)
        }
        assertEquals(1, loads)
        assertEquals(0, calls)
    }

    @Test fun requireAvailableCanFailBeforeDownloadOrOtherSideEffects() {
        val runtime = NativeHeifRuntime { throw UnsatisfiedLinkError("wrong ABI") }
        var downloaded = false
        assertThrows(HeifDecoderUnavailableException::class.java) {
            runtime.requireAvailable()
            downloaded = true
        }
        assertFalse(downloaded)
    }

    @Test fun successfulLoaderRunsOnceAndPassesNativeResultsThrough() {
        var loads = 0
        val runtime = NativeHeifRuntime { loads++ }
        assertTrue(runtime.isAvailable)
        runtime.requireAvailable()
        assertEquals("bitmap", runtime.call { "bitmap" })
        assertArrayEquals(byteArrayOf(1, 2), runtime.call { byteArrayOf(1, 2) })
        assertEquals(1, loads)
    }

    @Test fun missingJniSymbolAfterSuccessfulLoadDisablesSubsequentCalls() {
        val missing = UnsatisfiedLinkError("No implementation found for decodeFile")
        val runtime = NativeHeifRuntime {}
        assertTrue(runtime.isAvailable)
        val failure = assertThrows(HeifDecoderUnavailableException::class.java) {
            runtime.call { throw missing }
        }
        assertSame(missing, failure.cause)
        assertFalse(runtime.isAvailable)
        var retried = false
        assertThrows(HeifDecoderUnavailableException::class.java) {
            runtime.call { retried = true }
        }
        assertFalse(retried)
    }

    @Test fun decoderIoFailureIsNotMisclassifiedAsMissingLibrary() {
        val runtime = NativeHeifRuntime {}
        val malformedImage = IOException("malformed HEIF")
        val failure = assertThrows(IOException::class.java) {
            runtime.call { throw malformedImage }
        }
        assertSame(malformedImage, failure)
        assertTrue(runtime.isAvailable)
        assertEquals(42, runtime.call { 42 })
    }

    @Test fun coroutineCancellationIsNotSwallowedOrReclassified() {
        val runtime = NativeHeifRuntime {}
        val cancelled = CancellationException("cancelled")
        val failure = assertThrows(CancellationException::class.java) {
            runtime.call { throw cancelled }
        }
        assertSame(cancelled, failure)
        assertTrue(runtime.isAvailable)
    }

    @Test fun allocationFailureIsRecoverableWithoutRetryingOrDisablingDecoder() {
        val runtime = NativeHeifRuntime {}
        val exhausted = OutOfMemoryError("simulated bitmap allocation failure")
        var calls = 0
        val failure = assertThrows(IOException::class.java) {
            runtime.call { calls++; throw exhausted }
        }
        assertSame(exhausted, failure.cause)
        assertEquals(1, calls)
        assertTrue(runtime.isAvailable)
    }

    @Test fun unrelatedVmErrorsAreNotBroadlySwallowed() {
        val runtime = NativeHeifRuntime {}
        val failure = AssertionError("programming error")
        assertSame(failure, assertThrows(AssertionError::class.java) {
            runtime.call { throw failure }
        })
    }

    @Test fun concurrentUnavailableChecksLoadOnlyOnce() {
        var loads = 0
        val runtime = NativeHeifRuntime { loads++; throw UnsatisfiedLinkError("missing") }
        val executor = Executors.newFixedThreadPool(4)
        try {
            val results = executor.invokeAll((1..20).map { Callable { runtime.isAvailable } })
            results.forEach { assertFalse(it.get(5, TimeUnit.SECONDS)) }
            assertEquals(1, loads)
        } finally {
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }
}
