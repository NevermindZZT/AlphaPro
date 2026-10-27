package com.letter.alphapro.core.bluetooth

import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SonyWifiBootstrapTest {
    @Test
    fun retriesStatusAndEmptyCredentialsButActivatesOnlyOnceInOrder() = runBlocking {
        val port = FakePort().apply {
            enqueue(STATUS, status(0), status(1), status(2))
            enqueue(SSID, byteArrayOf(), prefixed(""), prefixed(TEST_SSID))
            enqueue(PASSWORD, byteArrayOf(), prefixed(TEST_PASSWORD))
        }

        val credentials = bootstrap(port).prepare()

        assertEquals(TEST_SSID, credentials.ssid)
        assertEquals(TEST_PASSWORD, credentials.password)
        assertEquals(1, port.writes.size)
        assertEquals(POWER, port.writes.single().first)
        assertArrayEquals(SonyBleConstants.CMD_WIFI_POWER_ON, port.writes.single().second)
        assertBefore(port.events, "subscribe:$STATUS", "read:$STATUS")
        assertBefore(port.events, "read:$STATUS", "write:$POWER")
        assertBefore(port.events, "write:$POWER", "read:$SSID")
        assertBefore(port.events, "write:$POWER", "read:$PASSWORD")
        assertTrue(port.readCount(STATUS) >= 3)
        assertTrue(port.readCount(SSID) >= 3)
        assertTrue(port.readCount(PASSWORD) >= 2)
    }

    @Test
    fun waitsBetweenCredentialRetriesInsteadOfBusyPolling() = runBlocking {
        val ssidReadTimes = mutableListOf<Long>()
        val port = FakePort().apply {
            onRead = { uuid, count ->
                if (uuid == SSID) {
                    ssidReadTimes += System.nanoTime()
                    if (count == 1) byteArrayOf() else prefixed(TEST_SSID)
                } else {
                    defaultValue(uuid)
                }
            }
        }

        SonyWifiBootstrap(port, overallTimeoutMs = 2_000, credentialPollMs = 40).prepare()

        assertEquals(2, ssidReadTimes.size)
        // Allow scheduler/clock precision variation without demanding exact timing.
        assertTrue(ssidReadTimes[1] - ssidReadTimes[0] >= 20_000_000L)
        assertEquals(1, port.writes.size)
    }

    @Test
    fun requiresReadyBeforeAnyActivationOrCredentialRead() = runBlocking {
        val port = FakePort().apply { ready = false }

        expectSetupFailure { bootstrap(port).prepare() }

        assertTrue(port.writes.isEmpty())
        assertEquals(0, port.readCount(SSID))
        assertEquals(0, port.readCount(PASSWORD))
    }

    @Test
    fun missingRequiredCharacteristicsFailBeforeActivation() = runBlocking {
        for (missing in listOf(POWER, SSID, PASSWORD)) {
            val port = FakePort().apply { available.remove(missing) }

            expectSetupFailure { bootstrap(port).prepare() }

            assertTrue("Must not activate with missing $missing", port.writes.isEmpty())
            assertEquals(0, port.readCount(SSID))
            assertEquals(0, port.readCount(PASSWORD))
        }
    }

    @Test
    fun activationRejectionPreventsCredentialReadsAndDoesNotRetryWrite() = runBlocking {
        val port = FakePort().apply { writeAccepted = false }

        expectSetupFailure { bootstrap(port).prepare() }

        assertEquals(1, port.writes.size)
        assertEquals(0, port.readCount(SSID))
        assertEquals(0, port.readCount(PASSWORD))
    }

    @Test
    fun explicitOffWithErrorAfterActivationPreventsCredentialReads() = runBlocking {
        val port = FakePort().apply { enqueue(STATUS, status(0), status(0, 5)) }

        expectSetupFailure { bootstrap(port).prepare() }

        assertEquals(1, port.writes.size)
        assertEquals(0, port.readCount(SSID))
        assertEquals(0, port.readCount(PASSWORD))
    }

    @Test
    fun explicitCameraErrorsTwoThroughFivePreventCredentialReads() = runBlocking {
        for (error in 2..5) {
            val port = FakePort().apply { enqueue(STATUS, status(0), status(0, error)) }

            expectSetupFailure { bootstrap(port).prepare() }

            assertEquals(1, port.writes.size)
            assertEquals("Camera error $error must not expose credentials", 0, port.readCount(SSID))
            assertEquals(0, port.readCount(PASSWORD))
        }
    }

    @Test
    fun malformedOrUnavailableStatusFallsBackToCredentialPolling() = runBlocking {
        for (unavailable in listOf(null, byteArrayOf(), byteArrayOf(4, 0, 1))) {
            val port = FakePort().apply { enqueue(STATUS, unavailable, unavailable) }

            val credentials = bootstrap(port).prepare()

            assertEquals(TEST_SSID, credentials.ssid)
            assertEquals(TEST_PASSWORD, credentials.password)
            assertEquals(1, port.writes.size)
        }
    }

    @Test
    fun missingOptionalStatusAndBssidDoNotPreventPreparation() = runBlocking {
        val port = FakePort().apply {
            available.remove(STATUS)
            available.remove(BSSID)
        }

        val credentials = bootstrap(port).prepare()

        assertEquals(TEST_SSID, credentials.ssid)
        assertEquals(TEST_PASSWORD, credentials.password)
        assertNull(credentials.bssid)
        assertEquals(0, port.readCount(STATUS))
        assertEquals(0, port.readCount(BSSID))
    }

    @Test
    fun stripsExactlyThreePrefixBytesAndPreservesCredentialSpaces() = runBlocking {
        val expectedSsid = " DIRECT-test "
        val expectedPassword = " pass word "
        for (firstPrefixByte in listOf(0x20, 0x01)) {
            val port = FakePort().apply {
                // Printable second/third prefix bytes must not become credential text.
                enqueue(SSID, prefixed(expectedSsid, firstPrefixByte, 0x41, 0x42))
                enqueue(PASSWORD, prefixed(expectedPassword, firstPrefixByte, 0x43, 0x44))
            }

            val credentials = bootstrap(port).prepare()

            assertEquals(expectedSsid, credentials.ssid)
            assertEquals(expectedPassword, credentials.password)
        }
    }

    @Test
    fun acceptsTrailingNulWithoutTrimmingSpaces() = runBlocking {
        val port = FakePort().apply {
            enqueue(SSID, prefixed(" DIRECT-test ") + byteArrayOf(0))
            enqueue(PASSWORD, prefixed(" pass word ") + byteArrayOf(0))
        }

        val credentials = bootstrap(port).prepare()

        assertEquals(" DIRECT-test ", credentials.ssid)
        assertEquals(" pass word ", credentials.password)
    }

    @Test
    fun retriesNonPrintableEmbeddedNulAndOutOfRangeCredentials() = runBlocking {
        val port = FakePort().apply {
            enqueue(
                SSID,
                prefixed("bad\nssid"),
                prefixed("bad\u0000ssid"),
                prefixed("x".repeat(33)),
                prefixed("bad") + byteArrayOf(0x80.toByte()),
                prefixed(TEST_SSID)
            )
            enqueue(
                PASSWORD,
                prefixed("short"),
                prefixed("x".repeat(64)),
                prefixed("bad\tpassword"),
                prefixed(TEST_PASSWORD)
            )
        }

        val credentials = bootstrap(port).prepare()

        assertEquals(TEST_SSID, credentials.ssid)
        assertEquals(TEST_PASSWORD, credentials.password)
        assertTrue(port.readCount(SSID) >= 5)
        assertTrue(port.readCount(PASSWORD) >= 4)
        assertEquals(1, port.writes.size)
    }

    @Test
    fun acceptsMinimumAndMaximumCredentialLengths() = runBlocking {
        for ((ssid, password) in listOf("s" to "12345678", "s".repeat(32) to "p".repeat(63))) {
            val port = FakePort().apply {
                enqueue(SSID, prefixed(ssid))
                enqueue(PASSWORD, prefixed(password))
            }

            val credentials = bootstrap(port).prepare()

            assertEquals(ssid, credentials.ssid)
            assertEquals(password, credentials.password)
        }
    }

    @Test
    fun shortPasswordEventuallyFailsWithSetupExceptionNotTimeoutCancellation() = runBlocking {
        val port = FakePort().apply { enqueue(PASSWORD, prefixed("short")) }

        val failure = expectSetupFailure { bootstrap(port, timeoutMs = 100).prepare() }

        assertTrue(failure.message.orEmpty().isNotBlank())
        assertTrue(port.readCount(PASSWORD) >= 2)
        assertEquals(1, port.writes.size)
    }

    @Test
    fun readinessLostWhilePollingFailsWithoutAnotherActivation() = runBlocking {
        val port = FakePort().apply {
            onRead = { uuid, _ ->
                if (uuid == SSID || uuid == PASSWORD) {
                    ready = false
                    byteArrayOf()
                } else {
                    defaultValue(uuid)
                }
            }
        }

        expectSetupFailure { bootstrap(port).prepare() }

        assertEquals(1, port.writes.size)
        assertFalse(port.ready)
    }

    @Test
    fun optionalMtuAndBssidReadExceptionsAreNonfatal() = runBlocking {
        val port = FakePort().apply {
            mtuFailure = IllegalStateException("MTU unsupported")
            onRead = { uuid, _ ->
                if (uuid == BSSID) throw IllegalStateException("Optional BSSID unavailable")
                defaultValue(uuid)
            }
        }

        val credentials = bootstrap(port).prepare()

        assertEquals(TEST_SSID, credentials.ssid)
        assertEquals(TEST_PASSWORD, credentials.password)
        assertNull(credentials.bssid)
        assertEquals(1, port.writes.size)
    }

    @Test
    fun externalTimeoutIsNotConvertedToSetupFailure() = runBlocking {
        val port = FakePort().apply {
            onRead = { uuid, _ ->
                if (uuid == SSID) awaitCancellation()
                defaultValue(uuid)
            }
        }
        var caught: Throwable? = null

        try {
            withTimeout(100) { bootstrap(port, timeoutMs = 5_000).prepare() }
        } catch (failure: Throwable) {
            caught = failure
        }

        assertTrue("Caller timeout must remain cancellation", caught is TimeoutCancellationException)
    }

    @Test
    fun callerCancellationDuringPollingPropagates() = runBlocking {
        val readingCredentials = CompletableDeferred<Unit>()
        val cancellation = CancellationException("Caller cancelled Wi-Fi setup")
        val port = FakePort().apply {
            onRead = { uuid, _ ->
                if (uuid == SSID) {
                    readingCredentials.complete(Unit)
                    awaitCancellation()
                }
                defaultValue(uuid)
            }
        }
        var caught: Throwable? = null
        val job = launch {
            try {
                bootstrap(port).prepare()
            } catch (failure: Throwable) {
                caught = failure
                throw failure
            }
        }

        withTimeout(2_000) { readingCredentials.await() }
        job.cancel(cancellation)
        job.cancelAndJoin()

        // Coroutine stack-trace recovery may copy CancellationException across a suspension.
        assertTrue(caught is CancellationException)
        assertEquals(cancellation.message, caught?.message)
        assertEquals(1, port.writes.size)
    }

    @Test
    fun optionalBssidAndMtuMustNotSwallowCancellation() = runBlocking {
        for (cancelMtu in listOf(false, true)) {
            val cancellation = CancellationException("Cancelled optional operation")
            val port = FakePort().apply {
                if (cancelMtu) mtuFailure = cancellation
                onRead = { uuid, _ ->
                    if (uuid == BSSID) throw cancellation
                    defaultValue(uuid)
                }
            }
            var caught: Throwable? = null

            try {
                bootstrap(port).prepare()
            } catch (failure: Throwable) {
                caught = failure
            }

            // Coroutine stack-trace recovery may copy CancellationException across a suspension.
            assertTrue(caught is CancellationException)
            assertEquals(cancellation.message, caught?.message)
        }
    }

    @Test
    fun diagnosticsAndCredentialToStringNeverExposeCredentialPayloads() = runBlocking {
        val reports = mutableListOf<String>()
        val port = FakePort()
        val credentials = bootstrap(port, reports = reports).prepare()
        val failureReports = mutableListOf<String>()
        val failingPort = FakePort().apply { enqueue(PASSWORD, prefixed("short")) }
        val failure = expectSetupFailure {
            bootstrap(failingPort, timeoutMs = 100, reports = failureReports).prepare()
        }
        val diagnostics = (reports + failureReports + credentials.toString() + failure.toString())
            .joinToString("\n")

        for (payload in listOf(TEST_SSID, TEST_PASSWORD, "short")) {
            assertFalse("Diagnostics expose credential text", diagnostics.contains(payload))
            assertFalse(
                "Diagnostics expose credential byte array",
                diagnostics.contains(payload.toByteArray(Charsets.US_ASCII).contentToString())
            )
            assertFalse(
                "Diagnostics expose hexadecimal credential payload",
                diagnostics.lowercase().contains(
                    payload.toByteArray(Charsets.US_ASCII).joinToString(" ") { "%02x".format(it) }
                )
            )
        }
    }

    private fun bootstrap(
        port: FakePort,
        timeoutMs: Long = 2_000,
        reports: MutableList<String> = mutableListOf()
    ) = SonyWifiBootstrap(
        port = port,
        report = { reports += it },
        overallTimeoutMs = timeoutMs,
        credentialPollMs = 1
    )

    private suspend fun expectSetupFailure(block: suspend () -> Any?): SonyWifiSetupException {
        try {
            block()
        } catch (failure: SonyWifiSetupException) {
            return failure
        }
        throw AssertionError("Expected SonyWifiSetupException")
    }

    private fun assertBefore(events: List<String>, first: String, second: String) {
        val firstIndex = events.indexOf(first)
        val secondIndex = events.indexOf(second)
        assertTrue("Missing $first in $events", firstIndex >= 0)
        assertTrue("Missing $second in $events", secondIndex >= 0)
        assertTrue("Expected $first before $second in $events", firstIndex < secondIndex)
    }

    private class FakePort : SonyWifiBootstrapPort {
        var ready = true
        val available = mutableSetOf(POWER, STATUS, SSID, PASSWORD, BSSID)
        val events = mutableListOf<String>()
        val writes = mutableListOf<Pair<UUID, ByteArray>>()
        var writeAccepted = true
        var mtuFailure: Exception? = null
        var onRead: (suspend (UUID, Int) -> ByteArray?)? = null
        private val values = mutableMapOf<UUID, MutableList<ByteArray?>>()
        private val readCounts = mutableMapOf<UUID, Int>()

        override fun isReady() = ready

        override fun hasCharacteristic(uuid: UUID) = uuid in available

        override suspend fun prepareMtu() {
            events += "mtu"
            mtuFailure?.let { throw it }
        }

        override suspend fun subscribe(uuid: UUID): Boolean {
            events += "subscribe:$uuid"
            return true
        }

        override suspend fun read(uuid: UUID): ByteArray? {
            events += "read:$uuid"
            val count = readCount(uuid) + 1
            readCounts[uuid] = count
            onRead?.let { return it(uuid, count) }
            val queued = values[uuid]
            return when {
                queued == null || queued.isEmpty() -> defaultValue(uuid)
                queued.size == 1 -> queued.first()
                else -> queued.removeAt(0)
            }
        }

        override suspend fun write(uuid: UUID, value: ByteArray): Boolean {
            events += "write:$uuid"
            writes += uuid to value.copyOf()
            return writeAccepted
        }

        fun enqueue(uuid: UUID, vararg responses: ByteArray?) {
            values[uuid] = responses.toMutableList()
        }

        fun readCount(uuid: UUID) = readCounts[uuid] ?: 0

        fun defaultValue(uuid: UUID): ByteArray? = when (uuid) {
            STATUS -> status(2)
            SSID -> prefixed(TEST_SSID)
            PASSWORD -> prefixed(TEST_PASSWORD)
            else -> null
        }
    }

    private companion object {
        val POWER = SonyBleConstants.CHAR_WIFI_POWER_UUID
        val STATUS = SonyBleConstants.CHAR_CAMERA_STATUS_UUID
        val SSID = SonyBleConstants.CHAR_WIFI_SSID_UUID
        val PASSWORD = SonyBleConstants.CHAR_WIFI_PASSWORD_UUID
        val BSSID = SonyBleConstants.CHAR_WIFI_BSSID_UUID
        const val TEST_SSID = "DIRECT-test"
        const val TEST_PASSWORD = "test-pass-42"

        fun status(state: Int, error: Int = 0) = byteArrayOf(4, 0, 1, state.toByte(), error.toByte())

        fun prefixed(text: String, first: Int = 3, second: Int = 0, third: Int = 0): ByteArray =
            byteArrayOf(first.toByte(), second.toByte(), third.toByte()) + text.toByteArray(Charsets.US_ASCII)
    }
}
