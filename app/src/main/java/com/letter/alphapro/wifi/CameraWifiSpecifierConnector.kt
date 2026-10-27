package com.letter.alphapro.wifi

import android.content.Context
import android.net.*
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.net.Inet4Address

sealed interface CameraWifiState {
    data object Idle : CameraWifiState
    data object Connecting : CameraWifiState
    data class Connected(val gatewayIp: String, val network: Network) : CameraWifiState
    data class Error(val message: String) : CameraWifiState
}

class CameraWifiSpecifierConnector(private val context: Context) {
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    // Setup, callbacks and cleanup share a lock so a replaced request cannot register late.
    private val requestLock = Any()
    private class WifiRequest(val closeFlow: () -> Unit) {
        var callback: ConnectivityManager.NetworkCallback? = null
    }
    private var activeRequest: WifiRequest? = null

    fun connectCameraWifi(ssid: String, password: String, bssid: String? = null): Flow<CameraWifiState> = callbackFlow {
        val owner = WifiRequest { close() }

        fun finish(state: CameraWifiState) {
            // Called with requestLock held, after checking ownership.
            trySend(state)
            releaseRequestLocked(owner)
        }

        synchronized(requestLock) {
            activeRequest?.let { releaseRequestLocked(it) }
            activeRequest = owner
            trySend(CameraWifiState.Connecting)
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val specifierBuilder = WifiNetworkSpecifier.Builder()
                    .setSsid(ssid)
                    .setWpa2Passphrase(password)

                if (!bssid.isNullOrEmpty() && bssid.matches(Regex("(?i)[0-9a-f]{2}(:[0-9a-f]{2}){5}"))) {
                    try {
                        val mac = MacAddress.fromString(bssid)
                        if (mac.addressType == MacAddress.TYPE_UNICAST && bssid != "00:00:00:00:00:00") {
                            specifierBuilder.setBssid(mac)
                        }
                    } catch (_: Exception) {}
                }

                val request = NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .setNetworkSpecifier(specifierBuilder.build())
                    .build()

                var currentNetwork: Network? = null
                var currentGateway: String? = null
                val callback = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        synchronized(requestLock) {
                            if (activeRequest !== owner) return
                            if (currentNetwork != network) {
                                currentNetwork = network
                                if (currentGateway != null) trySend(CameraWifiState.Connecting)
                                currentGateway = null
                            }
                            // Availability alone is not readiness: wait for link properties.
                            // PTP opens sockets on this Network; never bind the whole process.
                        }
                    }

                    override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) {
                        synchronized(requestLock) {
                            if (activeRequest !== owner || currentNetwork != network) return
                            try {
                                val gateway = resolveGateway(properties)
                                if (gateway != currentGateway) {
                                    currentGateway = gateway
                                    if (gateway != null) {
                                        trySend(CameraWifiState.Connected(gateway, network))
                                    } else {
                                        trySend(CameraWifiState.Connecting)
                                    }
                                }
                            } catch (e: Exception) {
                                finish(CameraWifiState.Error("读取 Wi-Fi 网络信息异常: " + (e.message ?: "")))
                            }
                        }
                    }

                    override fun onUnavailable() {
                        synchronized(requestLock) {
                            if (activeRequest !== owner) return
                            finish(CameraWifiState.Error("Wi-Fi 连接超时或被拒绝"))
                        }
                    }

                    override fun onLost(network: Network) {
                        synchronized(requestLock) {
                            if (activeRequest !== owner || (currentNetwork != null && currentNetwork != network)) return
                            finish(CameraWifiState.Idle)
                        }
                    }
                }

                synchronized(requestLock) {
                    // A replacement may have claimed ownership while this request was built.
                    if (activeRequest === owner) {
                        owner.callback = callback
                        connectivityManager.requestNetwork(request, callback, 60_000)
                    }
                }
            } else {
                synchronized(requestLock) {
                    if (activeRequest === owner) {
                        finish(CameraWifiState.Error("系统版本较低，请在系统 Wi-Fi 设置中手动连接 SSID: $ssid"))
                    }
                }
            }
        } catch (e: Exception) {
            synchronized(requestLock) {
                if (activeRequest === owner) {
                    finish(CameraWifiState.Error("发起 Wi-Fi 连接异常: " + (e.message ?: "")))
                }
            }
        }

        awaitClose {
            // This flow may outlive its replacement; cleanup must only touch its own request.
            synchronized(requestLock) { releaseRequestLocked(owner) }
        }
    }

    private fun resolveGateway(linkProperties: LinkProperties): String? {
        val localAddresses = linkProperties.linkAddresses.filter { it.address is Inet4Address }
        return linkProperties.routes
            .filter { it.isDefaultRoute }
            .mapNotNull { it.gateway as? Inet4Address }
            .filter { gateway ->
                !gateway.isAnyLocalAddress && !gateway.isLoopbackAddress && !gateway.isMulticastAddress &&
                    localAddresses.none { it.address == gateway } &&
                    localAddresses.any { isUsableGatewayOnSubnet(it, gateway) }
            }
            .mapNotNull { it.hostAddress }
            .distinct()
            .singleOrNull()
    }

    private fun isUsableGatewayOnSubnet(local: LinkAddress, gateway: Inet4Address): Boolean {
        val address = local.address as? Inet4Address ?: return false
        val prefixLength = local.prefixLength
        if (prefixLength !in 1..32 || address.isAnyLocalAddress ||
            address.isLoopbackAddress || address.isMulticastAddress) return false

        fun asLong(ip: Inet4Address): Long = ip.address.fold(0L) { value, byte ->
            (value shl 8) or (byte.toLong() and 0xffL)
        }

        val mask = (0xffff_ffffL shl (32 - prefixLength)) and 0xffff_ffffL
        val gatewayValue = asLong(gateway)
        if (gatewayValue == 0xffff_ffffL) return false
        if ((asLong(address) and mask) != (gatewayValue and mask)) return false
        // Reject subnet/network and broadcast addresses (except valid /31 point-to-point hosts).
        val hostMask = mask xor 0xffff_ffffL
        val host = gatewayValue and hostMask
        return prefixLength >= 31 || (host != 0L && host != hostMask)
    }

    // Must hold requestLock. Clearing callback before unregister makes cleanup idempotent.
    private fun releaseRequestLocked(owner: WifiRequest) {
        if (activeRequest === owner) activeRequest = null
        val callback = owner.callback
        owner.callback = null
        try {
            if (callback != null) connectivityManager.unregisterNetworkCallback(callback)
        } catch (_: Exception) {
            // Android may already have removed an unavailable or failed request.
        } finally {
            owner.closeFlow()
        }
    }

    fun release() {
        synchronized(requestLock) {
            activeRequest?.let { releaseRequestLocked(it) }
        }
    }
}
