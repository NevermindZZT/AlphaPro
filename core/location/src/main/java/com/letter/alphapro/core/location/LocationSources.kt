package com.letter.alphapro.core.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import com.google.android.gms.location.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

data class GeoCoordinate(
    val latitude: Double,
    val longitude: Double,
    val altitude: Double = 0.0,
    val accuracy: Float = 0f,
    val speed: Float = 0f,
    val timestamp: Long = System.currentTimeMillis()
)

interface LocationSource {
    fun getLocationUpdates(intervalMs: Long = 5000L): Flow<GeoCoordinate>
}

@SuppressLint("MissingPermission")
class FusedLocationSource(private val context: Context) : LocationSource {
    private val fusedClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    override fun getLocationUpdates(intervalMs: Long): Flow<GeoCoordinate> = callbackFlow {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
            .setMinUpdateIntervalMillis(intervalMs / 2)
            .setGranularity(Granularity.GRANULARITY_FINE)
            .build()

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { loc ->
                    trySend(
                        GeoCoordinate(
                            latitude = loc.latitude,
                            longitude = loc.longitude,
                            altitude = loc.altitude,
                            accuracy = loc.accuracy,
                            speed = loc.speed,
                            timestamp = loc.time
                        )
                    )
                }
            }
        }

        // Immediately send cached last location so camera gets instant GPS fix upon connection
        fusedClient.lastLocation.addOnSuccessListener { loc ->
            if (loc != null) {
                trySend(
                    GeoCoordinate(
                        latitude = loc.latitude,
                        longitude = loc.longitude,
                        altitude = loc.altitude,
                        accuracy = loc.accuracy,
                        speed = loc.speed,
                        timestamp = loc.time
                    )
                )
            }
        }

        fusedClient.requestLocationUpdates(request, callback, Looper.getMainLooper())

        awaitClose {
            fusedClient.removeLocationUpdates(callback)
        }
    }
}

@SuppressLint("MissingPermission")
class PlatformLocationSource(private val context: Context) : LocationSource {
    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    override fun getLocationUpdates(intervalMs: Long): Flow<GeoCoordinate> = callbackFlow {
        val listener = LocationListener { loc: Location ->
            trySend(
                GeoCoordinate(
                    latitude = loc.latitude,
                    longitude = loc.longitude,
                    altitude = loc.altitude,
                    accuracy = loc.accuracy,
                    speed = loc.speed,
                    timestamp = loc.time
                )
            )
        }

        val provider = when {
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> LocationManager.PASSIVE_PROVIDER
        }

        locationManager.requestLocationUpdates(provider, intervalMs, 0f, listener, Looper.getMainLooper())

        awaitClose {
            locationManager.removeUpdates(listener)
        }
    }
}
