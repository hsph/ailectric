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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

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
    val truncated: Boolean,
    /** Which sources failed, for display. */
    val notice: String?,
)

class StationRepository(
    /** All are asked and merged, so a station one source lacks still shows. Richest first. */
    private val providers: List<StationProvider>,
    private val cache: SearchCache,
    private val policy: SearchPolicy = SearchPolicy(),
    private val now: () -> Long = System::currentTimeMillis,
) {
    private class Outcome(
        val stations: List<Station>,
        val freshness: Freshness?,
        val truncated: Boolean = false,
        val failure: ProviderException? = null,
    )

    /** [onPartial] gets each intermediate state, so a slow source doesn't hold up the others. */
    suspend fun load(
        center: GeoPoint,
        radiusMeters: Double,
        onPartial: suspend (StationLoad) -> Unit = {},
    ): StationLoad = coroutineScope {
        if (providers.isEmpty()) throw ProviderException("Keine Datenquelle konfiguriert")
        val finished = Channel<Pair<Int, Outcome>>(providers.size)
        providers.forEachIndexed { i, provider ->
            launch { finished.send(i to fetch(provider, center, radiusMeters)) }
        }
        val outcomes = arrayOfNulls<Outcome>(providers.size)
        repeat(providers.size) { round ->
            val (i, outcome) = finished.receive()
            outcomes[i] = outcome
            val partial = combine(outcomes.filterNotNull())
            if (round < providers.size - 1 && partial.stations.isNotEmpty()) onPartial(partial)
        }
        val all = outcomes.filterNotNull()
        val result = combine(all)
        val networkAnswered = providers.indices.any { !providers[it].local && all[it].failure == null }
        if (result.stations.isEmpty() && !networkAnswered) {
            throw ProviderException(result.notice ?: "Keine Datenquelle verfügbar", all.firstNotNullOfOrNull { it.failure })
        }
        result
    }

    private suspend fun fetch(provider: StationProvider, center: GeoPoint, radiusMeters: Double): Outcome {
        val box = BoundingBox.around(center, radiusMeters)
        if (provider.local) {
            return try {
                Outcome(provider.search(StationQuery(center, radiusMeters)).stations, null)
            } catch (e: ProviderException) {
                Outcome(emptyList(), null, failure = e)
            }
        }
        val covered = cache.coverages(provider.id)
            .firstOrNull {
                policy.isCovered(center, radiusMeters, it, now(), provider.maxAgeMillis ?: policy.maxAgeMillis)
            }
        if (covered != null) {
            return Outcome(cache.stationsIn(provider.id, box), Freshness.CACHED, cache.wasTruncated(provider.id, covered))
        }
        val fetchRadius = policy.fetchRadius(radiusMeters)
        return try {
            val result = provider.search(StationQuery(center, fetchRadius))
            cache.store(provider.id, Coverage(center, fetchRadius, now()), result.stations, result.truncated)
            Outcome(result.stations, Freshness.LIVE_FETCH, result.truncated)
        } catch (e: ProviderException) {
            // What we know is better than nothing, as long as we say so.
            val stale = cache.stationsIn(provider.id, box).map { it.withoutLiveStatus() }
            Outcome(stale, Freshness.STALE_OFFLINE.takeIf { stale.isNotEmpty() }, failure = e)
        }
    }

    private fun combine(outcomes: List<Outcome>): StationLoad {
        val freshness = outcomes.mapNotNull { it.freshness }
        return StationLoad(
            stations = mergeStations(outcomes.map { it.stations }),
            freshness = when {
                Freshness.STALE_OFFLINE in freshness -> Freshness.STALE_OFFLINE
                Freshness.LIVE_FETCH in freshness -> Freshness.LIVE_FETCH
                else -> Freshness.CACHED
            },
            truncated = outcomes.any { it.truncated },
            notice = outcomes.mapNotNull { it.failure?.message }.distinct().joinToString(" · ").ifBlank { null },
        )
    }
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
    /** [onPartial] is not called for hiking searches, whose results need every station. */
    suspend fun search(
        center: GeoPoint,
        filter: StationFilter,
        onPartial: suspend (SearchResult) -> Unit = {},
    ): SearchResult {
        val radius = filter.radiusKm * 1000
        val load = stations.load(center, radius) { partial ->
            if (filter.hiking == null) onPartial(present(center, radius, filter, partial))
        }
        return present(center, radius, filter, load)
    }

    private suspend fun present(center: GeoPoint, radius: Double, filter: StationFilter, load: StationLoad): SearchResult {
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
        const val BATCH_SIZE = 12
        /** Always the largest filter preset, so changing the filter needs no new request. */
        const val SEARCH_RADIUS_M = 2000
        const val POI_MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000
    }
}
