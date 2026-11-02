package app.ailectric.core.provider

import app.ailectric.core.geo.BoundingBox
import app.ailectric.core.geo.Geo
import app.ailectric.core.geo.GeoPoint
import app.ailectric.core.model.Address
import app.ailectric.core.model.ChargingDevice
import app.ailectric.core.model.Connector
import app.ailectric.core.model.ConnectorType
import app.ailectric.core.model.CurrentType
import app.ailectric.core.model.DataSource
import app.ailectric.core.model.HikingDetails
import app.ailectric.core.model.OperationalStatus
import app.ailectric.core.model.Poi
import app.ailectric.core.model.PoiCatalog
import app.ailectric.core.model.PoiCategory
import app.ailectric.core.model.Station
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlin.math.roundToInt

private val OSM_SOURCE = DataSource("OpenStreetMap", "© OpenStreetMap-Mitwirkende", "ODbL 1.0")

@Serializable internal data class OverpassResponse(val elements: List<OverpassElement> = emptyList())
@Serializable internal data class OverpassPoint(val lat: Double? = null, val lon: Double? = null)
@Serializable
internal data class OverpassMember(val type: String? = null, val geometry: List<OverpassPoint?>? = null)
@Serializable
internal data class OverpassElement(
    val type: String,
    val id: Long,
    val lat: Double? = null,
    val lon: Double? = null,
    val center: OverpassPoint? = null,
    val tags: Map<String, String> = emptyMap(),
    /** `geom(bbox)` puts null where a node lies outside the box. */
    val geometry: List<OverpassPoint?>? = null,
    val members: List<OverpassMember>? = null,
) {
    val point get() = GeoPoint.orNull(lat ?: center?.lat, lon ?: center?.lon)

    /**
     * False once `geom(bbox)` cut anything off: a way outside the box has no geometry at all and
     * one crossing it has null gaps. `bounds` can't tell, as it describes the clipped result.
     */
    val geometryComplete: Boolean
        get() {
            val parts = members?.filter { it.type == "way" }?.map { it.geometry } ?: listOf(geometry)
            return parts.isNotEmpty() && parts.all { !it.isNullOrEmpty() && null !in it }
        }
    val osmId get() = "osm:$type/$id"
}

/** Tries each endpoint in order; public instances are often overloaded (HTTP 502/504/429). */
internal class OverpassClient(
    private val client: OkHttpClient,
    private val endpoints: () -> List<String>,
) {
    suspend fun run(query: String): OverpassResponse {
        var failure: ProviderException? = null
        for (endpoint in endpoints()) {
            try {
                return runOn(endpoint, query)
            } catch (e: ProviderException) {
                failure = e
            }
        }
        throw failure ?: ProviderException("Overpass: kein Server konfiguriert", retryable = false)
    }

    private suspend fun runOn(endpoint: String, query: String): OverpassResponse {
        val request = Request.Builder().url(endpoint)
            .header("User-Agent", Http.USER_AGENT)
            .post(FormBody.Builder().add("data", query).build())
            .build()
        val body = client.fetchBody(request, "Overpass")
        return try {
            json.decodeFromString(body)
        } catch (e: IllegalArgumentException) {
            // Overpass answers overload and timeouts with an HTML page and status 200.
            throw ProviderException("Overpass: Dienst ausgelastet", e)
        }
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    }
}

/** Own instance first once configured; see docs/SERVER.md. */
val OVERPASS_PUBLIC_ENDPOINTS = listOf(
    "https://overpass-api.de/api/interpreter",
    "https://overpass.private.coffee/api/interpreter",
)

/** Charging stations from OSM. No key needed, used as fallback; power data is often missing. */
class OsmStationProvider(
    client: OkHttpClient = Http.client(),
    endpoints: () -> List<String> = { OVERPASS_PUBLIC_ENDPOINTS },
) : StationProvider {
    override val id = "osm"
    private val overpass = OverpassClient(client, endpoints)

    override suspend fun search(query: StationQuery): StationResult {
        val c = query.center
        val q = "[out:json][timeout:30];" +
            """nwr["amenity"="charging_station"](around:${query.radiusMeters.toInt()},${c.lat},${c.lon});""" +
            "out center tags ${query.maxResults};"
        val elements = overpass.run(q).elements
        return StationResult(elements.mapNotNull(::osmStation), truncated = elements.size >= query.maxResults)
    }
}

private data class SocketSpec(val key: String, val type: ConnectorType, val current: CurrentType)

private val SOCKETS = listOf(
    SocketSpec("type2_combo", ConnectorType.CCS2, CurrentType.DC),
    SocketSpec("chademo", ConnectorType.CHADEMO, CurrentType.DC),
    SocketSpec("type2_cable", ConnectorType.TYPE2_TETHERED, CurrentType.AC),
    SocketSpec("type2", ConnectorType.TYPE2, CurrentType.AC),
    SocketSpec("type1_combo", ConnectorType.CCS1, CurrentType.DC),
    SocketSpec("type1", ConnectorType.TYPE1, CurrentType.AC),
    SocketSpec("tesla_supercharger", ConnectorType.TESLA_SUPERCHARGER, CurrentType.DC),
    SocketSpec("tesla_supercharger_ccs", ConnectorType.CCS2, CurrentType.DC),
    SocketSpec("schuko", ConnectorType.SCHUKO, CurrentType.AC),
    SocketSpec("cee_blue", ConnectorType.CEE, CurrentType.AC),
    SocketSpec("cee_red_16a", ConnectorType.CEE, CurrentType.AC),
    SocketSpec("cee_red_32a", ConnectorType.CEE, CurrentType.AC),
)

/** Parses "22 kW", "150", "11000 W" and the first value of "50 kW;22 kW". Plain numbers are kW. */
internal fun parseKw(raw: String?): Double? {
    val first = raw?.substringBefore(';')?.trim()?.lowercase() ?: return null
    val number = Regex("""^(\d+(?:[.,]\d+)?)\s*(kw|kva|w)?$""").find(first) ?: return null
    val value = number.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
    return when (number.groupValues[2]) {
        "w" -> value / 1000
        else -> value
    }.takeIf { it > 0 }
}

internal fun osmStation(e: OverpassElement): Station? {
    val point = e.point ?: return null
    val t = e.tags
    val devices = SOCKETS.flatMap { spec ->
        val raw = t["socket:${spec.key}"] ?: return@flatMap emptyList()
        // "yes" says the plug exists without a count, so it's one known connector.
        val count = if (raw == "yes") 1 else raw.toIntOrNull()?.takeIf { it > 0 } ?: return@flatMap emptyList()
        val kw = parseKw(t["socket:${spec.key}:output"])
        List(count.coerceAtMost(100)) {
            ChargingDevice(connectors = listOf(Connector(spec.type, spec.current, kw)))
        }
    }
    return Station(
        id = e.osmId,
        name = t["name"],
        operator = t["operator"] ?: t["brand"] ?: t["network"],
        address = Address(
            street = listOfNotNull(t["addr:street"], t["addr:housenumber"]).joinToString(" ").ifBlank { null },
            postcode = t["addr:postcode"],
            town = t["addr:city"],
            countryCode = t["addr:country"],
        ),
        location = point,
        devices = devices,
        declaredPoints = t["capacity"]?.toIntOrNull()?.takeIf { it > 0 },
        openingHours = t["opening_hours"],
        pricing = t["charge"] ?: t["fee"]?.let { if (it == "no") "kostenlos" else null },
        access = t["access"],
        status = if ("disused:amenity" in t) OperationalStatus.NOT_OPERATIONAL else OperationalStatus.UNKNOWN,
        lastUpdated = t["check_date"],
        source = OSM_SOURCE,
    )
}

/** Generic POI lookup; hiking is just the categories it is called with. */
class OverpassPoiProvider(
    client: OkHttpClient = Http.client(),
    endpoints: () -> List<String> = { OVERPASS_PUBLIC_ENDPOINTS },
) : PoiProvider {
    override val id = "osm-poi"
    private val overpass = OverpassClient(client, endpoints)

    override suspend fun near(points: List<GeoPoint>, radiusMeters: Int, categories: List<PoiCategory>): List<Poi> {
        if (points.isEmpty() || categories.isEmpty()) return emptyList()
        val query = buildOverpassPoiQuery(points, radiusMeters, categories)
        return overpass.run(query).elements.mapNotNull { osmPoi(it, categories) }
    }
}

/** Search area. Long trails are clipped to it, or one E-path alone is megabytes of geometry. */
private fun queryBox(points: List<GeoPoint>, radiusMeters: Int): BoundingBox =
    points.map { BoundingBox.around(it, radiusMeters + 500.0) }.reduce(BoundingBox::union)

/**
 * One box around all points, with distances checked on the device: a separate `around` per point
 * and category gets rejected by busy servers. Limits are per group because Overpass prints nodes
 * first, so a shared one would cut off the routes.
 */
internal fun buildOverpassPoiQuery(points: List<GeoPoint>, radiusMeters: Int, categories: List<PoiCategory>): String {
    val b = queryBox(points, radiusMeters)
    val box = "${b.south},${b.west},${b.north},${b.east}"
    val selectors = categories.flatMap { cat ->
        val brand = cat.brands.takeIf { it.isNotEmpty() }
            ?.joinToString("|", prefix = """["brand"~"^(""", postfix = """)$"]""") { Regex.escape(it) }.orEmpty()
        cat.osmSelectors.map { it + brand }
    }
    val (routes, others) = selectors.partition { it.startsWith("rel") }
    fun block(group: List<String>, limit: Int) =
        if (group.isEmpty()) "" else "(${group.joinToString("") { "$it($box);" }});out body geom($box) $limit;"
    return "[out:json][timeout:60];" + block(routes, 300) + block(others, 1200)
}

private fun PoiCategory.matches(t: Map<String, String>): Boolean = osmSelectors.any { sel ->
    Regex(""""([^"]+)"="([^"]+)"""").findAll(sel).all { t[it.groupValues[1]] == it.groupValues[2] } &&
        Regex("""\["([^"=]+)"]""").findAll(sel).all { it.groupValues[1] in t }
} && (brands.isEmpty() || t["brand"] in brands)

/** Splits at the gaps of a clipped way, so no straight line is drawn across them. */
private fun List<OverpassPoint?>.toLines(): List<List<GeoPoint>> {
    val lines = mutableListOf<List<GeoPoint>>()
    var current = mutableListOf<GeoPoint>()
    for (point in this) {
        val geo = point?.let { GeoPoint.orNull(it.lat, it.lon) }
        if (geo != null) {
            current += geo
        } else {
            if (current.size >= 2) lines += current
            current = mutableListOf()
        }
    }
    if (current.size >= 2) lines += current
    return lines
}

internal fun osmPoi(e: OverpassElement, categories: List<PoiCategory>): Poi? {
    val category = categories.firstOrNull { it.matches(e.tags) } ?: return null
    val lines = buildList {
        e.geometry?.let { addAll(it.toLines()) }
        e.members.orEmpty().filter { it.type == "way" }.forEach { m -> m.geometry?.let { addAll(it.toLines()) } }
    }
    val location = e.point ?: lines.firstOrNull()?.firstOrNull() ?: return null
    val trail = category.id == PoiCatalog.HIKING_ROUTE.id || category.id == PoiCatalog.HIKING_PATH.id
    return Poi(
        id = e.osmId,
        categoryId = category.id,
        name = e.tags["name"] ?: e.tags["ref"],
        location = location,
        geometry = lines,
        brand = e.tags["brand"],
        hiking = if (trail) hikingDetails(e, lines) else null,
        source = OSM_SOURCE,
    )
}

internal fun parseOsmLengthMeters(raw: String?): Double? {
    val m = Regex("""^(\d+(?:[.,]\d+)?)\s*(km|m|mi)?$""").find(raw?.trim()?.lowercase() ?: return null) ?: return null
    val v = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
    return when (m.groupValues[2]) {
        "m" -> v
        "mi" -> v * 1609.344
        else -> v * 1000 // OSM's `distance` defaults to kilometres
    }.takeIf { it > 0 }
}

/** Parses OSM's `duration`: "HH:MM", "HH:MM:SS" or plain minutes. */
internal fun parseOsmDurationMinutes(raw: String?): Int? {
    val parts = raw?.trim()?.split(':')?.map { it.toIntOrNull() ?: return null } ?: return null
    return when (parts.size) {
        1 -> parts[0]
        2, 3 -> parts[0] * 60 + parts[1]
        else -> null
    }?.takeIf { it > 0 }
}

/** DIN 33466: horizontal at 4 km/h and ascent at 300 m/h, larger + half the smaller. */
internal fun din33466Minutes(lengthMeters: Double, ascentMeters: Double?): Int {
    val horizontal = lengthMeters / 4000 * 60
    val vertical = (ascentMeters ?: 0.0) / 300 * 60
    return (maxOf(horizontal, vertical) + minOf(horizontal, vertical) / 2).roundToInt()
}

private fun hikingDetails(e: OverpassElement, lines: List<List<GeoPoint>>): HikingDetails {
    val t = e.tags
    val complete = e.geometryComplete
    val tagged = parseOsmLengthMeters(t["distance"])
    val measured = if (complete && lines.isNotEmpty()) lines.sumOf(Geo::lengthMeters) else null
    val length = tagged ?: measured
    val ascent = t["ascent"]?.removeSuffix("m")?.trim()?.toDoubleOrNull()
    val closed = if (complete && lines.isNotEmpty()) {
        Geo.distanceMeters(lines.first().first(), lines.last().last()) < 100
    } else null
    return HikingDetails(
        lengthMeters = length,
        lengthDerived = tagged == null && measured != null,
        ascentMeters = ascent,
        estimatedMinutes = parseOsmDurationMinutes(t["duration"]) ?: length?.let { din33466Minutes(it, ascent) },
        surface = t["surface"],
        difficulty = t["sac_scale"] ?: t["difficulty"],
        roundTrip = when (t["roundtrip"]) {
            "yes" -> true
            "no" -> false
            else -> closed
        },
        signposted = e.type == "relation" || "osmc:symbol" in t || "trailblazed" in t,
        network = t["network"],
    )
}
