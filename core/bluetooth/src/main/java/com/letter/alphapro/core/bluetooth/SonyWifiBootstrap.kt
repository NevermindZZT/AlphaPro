package com.letter.alphapro.core.bluetooth

import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

internal interface SonyWifiBootstrapPort {
    fun isReady(): Boolean
    fun hasCharacteristic(uuid: UUID): Boolean
    suspend fun prepareMtu()
    suspend fun subscribe(uuid: UUID): Boolean
    suspend fun read(uuid: UUID): ByteArray?
    suspend fun write(uuid: UUID, value: ByteArray): Boolean
}

internal class SonyWifiSetupException(message: String) : Exception(message)

/** Wi-Fi workflow owns no GATT lock: each port operation uses the shared callback-backed lane. */
internal class SonyWifiBootstrap(
    private val port: SonyWifiBootstrapPort,
    private val report: (String) -> Unit = {},
    private val overallTimeoutMs: Long = 45_000,
    private val credentialPollMs: Long = 500
) {
    suspend fun prepare(): SonyWifiCredentials {
        var stage = "Check Bluetooth connection"
        fun ready() {
            if (!port.isReady()) throw SonyWifiSetupException("$stage: Bluetooth session changed; wait for reconnect")
        }
        fun enter(value: String) { stage = value; report(value) }
        val result = withTimeoutOrNull(overallTimeoutMs) {
            ready()
            for (uuid in listOf(SonyBleConstants.CHAR_WIFI_POWER_UUID,
                SonyBleConstants.CHAR_WIFI_SSID_UUID, SonyBleConstants.CHAR_WIFI_PASSWORD_UUID)) {
                if (!port.hasCharacteristic(uuid)) {
                    throw SonyWifiSetupException("Missing characteristic "+uuid.toString().take(8))
                }
            }
            enter("Prepare BLE MTU")
            try { port.prepareMtu() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { report("MTU preparation unavailable; verify session and continue") }
            ready()
            val statusUuid = SonyBleConstants.CHAR_CAMERA_STATUS_UUID
            val hasStatus = port.hasCharacteristic(statusUuid)
            if (hasStatus) {
                enter("Subscribe and preread CC09")
                // Reference ordering; a missing optional CCCD does not prevent activation.
                if (!port.subscribe(statusUuid)) report("CC09 subscription unavailable; continue explicit reads")
                port.read(statusUuid)
            }
            ready()
            enter("Activate camera Wi-Fi (CC08)")
            if (!port.write(SonyBleConstants.CHAR_WIFI_POWER_UUID, SonyBleConstants.CMD_WIFI_POWER_ON)) {
                throw SonyWifiSetupException("CC08 Wi-Fi activation write failed")
            }
            if (hasStatus) {
                enter("Check camera Wi-Fi status (CC09)")
                // Reference: unknown status ends the status probe, not the entire setup.
                while (true) {
                    ready()
                    val status = port.read(statusUuid)?.let(SonyWifiDataParser::parseWifiStatus) ?: break
                    report("CC09 state=${status.first} error=${status.second}")
                    if (status.first == 2) break
                    if (status.first == 0 && status.second in 2..5) {
                        throw SonyWifiSetupException("CC09 camera Wi-Fi startup error=${status.second}")
                    }
                    delay(credentialPollMs)
                }
            }
            suspend fun credential(uuid: UUID, label: String, lengths: IntRange): String {
                enter(label)
                while (true) {
                    ready()
                    val bytes = port.read(uuid)
                    val parsed = bytes?.let(SonyWifiDataParser::parsePrefixedAscii)
                    report(label+": bytes="+(bytes?.size ?: 0)+" parse="+(parsed != null)+" lengthValid="+(parsed != null && parsed.length in lengths))
                    if (parsed != null && parsed.length in lengths) return parsed
                    delay(credentialPollMs)
                }
            }
            val ssid = credential(SonyBleConstants.CHAR_WIFI_SSID_UUID, "Read SSID (CC06)", 1..32)
            val password = credential(SonyBleConstants.CHAR_WIFI_PASSWORD_UUID, "Read password (CC07)", 8..63)
            var bssid: String? = null
            val bssidUuid = SonyBleConstants.CHAR_WIFI_BSSID_UUID
            if (port.hasCharacteristic(bssidUuid)) {
                enter("Read optional BSSID (CC0C)")
                try { bssid = port.read(bssidUuid)?.let(SonyWifiDataParser::parseAscii) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { report("Optional BSSID unavailable") }
            }
            ready()
            report("Credential read complete (contents redacted)")
            SonyWifiCredentials(ssid, password, bssid)
        }
        return result ?: throw SonyWifiSetupException("$stage: overall timeout after ${overallTimeoutMs / 1000} seconds")
    }
}
