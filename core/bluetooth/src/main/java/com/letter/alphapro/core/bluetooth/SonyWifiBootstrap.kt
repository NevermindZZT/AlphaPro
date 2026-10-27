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
        var stage = "检查蓝牙连接"
        fun ready() {
            if (!port.isReady()) throw SonyWifiSetupException("$stage：蓝牙连接已变化，请等待重新联机")
        }
        fun enter(value: String) { stage = value; report(value) }
        val result = withTimeoutOrNull(overallTimeoutMs) {
            ready()
            for (uuid in listOf(SonyBleConstants.CHAR_WIFI_POWER_UUID,
                SonyBleConstants.CHAR_WIFI_SSID_UUID, SonyBleConstants.CHAR_WIFI_PASSWORD_UUID)) {
                if (!port.hasCharacteristic(uuid)) {
                    throw SonyWifiSetupException("缺少特征 "+uuid.toString().take(8))
                }
            }
            enter("准备 BLE MTU")
            try { port.prepareMtu() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { report("MTU 准备未成功，检查连接后继续") }
            ready()
            val statusUuid = SonyBleConstants.CHAR_CAMERA_STATUS_UUID
            val hasStatus = port.hasCharacteristic(statusUuid)
            if (hasStatus) {
                enter("订阅并预读 CC09")
                // Reference ordering; a missing optional CCCD does not prevent activation.
                if (!port.subscribe(statusUuid)) report("CC09 通知订阅不可用，继续主动读取")
                port.read(statusUuid)
            }
            ready()
            enter("开启相机 Wi-Fi（CC08）")
            if (!port.write(SonyBleConstants.CHAR_WIFI_POWER_UUID, SonyBleConstants.CMD_WIFI_POWER_ON)) {
                throw SonyWifiSetupException("CC08 开启 Wi-Fi 写入失败")
            }
            if (hasStatus) {
                enter("确认相机 Wi-Fi 状态（CC09）")
                // Reference: unknown status ends the status probe, not the entire setup.
                while (true) {
                    ready()
                    val status = port.read(statusUuid)?.let(SonyWifiDataParser::parseWifiStatus) ?: break
                    report("CC09 state=${status.first} error=${status.second}")
                    if (status.first == 2) break
                    if (status.first == 0 && status.second in 2..5) {
                        throw SonyWifiSetupException("CC09 相机报告 Wi-Fi 启动错误，error=${status.second}")
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
            val ssid = credential(SonyBleConstants.CHAR_WIFI_SSID_UUID, "读取 SSID（CC06）", 1..32)
            val password = credential(SonyBleConstants.CHAR_WIFI_PASSWORD_UUID, "读取密码（CC07）", 8..63)
            var bssid: String? = null
            val bssidUuid = SonyBleConstants.CHAR_WIFI_BSSID_UUID
            if (port.hasCharacteristic(bssidUuid)) {
                enter("读取可选 BSSID（CC0C）")
                try { bssid = port.read(bssidUuid)?.let(SonyWifiDataParser::parseAscii) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { report("可选 BSSID 不可用") }
            }
            ready()
            report("凭据读取完成（内容已脱敏）")
            SonyWifiCredentials(ssid, password, bssid)
        }
        return result ?: throw SonyWifiSetupException("$stage：总等待超过 ${overallTimeoutMs / 1000} 秒")
    }
}
