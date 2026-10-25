package com.letter.alphapro.wifi

import android.content.Context
import android.net.*
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import androidx.annotation.RequiresApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

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

    fun connectCameraWifi(ssid: String, password: String): Flow<CameraWifiState> = callbackFlow {
        trySend(CameraWifiState.Connecting)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val specifier = WifiNetworkSpecifier.Builder()
                .setSsid(ssid)
                .setWpa2Passphrase(password)
                .build()

            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(specifier)
                .build()

            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    super.onAvailable(network)
                    connectivityManager.bindProcessToNetwork(network)

                    val linkProperties = connectivityManager.getLinkProperties(network)
                    val gatewayIp = linkProperties?.routes?.firstOrNull { it.isDefaultRoute }?.gateway?.hostAddress
                        ?: "192.168.122.1" // Common Sony default gateway

                    trySend(CameraWifiState.Connected(gatewayIp, network))
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
            connectivityManager.requestNetwork(request, callback)

            awaitClose {
                release()
            }
        } else {
            trySend(CameraWifiState.Error("系统版本较低，请在系统 Wi-Fi 设置中手动连接 SSID: $ssid"))
            awaitClose {}
        }
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
