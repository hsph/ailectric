package app.ailectric.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.core.content.ContextCompat
import app.ailectric.core.geo.GeoPoint
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** Framework location rather than Play Services: fewer dependencies, same result in a car. */
class LocationSource(private val context: Context) {
    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun updates(): Flow<GeoPoint> = callbackFlow {
        if (!hasPermission()) {
            close()
            return@callbackFlow
        }
        val manager = context.getSystemService(LocationManager::class.java)
        // An object, not a lambda: below Android 11 the other listener methods are abstract.
        val listener = object : LocationListener {
            override fun onLocationChanged(l: Location) {
                trySend(GeoPoint(l.latitude, l.longitude))
            }
            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) = Unit
            @Deprecated("Only called below Android 10")
            override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit
        }
        val providers = providersToUse(manager.allProviders)
        providers.firstNotNullOfOrNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
            ?.let { trySend(GeoPoint(it.latitude, it.longitude)) }
        providers.forEach {
            manager.requestLocationUpdates(it, UPDATE_INTERVAL_MS, UPDATE_DISTANCE_M, listener, Looper.getMainLooper())
        }
        awaitClose { manager.removeUpdates(listener) }
    }

    companion object {
        private const val FUSED = "fused"

        /**
         * All of them, because a provider can exist yet be disabled (fused without Google location
         * accuracy) and then never deliver. Requests on a disabled provider just stay inactive.
         */
        fun providersToUse(available: List<String>): List<String> =
            listOf(FUSED, LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).filter { it in available }

        private const val UPDATE_INTERVAL_MS = 10_000L
        private const val UPDATE_DISTANCE_M = 200f
    }
}
