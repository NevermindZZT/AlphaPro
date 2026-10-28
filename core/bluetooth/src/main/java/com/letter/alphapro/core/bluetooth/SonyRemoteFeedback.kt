package com.letter.alphapro.core.bluetooth

/** Optional camera signals. Neither a transport ACK nor A0 activity proves a file was saved. */
data class SonyRemoteFeedback(
    val shutterActive: Boolean? = null,
    val focusAcquired: Boolean? = null,
    val recording: Boolean? = null,
    val sequence: Long = 0,
    val shutterSequence: Long = 0
)

object SonyRemoteFeedbackParser {
    fun update(previous: SonyRemoteFeedback, value: ByteArray): SonyRemoteFeedback? {
        if (value.size < 3 || value[0] != 2.toByte()) return null
        val flag = value[2].toInt() and 0xff
        if (flag != 0 && flag != 0x20) return null
        val active = flag == 0x20
        val next = previous.sequence + 1
        return when (value[1].toInt() and 0xff) {
            0xa0 -> previous.copy(shutterActive = active, sequence = next, shutterSequence = previous.shutterSequence + 1)
            0x3f -> previous.copy(focusAcquired = active, sequence = next)
            0xd5 -> previous.copy(recording = active, sequence = next)
            else -> null
        }
    }
}
