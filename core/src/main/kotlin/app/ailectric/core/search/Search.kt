package app.ailectric.core.search

import app.ailectric.core.cache.Coverage
import app.ailectric.core.cache.SearchPolicy
import app.ailectric.core.filter.HikingFilter
import app.ailectric.core.filter.NearbyPoi
import app.ailectric.core.filter.StationFilter
import app.ailectric.core.filter.airlineMetersFrom
import app.ailectric.core.filter.nearbyPois
import app.ailectric.core.geo.BoundingBox
import app.ailectric.core.geo.Distance
import app.ailectric.core.geo.DistanceKind
import app.ailectric.core.geo.Geo
import app.ailectric.core.geo.GeoPoint
import app.ailectric.core.model.Poi
import app.ailectric.core.model.PoiCatalog
import app.ailectric.core.model.Station
import app.ailectric.core.provider.PoiProvider
import app.ailectric.core.provider.ProviderException
import app.ailectric.core.provider.StationProvider
import app.ailectric.core.provider.StationQuery

/** Persistent store for stations and POIs. Room in the app, in-memory in tests. */
interface SearchCache {
    suspend fun coverages(providerId: String): List<Coverage>
    suspend fun stationsIn(providerId: String, box: BoundingBox): List<Station>
    suspend fun store(providerId: String, coverage: Coverage, stations: List<Station>, truncated: Boolean)
    suspend fun wasTruncated(providerId: String, coverage: Coverage): Boolean
    suspend fun poisFor(stationId: String, notBeforeMillis: Long): List<Poi>?
    suspend fun storePois(stationId: String, pois: List<Poi>, nowMillis: Long)
}

enum class Freshness { LIVE_FETCH, CACHED, STALE_OFFLINE }

data class StationLoad(
    val stations: List<Station>,
    val freshness: Freshness,
    val providerId: String?,
    val truncated: Boolean,
    /** Why the preferred source wasn't used, for display. */
    val notice: String?,
)

class StationRepository(
    /** Tried in order, so the richest source goes first. */
    private val providers: List<StationProvider>,
    private val cache: SearchCache,
    private val policy: SearchPolicy = SearchPolicy(),
    private val now: () -> Long = System::currentTimeMillis,
) {
    suspend fun load(center: GeoPoint, radiusMeters: Double): StationLoad {
        val box = BoundingBox.around(center, radiusMeters)
        val failures = mutableListOf<ProviderException>()
        for (provider in providers) {
            val covered = cache.coverages(provider.id)
                .firstOrNull { policy.isCovered(center, radiusMeters, it, now()) }
            if (covered != null) {
                return StationLoad(
                    cache.stationsIn(provider.id, box), Freshness.CACHED, provider.id,
                    cache.wasTruncated(provider.id, covered), failures.summary(),
                )
            }
            val fetchRadius = policy.fetchRadius(radiusMeters)
            val result = try {
                provider.search(StationQuery(center, fetchRadius))
            } catch (e: ProviderException) {
                failures += e
                continue
            }
            cache.store(provider.id, Coverage(center, fetchRadius, now()), result.stations, result.truncated)
            return StationLoad(result.stations, Freshness.LIVE_FETCH, provider.id, result.truncated, failures.summary())
        }
        // Everything failed: whatever we know is better than nothing, as long as we say so.
        for (provider in providers) {
            val stale = cache.stationsIn(provider.id, box)
            if (stale.isNotEmpty()) {
                return StationLoad(stale, Freshness.STALE_OFFLINE, provider.id, false, failures.summary())
            }
        }
        throw ProviderException(failures.summary() ?: "Keine Datenquelle konfiguriert", failures.firstOrNull())
    }

    /** All causes: the first alone would blame a missing key when the fallback is what broke. */
    private fun List<ProviderException>.summary(): String? =
        mapNotNull { it.message }.distinct().joinToString(" · ").ifBlank { null }
}

data class StationHit(
    val station: Station,
    val distance: Distance,
    /** Only populated when a hiking filter is active. */
    val nearby: List<NearbyPoi> = emptyList(),
)

data class SearchResult(
    val hits: List<StationHit>,
    /** Operators of all stations in range, to offer in the filter regardless of its state. */
    val operators: List<String>,
    val freshness: Freshness,
    val truncated: Boolean,
    val notices: List<String>,
    val attributions: List<String>,
)

class ChargerSearch(
    private val stations: StationRepository,
    private val pois: PoiProvider,
    private val cache: SearchCache,
    private val now: () -> Long = System::currentTimeMillis,
) {
    suspend fun search(center: GeoPoint, filter: StationFilter): SearchResult {
        val radius = filter.radiusKm * 1000
        val load = stations.load(center, radius)
        val inRange = load.stations
            .map { StationHit(it, Distance(Geo.distanceMeters(center, it.location), DistanceKind.AIRLINE)) }
            .filter { it.distance.meters <= radius }
            .sortedBy { it.distance.meters }
        val matching = inRange.filter { filter.matches(it.station) }
        val notices = listOfNotNull(load.notice).toMutableList()
        val hiking = filter.hiking
        val hits = if (hiking == null) matching else withHiking(matching, hiking, notices)
        return SearchResult(
            hits = hits,
            operators = inRange.mapNotNull { it.station.operator }.distinct().sorted(),
            freshness = load.freshness,
            truncated = load.truncated,
            notices = notices,
            attributions = inRange.map { it.station.source.attribution }.distinct(),
        )
    }

    /** Hiking lookups are per station, so only the closest are checked to stay polite to Overpass. */
    private suspend fun withHiking(
        hits: List<StationHit>,
        filter: HikingFilter,
        notices: MutableList<String>,
    ): List<StationHit> {
        val checked = hits.take(MAX_STATIONS_CHECKED)
        val found = HashMap<String, List<Poi>>()
        val missing = mutableListOf<StationHit>()
        for (hit in checked) {
            val cached = cache.poisFor(hit.station.id, now() - POI_MAX_AGE_MS)
            if (cached != null) found[hit.station.id] = cached else missing += hit
        }
        var failed = false
        for (batch in missing.chunked(BATCH_SIZE)) {
            val result = try {
                pois.near(batch.map { it.station.location }, SEARCH_RADIUS_M, PoiCatalog.HIKING)
            } catch (e: ProviderException) {
                failed = true
                notices += e.message ?: "Wanderdaten nicht verfügbar"
                break
            }
            for (hit in batch) {
                val mine = result.filter { it.airlineMetersFrom(hit.station) <= SEARCH_RADIUS_M }
                found[hit.station.id] = mine
                cache.storePois(hit.station.id, mine, now())
            }
        }
        if (hits.size > checked.size) {
            notices += "Wanderwege nur für die ${checked.size} nächsten von ${hits.size} Stationen geprüft"
        }
        if (failed) notices += "Wanderdaten unvollständig: nicht alle Stationen konnten geprüft werden"
        notices += "Wege-Entfernungen sind Luftlinie, die Erreichbarkeit zu Fuß ist nicht geprüft"
        return checked.mapNotNull { hit ->
            val nearby = nearbyPois(hit.station, found[hit.station.id].orEmpty(), filter)
            if (nearby.isEmpty()) null else hit.copy(nearby = nearby)
        }
    }

    companion object {
        const val MAX_STATIONS_CHECKED = 24
        const val BATCH_SIZE = 8
        /** Always the largest filter preset, so changing the filter needs no new request. */
        const val SEARCH_RADIUS_M = 2000
        const val POI_MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000
    }
}
