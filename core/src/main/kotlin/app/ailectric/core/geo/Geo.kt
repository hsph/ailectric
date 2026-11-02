package app.ailectric.core.geo

import kotlinx.serialization.Serializable
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** WGS84 coordinate in decimal degrees. Longitude is always normalised to -180..180. */
@Serializable
data class GeoPoint(val lat: Double, val lon: Double) {
    init {
        require(lat in -90.0..90.0) { "latitude out of range: $lat" }
        require(lon in -180.0..180.0) { "longitude out of range: $lon" }
    }

    companion object {
        /** Returns null instead of throwing, for parsing untrusted provider data. */
        fun orNull(lat: Double?, lon: Double?): GeoPoint? {
            if (lat == null || lon == null) return null
            if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
            // 0/0 is the classic "missing coordinate" marker in imported data and is open sea.
            if (lat == 0.0 && lon == 0.0) return null
            return GeoPoint(lat, lon)
        }
    }
}

@Serializable
data class BoundingBox(val south: Double, val west: Double, val north: Double, val east: Double) {
    fun contains(p: GeoPoint) = p.lat in south..north && p.lon in west..east

    fun union(other: BoundingBox) = BoundingBox(
        min(south, other.south), min(west, other.west),
        max(north, other.north), max(east, other.east),
    )

    companion object {
        fun around(center: GeoPoint, radiusMeters: Double): BoundingBox {
            val dLat = Math.toDegrees(radiusMeters / Geo.EARTH_RADIUS_M)
            val cosLat = max(cos(Math.toRadians(center.lat)), 0.01)
            val dLon = Math.toDegrees(radiusMeters / Geo.EARTH_RADIUS_M / cosLat)
            return BoundingBox(
                max(center.lat - dLat, -90.0), max(center.lon - dLon, -180.0),
                min(center.lat + dLat, 90.0), min(center.lon + dLon, 180.0),
            )
        }
    }
}

object Geo {
    const val EARTH_RADIUS_M = 6_371_008.8

    /** Great-circle distance (haversine). This is always a straight line, never a road distance. */
    fun distanceMeters(a: GeoPoint, b: GeoPoint): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val h = sin(dLat / 2).let { it * it } +
            cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2).let { it * it }
        return 2 * EARTH_RADIUS_M * asin(min(1.0, sqrt(h)))
    }

    fun lengthMeters(line: List<GeoPoint>): Double =
        line.zipWithNext { a, b -> distanceMeters(a, b) }.sum()

    /** Shortest straight-line distance from [p] to a polyline, using a local planar projection. */
    fun distanceToLineMeters(p: GeoPoint, line: List<GeoPoint>): Double {
        if (line.isEmpty()) return Double.POSITIVE_INFINITY
        if (line.size == 1) return distanceMeters(p, line[0])
        val mPerDegLat = EARTH_RADIUS_M * PI / 180
        val mPerDegLon = mPerDegLat * cos(Math.toRadians(p.lat))
        var best = Double.POSITIVE_INFINITY
        for ((a, b) in line.zipWithNext()) {
            val ax = (a.lon - p.lon) * mPerDegLon
            val ay = (a.lat - p.lat) * mPerDegLat
            val bx = (b.lon - p.lon) * mPerDegLon
            val by = (b.lat - p.lat) * mPerDegLat
            val dx = bx - ax
            val dy = by - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 == 0.0) 0.0 else (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
            val cx = ax + t * dx
            val cy = ay + t * dy
            best = min(best, sqrt(cx * cx + cy * cy))
        }
        return best
    }
}

/** How a distance was determined. The UI must label it; an airline value is never a route. */
enum class DistanceKind { AIRLINE, DRIVING, WALKING }

data class Distance(val meters: Double, val kind: DistanceKind) {
    /** German-formatted value including what kind of distance it is. */
    fun label(): String {
        val value = if (meters < 1000) "${meters.toInt()} m"
        else String.format(java.util.Locale.GERMANY, "%.1f km", meters / 1000)
        return when (kind) {
            DistanceKind.AIRLINE -> "$value Luftlinie"
            DistanceKind.DRIVING -> "$value Fahrstrecke"
            DistanceKind.WALKING -> "$value Fußweg"
        }
    }
}
