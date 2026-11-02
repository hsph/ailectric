package app.ailectric.core.provider

import app.ailectric.core.geo.BoundingBox
import app.ailectric.core.geo.Geo
import app.ailectric.core.geo.GeoPoint
import app.ailectric.core.model.Address
import app.ailectric.core.model.Availability
import app.ailectric.core.model.ChargingDevice
import app.ailectric.core.model.Connector
import app.ailectric.core.model.ConnectorType
import app.ailectric.core.model.CurrentType
import app.ailectric.core.model.DataSource
import app.ailectric.core.model.OperationalStatus
import app.ailectric.core.model.Station
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonTransformingSerializer
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * The Swiss charging data of the Federal Office of Energy (ich-tanke-strom.ch), with a live state
 * per charge point. Licence "Opendata BY ASK": free use, source required, commercial use needs
 * permission. There are only whole-country files, so the static one is kept in memory.
 * https://github.com/SFOE/ichtankestrom_Documentation
 */
class SwissProvider(
    private val client: OkHttpClient = Http.client(),
    private val dataUrl: String = "$BASE/data/oicp/ch.bfe.ladestellen-elektromobilitaet.json",
    private val statusUrl: String = "$BASE/status/oicp/ch.bfe.ladestellen-elektromobilitaet.json",
    private val now: () -> Long = System::currentTimeMillis,
) : StationProvider {
    override val id = "bfe"
    override val maxAgeMillis = LIVE_MAX_AGE_MS

    private val lock = Mutex()
    private var sites: List<List<BfeEvse>> = emptyList()
    private var sitesLoadedAt: Long? = null

    override suspend fun search(query: StationQuery): StationResult {
        if (!BoundingBox.around(query.center, query.radiusMeters).overlaps(COVERAGE)) {
            return StationResult(emptyList(), truncated = false)
        }
        val all = staticSites()
        val states = parse<BfeStatusFile>(fetch(statusUrl)).EVSEStatuses
            .flatMap { it.EVSEStatusRecord }.associate { it.EvseID to it.EVSEStatus }
        val stations = withContext(Dispatchers.Default) {
            all.mapNotNull { site -> toStation(site, states) }
                .filter { Geo.distanceMeters(query.center, it.location) <= query.radiusMeters }
        }
        return StationResult(stations, truncated = false)
    }

    private suspend fun staticSites() = lock.withLock {
        val loadedAt = sitesLoadedAt
        if (loadedAt == null || now() - loadedAt > STATIC_MAX_AGE_MS) {
            val body = fetch(dataUrl)
            sites = withContext(Dispatchers.Default) {
                // Records are per charge point; a site is one operator on one spot.
                parse<BfeDataFile>(body).EVSEData
                    .flatMap { operator -> operator.EVSEDataRecord.map { it.copy(operator = operator.OperatorName) } }
                    .filter { it.Accessibility != "Test Station" }
                    .groupBy { it.siteKey() }.filterKeys { it != null }.values.toList()
            }
            sitesLoadedAt = now()
        }
        sites
    }

    private fun toStation(site: List<BfeEvse>, states: Map<String, String?>): Station? {
        val first = site.first()
        val location = first.location() ?: return null
        val address = first.Address
        val devices = site.map { evse ->
            val availability = availability(states[evse.EvseID])
            val power = evse.ChargingFacilities.orEmpty()
            val connectors = evse.Plugs.orEmpty().mapIndexed { i, plug ->
                val facility = power.getOrNull(i)
                Connector(
                    connectorType(plug), currentType(facility?.powertype),
                    (facility?.power as? JsonPrimitive)?.content?.toDoubleOrNull()?.takeIf { it > 0 }, availability,
                )
            }
            ChargingDevice(evse.EvseID, connectors, OperationalStatus.UNKNOWN)
        }
        return Station(
            id = "bfe:${first.EvseID}",
            name = first.ChargingStationNames.orEmpty().let { names ->
                (names.firstOrNull { it.lang == "de" } ?: names.firstOrNull())?.value?.trim()?.ifBlank { null }
            },
            operator = first.operator?.trim()?.ifBlank { null },
            address = Address(
                street = address?.Street?.replace(Regex("\\s+"), " ")?.trim()?.ifBlank { null },
                postcode = address?.PostalCode?.trim()?.ifBlank { null },
                town = address?.City?.trim()?.ifBlank { null },
                countryCode = address?.Country?.take(2),
            ),
            location = location,
            devices = devices,
            access = when (first.Accessibility) {
                "Free publicly accessible" -> "Öffentlich, kostenlos"
                "Paying publicly accessible" -> "Öffentlich, kostenpflichtig"
                "Restricted access" -> "Eingeschränkt"
                else -> null
            },
            source = DataSource("ich-tanke-strom.ch", "Bundesamt für Energie BFE", "Opendata BY ASK"),
        )
    }

    private suspend fun fetch(url: String): String {
        val request = Request.Builder().url(url).header("User-Agent", Http.USER_AGENT).build()
        return client.fetchBody(request, "ich-tanke-strom.ch")
    }

    private suspend inline fun <reified T> parse(body: String): T = withContext(Dispatchers.Default) {
        try {
            parser.decodeFromString<T>(body)
        } catch (e: IllegalArgumentException) {
            throw ProviderException("ich-tanke-strom.ch: unerwartete Antwort", e)
        }
    }

    internal companion object {
        const val BASE = "https://data.geo.admin.ch/ch.bfe.ladestellen-elektromobilitaet"
        const val LIVE_MAX_AGE_MS = 5L * 60 * 1000
        const val STATIC_MAX_AGE_MS = 24L * 60 * 60 * 1000
        val COVERAGE = BoundingBox(45.8, 5.9, 47.85, 10.55)
        // Lenient because some operators send postcodes as numbers.
        val parser = Json { ignoreUnknownKeys = true; explicitNulls = false; coerceInputValues = true; isLenient = true }

        fun availability(state: String?) = when (state) {
            "Available" -> Availability.AVAILABLE
            "Occupied", "Reserved" -> Availability.OCCUPIED
            "OutOfService" -> Availability.OUT_OF_ORDER
            else -> Availability.UNKNOWN
        }

        fun connectorType(plug: String) = when {
            "CCS Combo 2" in plug -> ConnectorType.CCS2
            "CCS Combo 1" in plug -> ConnectorType.CCS1
            plug == "CHAdeMO" -> ConnectorType.CHADEMO
            plug == "Type 2 Outlet" -> ConnectorType.TYPE2
            "Type 2" in plug -> ConnectorType.TYPE2_TETHERED
            "Type 1" in plug -> ConnectorType.TYPE1
            "Tesla" in plug -> ConnectorType.TESLA_SUPERCHARGER
            else -> ConnectorType.OTHER
        }

        fun currentType(type: String?) = when (type) {
            "DC" -> CurrentType.DC
            "AC_1_PHASE", "AC_3_PHASE" -> CurrentType.AC
            else -> CurrentType.UNKNOWN
        }
    }
}

private fun BoundingBox.overlaps(o: BoundingBox) = south < o.north && o.south < north && west < o.east && o.west < east

/** Most operators send a list of names per language, some a single object. */
internal object BfeNamesSerializer : JsonTransformingSerializer<List<BfeName>>(ListSerializer(BfeName.serializer())) {
    override fun transformDeserialize(element: JsonElement) =
        if (element is JsonArray) element else JsonArray(listOf(element))
}

@Serializable internal data class BfeDataFile(val EVSEData: List<BfeOperator> = emptyList())
@Serializable internal data class BfeStatusFile(val EVSEStatuses: List<BfeStatusGroup> = emptyList())
@Serializable internal data class BfeStatusGroup(val EVSEStatusRecord: List<BfeStatus> = emptyList())
@Serializable internal data class BfeStatus(val EvseID: String, val EVSEStatus: String? = null)
@Serializable internal data class BfeOperator(val OperatorName: String? = null, val EVSEDataRecord: List<BfeEvse> = emptyList())
@Serializable internal data class BfeName(val lang: String? = null, val value: String? = null)
@Serializable internal data class BfeGeo(val Google: String? = null)
@Serializable internal data class BfeFacility(val power: JsonElement? = null, val powertype: String? = null)

@Serializable
internal data class BfeAddress(
    val City: String? = null,
    val Country: String? = null,
    val PostalCode: String? = null,
    val Street: String? = null,
)

@Serializable
internal data class BfeEvse(
    val EvseID: String,
    val Accessibility: String? = null,
    val Address: BfeAddress? = null,
    val ChargingFacilities: List<BfeFacility>? = null,
    @Serializable(with = BfeNamesSerializer::class) val ChargingStationNames: List<BfeName>? = null,
    val GeoCoordinates: BfeGeo? = null,
    val Plugs: List<String>? = null,
    val operator: String? = null,
) {
    fun location(): GeoPoint? {
        val parts = GeoCoordinates?.Google?.trim()?.split(Regex("[\\s,]+")).orEmpty()
        return GeoPoint.orNull(parts.getOrNull(0)?.toDoubleOrNull(), parts.getOrNull(1)?.toDoubleOrNull())
    }

    fun siteKey(): String? = location()?.let { "%.4f,%.4f,%s".format(java.util.Locale.ROOT, it.lat, it.lon, operator) }
}
