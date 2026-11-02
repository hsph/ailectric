package app.ailectric.core.provider

import app.ailectric.core.geo.BoundingBox
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
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlin.math.ceil

/**
 * DOT-NL by the Dutch road data agency NDW: all public charge points of the Netherlands with live
 * availability, CC0, no key. https://docs.ndw.nu/en/data-uitwisseling/interface-beschrijvingen/dafne-api/
 *
 * One request returns at most 1000 features and covers at most 1 square degree, so areas are cut
 * up and dense ones cut further. NDW documents 10 requests per second, but in practice answered
 * 429 to about 6 and took 2 without complaint, hence the pause.
 */
class NdwProvider(
    private val client: OkHttpClient = Http.client(),
    private val baseUrl: String = "https://dotnl.ndw.nu/api/rest/geojson/dynamic-road-status/charge-point-data/v1/features",
    private val pauseMillis: Long = 400,
    private val retryMillis: Long = 2000,
) : StationProvider {
    override val id = "ndw"
    override val maxAgeMillis = LIVE_MAX_AGE_MS

    override suspend fun search(query: StationQuery): StationResult {
        val area = BoundingBox.around(query.center, query.radiusMeters).clippedToCoverage()
            ?: return StationResult(emptyList(), truncated = false)
        val found = LinkedHashMap<String, Station>()
        val pending = ArrayDeque(area.squareDegreeCells())
        var requests = 0
        var truncated = false
        while (pending.isNotEmpty()) {
            if (requests == MAX_REQUESTS) {
                truncated = true
                break
            }
            val cell = pending.removeFirst()
            if (requests > 0) delay(pauseMillis)
            requests++
            val features = try {
                fetchRetrying(cell)
            } catch (e: ProviderException) {
                if (found.isEmpty()) throw e
                truncated = true
                break
            }
            for (f in features) f.toStation()?.let { found[it.id] = it }
            if (features.size >= PAGE_LIMIT) pending.addAll(cell.quarters())
        }
        return StationResult(found.values.toList(), truncated)
    }

    private suspend fun fetchRetrying(box: BoundingBox) = try {
        fetch(box)
    } catch (e: ProviderException) {
        if (!e.retryable) throw e
        delay(retryMillis)
        fetch(box)
    }

    private suspend fun fetch(box: BoundingBox): List<NdwFeature> {
        val url = baseUrl.toHttpUrl().newBuilder()
            .addQueryParameter("bbox", "%.6f,%.6f,%.6f,%.6f".format(java.util.Locale.ROOT, box.west, box.south, box.east, box.north))
            .build()
        val request = Request.Builder().url(url).header("User-Agent", Http.USER_AGENT).build()
        val body = client.fetchBody(request, "NDW")
        return try {
            parser.decodeFromString<NdwResponse>(body).features
        } catch (e: IllegalArgumentException) {
            throw ProviderException("NDW: unerwartete Antwort", e)
        }
    }

    private fun BoundingBox.clippedToCoverage(): BoundingBox? {
        val s = maxOf(south, COVERAGE.south)
        val n = minOf(north, COVERAGE.north)
        val w = maxOf(west, COVERAGE.west)
        val e = minOf(east, COVERAGE.east)
        return if (s < n && w < e) BoundingBox(s, w, n, e) else null
    }

    private fun BoundingBox.squareDegreeCells(): List<BoundingBox> {
        val rows = ceil(north - south).toInt().coerceAtLeast(1)
        val columns = ceil(east - west).toInt().coerceAtLeast(1)
        val height = (north - south) / rows
        val width = (east - west) / columns
        return (0 until rows).flatMap { r ->
            (0 until columns).map { c ->
                BoundingBox(south + r * height, west + c * width, south + (r + 1) * height, west + (c + 1) * width)
            }
        }
    }

    private fun BoundingBox.quarters(): List<BoundingBox> {
        val lat = (south + north) / 2
        val lon = (west + east) / 2
        return listOf(
            BoundingBox(south, west, lat, lon), BoundingBox(south, lon, lat, east),
            BoundingBox(lat, west, north, lon), BoundingBox(lat, lon, north, east),
        )
    }

    internal companion object {
        const val PAGE_LIMIT = 1000
        const val MAX_REQUESTS = 80
        /** Availability ages fast, but a dense area takes some 30 requests while driving past it. */
        const val LIVE_MAX_AGE_MS = 5L * 60 * 1000
        val COVERAGE = BoundingBox(50.7, 3.3, 53.6, 7.25)
        val parser = Json { ignoreUnknownKeys = true; explicitNulls = false; coerceInputValues = true }
    }
}

@Serializable internal data class NdwResponse(val features: List<NdwFeature> = emptyList())
@Serializable internal data class NdwGeometry(val coordinates: List<Double> = emptyList())

@Serializable
internal data class NdwFeature(
    val id: String? = null,
    val geometry: NdwGeometry? = null,
    val properties: NdwProperties? = null,
) {
    fun toStation(): Station? {
        val p = properties ?: return null
        val c = geometry?.coordinates.orEmpty()
        if (id == null || c.size < 2) return null
        val location = GeoPoint.orNull(c[1], c[0]) ?: return null
        return Station(
            id = "ndw:$id",
            name = null,
            operator = p.operator_name?.trim()?.ifBlank { null },
            address = Address(street = p.address?.trim()?.ifBlank { null }, countryCode = p.country?.take(2)),
            location = location,
            devices = p.availabilities.flatMap { it.toDevices() },
            lastUpdated = p.last_updated,
            source = DataSource("NDW DOT-NL", "NDW DOT-NL", "CC0 1.0"),
        )
    }
}

@Serializable
internal data class NdwProperties(
    val address: String? = null,
    val operator_name: String? = null,
    val country: String? = null,
    val last_updated: String? = null,
    val availabilities: List<NdwAvailability> = emptyList(),
)

@Serializable
internal data class NdwAvailability(
    val available: Int? = null,
    val total: Int? = null,
    val connector_type: String? = null,
    val connector_format: String? = null,
    val power_max: Double? = null,
    val power_type: String? = null,
) {
    /** The source only counts free ones, so the rest stay "not free", not "occupied". */
    fun toDevices(): List<ChargingDevice> {
        val type = when (connector_type) {
            "IEC_62196_T2" -> if (connector_format == "CABLE") ConnectorType.TYPE2_TETHERED else ConnectorType.TYPE2
            "IEC_62196_T2_COMBO" -> ConnectorType.CCS2
            "IEC_62196_T1_COMBO" -> ConnectorType.CCS1
            "IEC_62196_T1" -> ConnectorType.TYPE1
            "CHADEMO" -> ConnectorType.CHADEMO
            "DOMESTIC_F" -> ConnectorType.SCHUKO
            else -> if (connector_type?.startsWith("IEC_60309") == true) ConnectorType.CEE else ConnectorType.OTHER
        }
        val current = when (power_type) {
            "DC" -> CurrentType.DC
            "AC1", "AC3" -> CurrentType.AC
            else -> CurrentType.UNKNOWN
        }
        val count = (total ?: return emptyList()).coerceIn(0, 100)
        return List(count) { i ->
            val state = when {
                available == null -> Availability.UNKNOWN
                i < available -> Availability.AVAILABLE
                else -> Availability.NOT_FREE
            }
            ChargingDevice(
                connectors = listOf(Connector(type, current, power_max?.takeIf { it > 0 }?.div(1000), state)),
                status = OperationalStatus.UNKNOWN,
            )
        }
    }
}
