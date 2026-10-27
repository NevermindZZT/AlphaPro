package com.letter.alphapro.core.bluetooth

import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiGpsInterleavingTest {
    @Test
    fun gpsWriteCanCompleteDuringCredentialPollingDelay() = runBlocking {
        val lane = GattOperationLane()
        val connection = Any()
        lane.activate(connection)
        val firstRead = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        var ssidReads = 0
        val port = object : SonyWifiBootstrapPort {
            override fun isReady() = lane.isActive(connection)
            override fun hasCharacteristic(uuid: UUID) = uuid in listOf(
                SonyBleConstants.CHAR_WIFI_POWER_UUID,
                SonyBleConstants.CHAR_WIFI_SSID_UUID,
                SonyBleConstants.CHAR_WIFI_PASSWORD_UUID
            )
            override suspend fun prepareMtu() = Unit
            override suspend fun subscribe(uuid: UUID) = false
            override suspend fun read(uuid: UUID): ByteArray? {
                val bytes = if (uuid == SonyBleConstants.CHAR_WIFI_SSID_UUID) {
                    ssidReads++
                    events.add("ssid" + ssidReads)
                    if (ssidReads == 1) {
                        firstRead.complete(Unit)
                        byteArrayOf()
                    } else byteArrayOf(1, 0, 0) + "DIRECT-test".toByteArray()
                } else byteArrayOf(1, 0, 0) + "test-pass".toByteArray()
                return lane.execute(connection, GattOperationKind.READ, uuid.toString()) {
                    lane.complete(connection, GattOperationKind.READ, uuid.toString(), 0, bytes)
                    true
                }.value
            }
            override suspend fun write(uuid: UUID, value: ByteArray): Boolean =
                lane.execute(connection, GattOperationKind.WRITE, uuid.toString()) {
                    lane.complete(connection, GattOperationKind.WRITE, uuid.toString(), 0)
                    true
                }.status == 0
        }
        withTimeout(5_000) {
            val setup = async(start = CoroutineStart.UNDISPATCHED) {
                SonyWifiBootstrap(port, credentialPollMs = 80).prepare()
            }
            firstRead.await()
            val gps = lane.execute(connection, GattOperationKind.WRITE, "dd11") {
                events.add("gps")
                lane.complete(connection, GattOperationKind.WRITE, "dd11", 0)
                true
            }
            assertEquals(0, gps.status)
            assertEquals("DIRECT-test", setup.await().ssid)
        }
        assertTrue(events.indexOf("ssid1") < events.indexOf("gps"))
        assertTrue(events.indexOf("gps") < events.indexOf("ssid2"))
    }
}
