package app.ailectric.core.model

import app.ailectric.core.geo.GeoPoint
import kotlinx.serialization.Serializable

@Serializable
enum class ConnectorType(val label: String) {
    CCS2("CCS"),
    CCS1("CCS (Typ 1)"),
    CHADEMO("CHAdeMO"),
    TYPE2("Typ 2"),
    TYPE2_TETHERED("Typ 2 (Kabel)"),
    TYPE1("Typ 1"),
    TESLA_SUPERCHARGER("Tesla Supercharger"),
    SCHUKO("Schuko"),
    CEE("CEE"),
    OTHER("Sonstige"),
}

@Serializable
enum class CurrentType { AC, DC, UNKNOWN }

/** Live state of a charge point. Only a real-time source may report anything but [UNKNOWN]. */
@Serializable
enum class Availability { AVAILABLE, OCCUPIED, OUT_OF_ORDER, UNKNOWN }

/** Long-lived state from the static registry. "Operational" does not mean "free right now". */
@Serializable
enum class OperationalStatus { OPERATIONAL, NOT_OPERATIONAL, PLANNED, UNKNOWN }

/** A single plug. Every field a source may omit is nullable; null renders as "unbekannt". */
@Serializable
data class Connector(
    val type: ConnectorType,
    val currentType: CurrentType = CurrentType.UNKNOWN,
    val powerKw: Double? = null,
    val availability: Availability = Availability.UNKNOWN,
)

/** One charging device (EVSE) at a site. A device can offer several connectors. */
@Serializable
data class ChargingDevice(
    val reference: String? = null,
    val connectors: List<Connector>,
    val status: OperationalStatus = OperationalStatus.UNKNOWN,
)

@Serializable
data class Address(
    val street: String? = null,
    val postcode: String? = null,
    val town: String? = null,
    val countryCode: String? = null,
) {
    fun singleLine(): String? = listOfNotNull(
        street, listOfNotNull(postcode, town).joinToString(" ").ifBlank { null }, countryCode,
    ).joinToString(", ").ifBlank { null }
}

/** Where a record came from. Must stay visible to the user for licence reasons. */
@Serializable
data class DataSource(val provider: String, val attribution: String, val license: String? = null)

/** A charging location (site) with its devices. */
@Serializable
data class Station(
    /** Globally unique, prefixed with the provider ("ocm:123", "osm:node/456"). */
    val id: String,
    val name: String?,
    val operator: String?,
    val address: Address,
    val location: GeoPoint,
    val devices: List<ChargingDevice>,
    /** Number of charge points reported by the source; may exceed what [devices] details. */
    val declaredPoints: Int? = null,
    val openingHours: String? = null,
    val pricing: String? = null,
    val access: String? = null,
    val status: OperationalStatus = OperationalStatus.UNKNOWN,
    /** ISO-8601 instant of the last change at the source. */
    val lastUpdated: String? = null,
    val source: DataSource,
) {
    val connectors: List<Connector> get() = devices.flatMap { it.connectors }

    val maxPowerKw: Double? get() = connectors.mapNotNull { it.powerKw }.maxOrNull()

    val connectorTypes: Set<ConnectorType> get() = connectors.mapTo(linkedSetOf()) { it.type }

    /** Null when no count is known at all, rather than a guessed 0. */
    val pointCount: Int? get() = declaredPoints ?: devices.size.takeIf { it > 0 }

    val availability: Availability
        get() {
            val all = connectors.map { it.availability }
            return when {
                Availability.AVAILABLE in all -> Availability.AVAILABLE
                all.isEmpty() || Availability.UNKNOWN in all -> Availability.UNKNOWN
                Availability.OCCUPIED in all -> Availability.OCCUPIED
                else -> Availability.OUT_OF_ORDER
            }
        }
}
