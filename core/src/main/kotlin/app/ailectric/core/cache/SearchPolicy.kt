package app.ailectric.core.cache

import app.ailectric.core.geo.Geo
import app.ailectric.core.geo.GeoPoint

/** An area for which a complete provider response is stored. */
data class Coverage(val center: GeoPoint, val radiusMeters: Double, val fetchedAtMillis: Long)

/**
 * Decides whether a search needs the network. Requests overfetch by [marginMeters], so driving
 * up to that far stays within the stored circle and causes no API call.
 */
class SearchPolicy(
    val marginMeters: Double = 25_000.0,
    val maxAgeMillis: Long = 7L * 24 * 60 * 60 * 1000,
) {
    fun fetchRadius(requestedMeters: Double) = requestedMeters + marginMeters

    fun isCovered(center: GeoPoint, radiusMeters: Double, coverage: Coverage, nowMillis: Long): Boolean {
        if (nowMillis - coverage.fetchedAtMillis > maxAgeMillis) return false
        return Geo.distanceMeters(center, coverage.center) + radiusMeters <= coverage.radiusMeters
    }
}
