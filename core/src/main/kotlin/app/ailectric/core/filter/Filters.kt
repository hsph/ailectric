package app.ailectric.core.filter

import app.ailectric.core.geo.Geo
import app.ailectric.core.model.Availability
import app.ailectric.core.model.Connector
import app.ailectric.core.model.ConnectorType
import app.ailectric.core.model.CurrentType
import app.ailectric.core.model.Poi
import app.ailectric.core.model.Station
import kotlinx.serialization.Serializable

enum class AvailabilityFilter { ALL, FREE, UNKNOWN }

@Serializable
data class StationFilter(
    val minPowerKw: Double? = null,
    val maxPowerKw: Double? = null,
    val connectorTypes: Set<ConnectorType> = emptySet(),
    val currentTypes: Set<CurrentType> = emptySet(),
    val operators: Set<String> = emptySet(),
    val availability: AvailabilityFilter = AvailabilityFilter.ALL,
    /** Keep stations without a known power when a power filter is active. */
    val includeUnknownPower: Boolean = false,
    val radiusKm: Double = DEFAULT_RADIUS_KM,
    val hiking: HikingFilter? = null,
) {
    val isDefault: Boolean get() = this == StationFilter()

    /**
     * Power, plug and current must hold for one and the same connector: a site with 300 kW CCS
     * and 22 kW Type 2 is no match for "Type 2 with at least 150 kW".
     */
    fun matches(station: Station): Boolean {
        if (operators.isNotEmpty() && station.operator !in operators) return false
        when (availability) {
            AvailabilityFilter.ALL -> Unit
            AvailabilityFilter.FREE -> if (station.availability != Availability.AVAILABLE) return false
            AvailabilityFilter.UNKNOWN -> if (station.availability != Availability.UNKNOWN) return false
        }
        if (!hasConnectorCriteria) return true
        return station.connectors.any(::matchesConnector)
    }

    private val hasConnectorCriteria: Boolean
        get() = minPowerKw != null || maxPowerKw != null || connectorTypes.isNotEmpty() || currentTypes.isNotEmpty()

    private fun matchesConnector(c: Connector): Boolean {
        if (connectorTypes.isNotEmpty() && c.type !in connectorTypes) return false
        if (currentTypes.isNotEmpty() && c.currentType !in currentTypes) return false
        val power = c.powerKw
        if (minPowerKw != null || maxPowerKw != null) {
            if (power == null) return includeUnknownPower
            if (minPowerKw != null && power < minPowerKw) return false
            if (maxPowerKw != null && power > maxPowerKw) return false
        }
        return true
    }

    companion object {
        const val DEFAULT_RADIUS_KM = 50.0
    }
}

@Serializable
data class HikingFilter(
    val withinMeters: Int = 1000,
    val signpostedOnly: Boolean = false,
    val roundTripOnly: Boolean = false,
    val minLengthKm: Double? = null,
    val maxLengthKm: Double? = null,
    val maxAscentMeters: Double? = null,
) {
    /** Unknown attributes fail an active criterion: we only assert what the data says. */
    fun matches(poi: Poi): Boolean {
        val h = poi.hiking
        val active = signpostedOnly || roundTripOnly || minLengthKm != null || maxLengthKm != null || maxAscentMeters != null
        if (h == null) return !active
        if (signpostedOnly && !h.signposted) return false
        if (roundTripOnly && h.roundTrip != true) return false
        val km = h.lengthMeters?.div(1000)
        if (minLengthKm != null && (km == null || km < minLengthKm)) return false
        if (maxLengthKm != null && (km == null || km > maxLengthKm)) return false
        if (maxAscentMeters != null && (h.ascentMeters == null || h.ascentMeters > maxAscentMeters)) return false
        return true
    }

    companion object {
        val DISTANCE_PRESETS_M = listOf(500, 1000, 2000)
    }
}

/** Straight-line distance from a station to a POI, using trail geometry where there is any. */
fun Poi.airlineMetersFrom(station: Station): Double =
    if (geometry.isEmpty()) Geo.distanceMeters(station.location, location)
    else geometry.minOf { Geo.distanceToLineMeters(station.location, it) }

data class NearbyPoi(val poi: Poi, val airlineMeters: Double)

/** POIs near [station] that satisfy [filter], closest first. */
fun nearbyPois(station: Station, pois: List<Poi>, filter: HikingFilter): List<NearbyPoi> =
    pois.asSequence()
        .filter(filter::matches)
        .map { NearbyPoi(it, it.airlineMetersFrom(station)) }
        .filter { it.airlineMeters <= filter.withinMeters }
        .sortedBy { it.airlineMeters }
        .toList()
