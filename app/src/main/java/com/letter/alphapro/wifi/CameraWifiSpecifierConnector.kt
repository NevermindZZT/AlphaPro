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

    private var activeCallback: ConnectivityManager.NetworkCallback? = null

    fun connectCameraWifi(ssid: String, password: String, bssid: String? = null): Flow<CameraWifiState> = callbackFlow {
        trySend(CameraWifiState.Connecting)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val specifierBuilder = WifiNetworkSpecifier.Builder()
                .setSsid(ssid.trim())
                .setWpa2Passphrase(password.trim())

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

            var currentGateway: String? = null

            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    super.onAvailable(network)
                    connectivityManager.bindProcessToNetwork(network)

                    val linkProperties = connectivityManager.getLinkProperties(network)
                    val gw = resolveGateway(linkProperties)
                    currentGateway = gw
                    trySend(CameraWifiState.Connected(gw, network))
                }

                override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) {
                    super.onLinkPropertiesChanged(network, properties)
                    connectivityManager.bindProcessToNetwork(network)
                    val gw = resolveGateway(properties)
                    if (gw != currentGateway) {
                        currentGateway = gw
                        trySend(CameraWifiState.Connected(gw, network))
                    }
                }

                override fun onUnavailable() {
                    super.onUnavailable()
                    trySend(CameraWifiState.Error("Wi-Fi 连接超时或被拒绝"))
                }

                override fun onLost(network: Network) {
                    super.onLost(network)
                    connectivityManager.bindProcessToNetwork(null)
                    trySend(CameraWifiState.Idle)
                }
            }

            activeCallback = callback
            try {
                connectivityManager.requestNetwork(request, callback, 60_000)
            } catch (e: Exception) {
                trySend(CameraWifiState.Error("发起 Wi-Fi 连接异常: " + (e.message ?: "")))
            }

            awaitClose {
                release()
            }
        } else {
            trySend(CameraWifiState.Error("系统版本较低，请在系统 Wi-Fi 设置中手动连接 SSID: $ssid"))
            awaitClose {}
        }
    }

    private fun resolveGateway(linkProperties: LinkProperties?): String {
        if (linkProperties == null) return "192.168.122.1"
        val localIps = linkProperties.linkAddresses.map { it.address }.filterIsInstance<Inet4Address>()
        val defaultRouteGw = linkProperties.routes
            .filter { it.isDefaultRoute }
            .mapNotNull { it.gateway as? Inet4Address }
            .firstOrNull { gw ->
                !gw.isAnyLocalAddress && !gw.isLoopbackAddress && localIps.none { it == gw }
            }
        return defaultRouteGw?.hostAddress ?: "192.168.122.1"
    }

    fun release() {
        activeCallback?.let {
            try {
                connectivityManager.unregisterNetworkCallback(it)
                connectivityManager.bindProcessToNetwork(null)
            } catch (_: Exception) {}
        }
        activeCallback = null
    }
}
