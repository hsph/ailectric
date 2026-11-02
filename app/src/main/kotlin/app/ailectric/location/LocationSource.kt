package app.ailectric.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
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
        val listener = LocationListener { l: Location -> trySend(GeoPoint(l.latitude, l.longitude)) }
        val providers = usableProviders(manager)
        providers.firstNotNullOfOrNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
            ?.let { trySend(GeoPoint(it.latitude, it.longitude)) }
        providers.forEach {
            manager.requestLocationUpdates(it, UPDATE_INTERVAL_MS, UPDATE_DISTANCE_M, listener, Looper.getMainLooper())
        }
        awaitClose { manager.removeUpdates(listener) }
    }

    private fun usableProviders(manager: LocationManager): List<String> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && manager.hasProvider(LocationManager.FUSED_PROVIDER)) {
            return listOf(LocationManager.FUSED_PROVIDER)
        }
        return listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { it in manager.allProviders }
    }

    private companion object {
        const val UPDATE_INTERVAL_MS = 10_000L
        const val UPDATE_DISTANCE_M = 200f
    }
}
