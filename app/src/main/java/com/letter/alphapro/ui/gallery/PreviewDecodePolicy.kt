package com.letter.alphapro.ui.gallery

import java.util.concurrent.CancellationException
import java.util.function.BooleanSupplier

internal enum class PreviewDecoderKind { SYSTEM, SOFTWARE }
internal data class PreviewDecodeResult<T>(val image: T, val decoder: PreviewDecoderKind)

/** A successful platform result is final: do not even inspect/load a software decoder. */
internal object PreviewDecodePolicy {
    fun <T> decode(
        platform: () -> T?,
        mayUseSoftware: () -> Boolean,
        software: () -> T?,
        cancelled: BooleanSupplier? = null
    ): PreviewDecodeResult<T>? {
        if (cancelled?.asBoolean == true) throw CancellationException()
        platform()?.let { return PreviewDecodeResult(it, PreviewDecoderKind.SYSTEM) }
        if (cancelled?.asBoolean == true) throw CancellationException()
        if (!mayUseSoftware()) return null
        return software()?.let { PreviewDecodeResult(it, PreviewDecoderKind.SOFTWARE) }
    }
}
