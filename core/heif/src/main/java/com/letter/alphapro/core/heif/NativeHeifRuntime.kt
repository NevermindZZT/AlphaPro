package com.letter.alphapro.core.heif

import java.io.IOException

/** A recoverable feature failure, not an uncaught VM linkage Error. */
class HeifDecoderUnavailableException(cause: UnsatisfiedLinkError) :
    IOException("HEIF software decoder is unavailable", cause)

/** Kept Android-free so missing libraries and missing JNI exports are regression-testable. */
internal class NativeHeifRuntime(private val loadLibrary: () -> Unit) {
    private val lock = Any()
    private var attempted = false
    private var loadFailure: UnsatisfiedLinkError? = null

    val isAvailable: Boolean
        get() = synchronized(lock) {
            loadOnce()
            loadFailure == null
        }

    fun requireAvailable(): Unit = synchronized(lock) {
        loadOnce()
        loadFailure?.let { throw HeifDecoderUnavailableException(it) }
    }

    fun <T> call(nativeCall: () -> T): T {
        requireAvailable()
        return try {
            nativeCall()
        } catch (failure: UnsatisfiedLinkError) {
            // Loading a .so does not prove that this build exports the expected JNI methods.
            synchronized(lock) { loadFailure = failure }
            throw HeifDecoderUnavailableException(failure)
        } catch (failure: OutOfMemoryError) {
            // Native decode has unwound its RAII allocations before the Java error arrives.
            // Do not retry or pretend a smaller image was a full-resolution export.
            throw IOException("Insufficient memory for HEIF decode", failure)
        }
    }

    private fun loadOnce() {
        if (attempted) return
        attempted = true
        try {
            loadLibrary()
        } catch (failure: UnsatisfiedLinkError) {
            loadFailure = failure
        }
    }
}
