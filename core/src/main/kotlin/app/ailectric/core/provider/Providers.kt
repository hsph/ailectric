package app.ailectric.core.provider

import app.ailectric.core.geo.GeoPoint
import app.ailectric.core.model.Poi
import app.ailectric.core.model.PoiCategory
import app.ailectric.core.model.Station

data class StationQuery(val center: GeoPoint, val radiusMeters: Double, val maxResults: Int = 2000)

data class StationResult(
    val stations: List<Station>,
    /** The source hit its result cap, so more stations may exist than were returned. */
    val truncated: Boolean,
)

/** Thrown for every provider failure so callers can fall back to cached data. */
class ProviderException(message: String, cause: Throwable? = null, val retryable: Boolean = true) :
    Exception(message, cause)

/** A swappable source of charging stations (Open Charge Map, OSM, later an own backend). */
interface StationProvider {
    val id: String
    suspend fun search(query: StationQuery): StationResult
}

/** A swappable source of POIs, independent of the concrete category. */
interface PoiProvider {
    val id: String
    suspend fun near(points: List<GeoPoint>, radiusMeters: Int, categories: List<PoiCategory>): List<Poi>
}
