package app.ailectric.core.provider

import app.ailectric.core.geo.GeoPoint
import app.ailectric.core.model.Address
import app.ailectric.core.model.ChargingDevice
import app.ailectric.core.model.Connector
import app.ailectric.core.model.ConnectorType
import app.ailectric.core.model.CurrentType
import app.ailectric.core.model.DataSource
import app.ailectric.core.model.OperationalStatus
import app.ailectric.core.model.Station
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/** https://openchargemap.org: static registry, no live occupancy. Requires a free API key. */
class OpenChargeMapProvider(
    private val apiKey: () -> String?,
    private val client: OkHttpClient = Http.client(),
    private val baseUrl: String = "https://api.openchargemap.io/v3/",
) : StationProvider {
    override val id = "ocm"

    override suspend fun search(query: StationQuery): StationResult {
        val key = apiKey()?.takeIf { it.isNotBlank() }
            ?: throw ProviderException("Open Charge Map: kein API-Schlüssel hinterlegt", retryable = false)
        val url = baseUrl.toHttpUrl().newBuilder().addPathSegment("poi")
            .addQueryParameter("output", "json")
            .addQueryParameter("latitude", query.center.lat.toString())
            .addQueryParameter("longitude", query.center.lon.toString())
            .addQueryParameter("distance", (query.radiusMeters / 1000).toString())
            .addQueryParameter("distanceunit", "km")
            .addQueryParameter("maxresults", query.maxResults.toString())
            .addQueryParameter("verbose", "false")
            .build()
        val request = Request.Builder().url(url)
            .header("X-API-Key", key)
            .header("User-Agent", Http.USER_AGENT)
            .build()
        val body = client.fetchBody(request, "Open Charge Map")
        val pois = try {
            parser.decodeFromString<List<OcmPoi>>(body)
        } catch (e: IllegalArgumentException) {
            throw ProviderException("Open Charge Map: unerwartete Antwort", e)
        }
        return StationResult(pois.mapNotNull { it.toStation() }, truncated = pois.size >= query.maxResults)
    }

    internal companion object {
        val parser = Json { ignoreUnknownKeys = true; explicitNulls = false; coerceInputValues = true }

        /** OCM reference ids; https://api.openchargemap.io/v3/referencedata */
        fun connectorType(id: Int?, title: String?): ConnectorType = when (id) {
            33 -> ConnectorType.CCS2
            32 -> ConnectorType.CCS1
            2 -> ConnectorType.CHADEMO
            25 -> ConnectorType.TYPE2
            1036 -> ConnectorType.TYPE2_TETHERED
            1 -> ConnectorType.TYPE1
            27, 30, 8 -> ConnectorType.TESLA_SUPERCHARGER
            28 -> ConnectorType.SCHUKO
            else -> {
                val t = title.orEmpty().lowercase()
                when {
                    "ccs" in t && "type 1" in t -> ConnectorType.CCS1
                    "ccs" in t -> ConnectorType.CCS2
                    "chademo" in t -> ConnectorType.CHADEMO
                    "type 2" in t || "mennekes" in t -> ConnectorType.TYPE2
                    "type 1" in t || "j1772" in t -> ConnectorType.TYPE1
                    "schuko" in t || "cee 7" in t -> ConnectorType.SCHUKO
                    "tesla" in t -> ConnectorType.TESLA_SUPERCHARGER
                    "cee" in t -> ConnectorType.CEE
                    else -> ConnectorType.OTHER
                }
            }
        }

        fun currentType(id: Int?, title: String?): CurrentType = when {
            id == 30 || title?.startsWith("DC") == true -> CurrentType.DC
            id == 10 || id == 20 || title?.startsWith("AC") == true -> CurrentType.AC
            else -> CurrentType.UNKNOWN
        }
    }
}

@Serializable
internal data class OcmPoi(
    val ID: Long,
    val AddressInfo: OcmAddress? = null,
    val Connections: List<OcmConnection>? = null,
    val OperatorInfo: OcmTitled? = null,
    val StatusType: OcmStatus? = null,
    val DataProvider: OcmDataProvider? = null,
    val UsageCost: String? = null,
    val UsageType: OcmTitled? = null,
    val NumberOfPoints: Int? = null,
    val DateLastStatusUpdate: String? = null,
    val DateLastVerified: String? = null,
) {
    fun toStation(): Station? {
        val a = AddressInfo ?: return null
        val location = GeoPoint.orNull(a.Latitude, a.Longitude) ?: return null
        val connections = Connections.orEmpty()
        return Station(
            id = "ocm:$ID",
            name = a.Title?.trim()?.ifBlank { null },
            // OCM uses placeholder operators for "not known"; that is no operator name.
            operator = OperatorInfo?.Title?.takeUnless { it.startsWith("(") || it.startsWith("Unknown") },
            address = Address(
                street = a.AddressLine1?.trim()?.ifBlank { null },
                postcode = a.Postcode?.trim()?.ifBlank { null },
                town = a.Town?.trim()?.ifBlank { null },
                countryCode = a.Country?.ISOCode,
            ),
            location = location,
            devices = connections.flatMap { it.toDevices() },
            declaredPoints = NumberOfPoints?.takeIf { it > 0 },
            openingHours = a.AccessComments?.trim()?.ifBlank { null },
            pricing = UsageCost?.trim()?.ifBlank { null },
            access = UsageType?.Title?.takeUnless { it.startsWith("(") },
            status = StatusType.toOperationalStatus(),
            lastUpdated = DateLastStatusUpdate ?: DateLastVerified,
            source = DataSource(
                provider = "Open Charge Map",
                attribution = listOfNotNull("Open Charge Map", DataProvider?.Title?.takeUnless { it == "Open Charge Map Contributors" })
                    .joinToString(" / "),
                license = DataProvider?.License,
            ),
        )
    }
}

private fun OcmStatus?.toOperationalStatus() = when {
    this == null -> OperationalStatus.UNKNOWN
    ID == 50 -> OperationalStatus.OPERATIONAL
    ID == 150 -> OperationalStatus.PLANNED
    IsOperational == true -> OperationalStatus.OPERATIONAL
    IsOperational == false -> OperationalStatus.NOT_OPERATIONAL
    else -> OperationalStatus.UNKNOWN
}

@Serializable
internal data class OcmConnection(
    val ConnectionTypeID: Int? = null,
    val ConnectionType: OcmTitled? = null,
    val CurrentTypeID: Int? = null,
    val CurrentType: OcmTitled? = null,
    val StatusType: OcmStatus? = null,
    val PowerKW: Double? = null,
    val Quantity: Int? = null,
    val Reference: String? = null,
) {
    /** A connection line with Quantity n stands for n equal devices. */
    fun toDevices(): List<ChargingDevice> {
        val connector = Connector(
            type = OpenChargeMapProvider.connectorType(ConnectionTypeID ?: ConnectionType?.ID, ConnectionType?.Title),
            currentType = OpenChargeMapProvider.currentType(CurrentTypeID ?: CurrentType?.ID, CurrentType?.Title),
            powerKw = PowerKW?.takeIf { it > 0 },
        )
        return List((Quantity ?: 1).coerceIn(1, 100)) {
            ChargingDevice(Reference, listOf(connector), StatusType.toOperationalStatus())
        }
    }
}

@Serializable internal data class OcmTitled(val ID: Int? = null, val Title: String? = null)
@Serializable internal data class OcmStatus(val ID: Int? = null, val IsOperational: Boolean? = null)
@Serializable internal data class OcmDataProvider(val Title: String? = null, val License: String? = null)
@Serializable internal data class OcmCountry(val ISOCode: String? = null)
@Serializable
internal data class OcmAddress(
    val Title: String? = null,
    val AddressLine1: String? = null,
    val Town: String? = null,
    val Postcode: String? = null,
    val Country: OcmCountry? = null,
    val Latitude: Double? = null,
    val Longitude: Double? = null,
    val AccessComments: String? = null,
)
