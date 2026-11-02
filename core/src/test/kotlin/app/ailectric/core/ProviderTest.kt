package app.ailectric.core

import app.ailectric.core.geo.BoundingBox
import app.ailectric.core.geo.GeoPoint
import app.ailectric.core.model.Availability
import app.ailectric.core.model.ConnectorType
import app.ailectric.core.model.CurrentType
import app.ailectric.core.model.OperationalStatus
import app.ailectric.core.model.PoiCatalog
import app.ailectric.core.provider.OpenChargeMapProvider
import app.ailectric.core.provider.OsmStationProvider
import app.ailectric.core.provider.OverpassElement
import app.ailectric.core.provider.OverpassMember
import app.ailectric.core.provider.OverpassBounds
import app.ailectric.core.provider.OverpassPoint
import app.ailectric.core.provider.ProviderException
import app.ailectric.core.provider.StationQuery
import app.ailectric.core.provider.buildOverpassPoiQuery
import app.ailectric.core.provider.din33466Minutes
import app.ailectric.core.provider.osmPoi
import app.ailectric.core.provider.osmStation
import app.ailectric.core.provider.parseKw
import app.ailectric.core.provider.parseOsmDurationMinutes
import app.ailectric.core.provider.parseOsmLengthMeters
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

class ProviderTest {
    private lateinit var server: MockWebServer
    private val query = StationQuery(GeoPoint(48.0, 11.0), 75_000.0, maxResults = 5)
    private val sample = javaClass.getResource("/ocm_sample.json")!!.readText()

    @Before fun start() { server = MockWebServer().also { it.start() } }
    @After fun stop() { server.close() }

    private fun ocm(key: String? = "k") =
        OpenChargeMapProvider({ key }, baseUrl = server.url("/v3/").toString())

    private fun respond(body: String, code: Int = 200) =
        server.enqueue(MockResponse.Builder().code(code).body(body).build())

    @Test fun `ocm request sends the key in the header, never in the URL`() = runTest {
        respond("[]")
        ocm("secret").search(query)
        val r = server.takeRequest()
        assertThat(r.headers["X-API-Key"]).isEqualTo("secret")
        assertThat(r.url.toString()).doesNotContain("secret")
        assertThat(r.url.queryParameter("distance")).isEqualTo("75.0")
        assertThat(r.url.queryParameter("distanceunit")).isEqualTo("km")
    }

    @Test fun `ocm maps site, devices and connectors`() = runTest {
        respond(sample)
        val s = ocm().search(query).stations.first { it.id == "ocm:100" }
        assertThat(s.operator).isEqualTo("IONITY")
        assertThat(s.devices).hasSize(8)
        assertThat(s.maxPowerKw).isEqualTo(350.0)
        assertThat(s.connectorTypes).containsExactly(ConnectorType.CCS2, ConnectorType.TYPE2)
        assertThat(s.connectors.count { it.currentType == CurrentType.DC }).isEqualTo(6)
        assertThat(s.status).isEqualTo(OperationalStatus.OPERATIONAL)
        assertThat(s.source.attribution).contains("Bundesnetzagentur")
        assertThat(s.pricing).isEqualTo("0,59 EUR/kWh")
    }

    @Test fun `ocm status operational must not become live availability`() = runTest {
        respond(sample)
        val s = ocm().search(query).stations.first { it.id == "ocm:100" }
        assertThat(s.availability).isEqualTo(Availability.UNKNOWN)
    }

    @Test fun `ocm tolerates missing data and drops unusable coordinates`() = runTest {
        respond(sample)
        val r = ocm().search(query).stations
        assertThat(r.map { it.id }).containsExactly("ocm:100", "ocm:101", "ocm:104")
        val sparse = r.first { it.id == "ocm:101" }
        assertThat(sparse.name).isNull()
        assertThat(sparse.operator).isNull()
        assertThat(sparse.status).isEqualTo(OperationalStatus.PLANNED)
        assertThat(sparse.connectors.single().type).isEqualTo(ConnectorType.OTHER)
        assertThat(sparse.maxPowerKw).isNull()
        val bare = r.first { it.id == "ocm:104" }
        assertThat(bare.pointCount).isNull()
        assertThat(bare.pricing).isNull()
    }

    @Test fun `hitting the result cap is reported as truncated`() = runTest {
        respond(sample)
        assertThat(ocm().search(query).truncated).isTrue()
    }

    @Test fun `missing key fails without any network call`() = runTest {
        val e = runCatching { ocm(null).search(query) }.exceptionOrNull() as ProviderException
        assertThat(e.retryable).isFalse()
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test fun `invalid key is not retryable, server errors are`() = runTest {
        respond("no", 403)
        assertThat((runCatching { ocm().search(query) }.exceptionOrNull() as ProviderException).retryable).isFalse()
        respond("boom", 500)
        assertThat((runCatching { ocm().search(query) }.exceptionOrNull() as ProviderException).retryable).isTrue()
    }

    @Test fun `malformed body becomes a provider error`() = runTest {
        respond("<html>")
        assertThat(runCatching { ocm().search(query) }.exceptionOrNull()).isInstanceOf(ProviderException::class.java)
    }

    @Test fun `overpass falls through to the next endpoint when overloaded`() = runTest {
        respond("# 502 Gateway Timeout", 502)
        respond("""{"elements":[{"type":"node","id":7,"lat":48.1,"lon":11.1,"tags":{"amenity":"charging_station"}}]}""")
        val endpoints = listOf(server.url("/a").toString(), server.url("/b").toString())
        val r = OsmStationProvider(endpoints = { endpoints }).search(query)
        assertThat(r.stations.single().id).isEqualTo("osm:node/7")
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test fun `osm overload page with status 200 is an error, not an empty result`() = runTest {
        respond("<html>rate_limited</html>")
        val p = OsmStationProvider(endpoints = { listOf(server.url("/a").toString()) })
        assertThat(runCatching { p.search(query) }.exceptionOrNull()).isInstanceOf(ProviderException::class.java)
    }

    @Test fun `osm station maps sockets and keeps unknown power null`() {
        val s = osmStation(
            OverpassElement(
                "node", 1, 48.1, 11.1,
                tags = mapOf(
                    "name" to "Elisenhof", "operator" to "E.ON", "capacity" to "4", "opening_hours" to "24/7",
                    "socket:type2" to "2", "socket:type2_combo" to "2", "socket:type2_combo:output" to "150 kW",
                ),
            ),
        )!!
        assertThat(s.pointCount).isEqualTo(4)
        assertThat(s.maxPowerKw).isEqualTo(150.0)
        assertThat(s.connectors.filter { it.type == ConnectorType.TYPE2 }.map { it.powerKw }).containsExactly(null, null)
        assertThat(s.source.license).isEqualTo("ODbL 1.0")
    }

    @Test fun `power strings`() {
        assertThat(parseKw("22 kW")).isEqualTo(22.0)
        assertThat(parseKw("11000 W")).isEqualTo(11.0)
        assertThat(parseKw("3,7kW")).isEqualTo(3.7)
        assertThat(parseKw("50 kW;22 kW")).isEqualTo(50.0)
        assertThat(parseKw("schnell")).isNull()
        assertThat(parseKw(null)).isNull()
    }

    @Test fun `osm length and duration strings`() {
        assertThat(parseOsmLengthMeters("4.5")).isEqualTo(4500.0)
        assertThat(parseOsmLengthMeters("800 m")).isEqualTo(800.0)
        assertThat(parseOsmLengthMeters("12,3 km")).isEqualTo(12300.0)
        assertThat(parseOsmLengthMeters("weit")).isNull()
        assertThat(parseOsmDurationMinutes("02:30")).isEqualTo(150)
        assertThat(parseOsmDurationMinutes("90")).isEqualTo(90)
        assertThat(parseOsmDurationMinutes("x")).isNull()
    }

    @Test fun `din 33466 walking time`() {
        assertThat(din33466Minutes(4000.0, null)).isEqualTo(60)
        assertThat(din33466Minutes(8000.0, 600.0)).isEqualTo(180)
    }

    private val box = BoundingBox(47.0, 10.0, 49.0, 12.0)
    private val loop = listOf(
        OverpassPoint(48.0, 11.0), OverpassPoint(48.0, 11.02), OverpassPoint(48.01, 11.02), OverpassPoint(48.0, 11.0),
    )

    @Test fun `trail inside the query box gets measured length and loop detection`() {
        val p = osmPoi(
            OverpassElement(
                "relation", 5, bounds = OverpassBounds(48.0, 11.0, 48.01, 11.02),
                tags = mapOf("route" to "hiking", "name" to "Rundweg", "network" to "lwn"),
                members = listOf(OverpassMember("way", loop)),
            ),
            PoiCatalog.HIKING, box,
        )!!
        assertThat(p.categoryId).isEqualTo(PoiCatalog.HIKING_ROUTE.id)
        assertThat(p.hiking!!.lengthMeters).isNotNull()
        assertThat(p.hiking!!.lengthDerived).isTrue()
        assertThat(p.hiking!!.roundTrip).isTrue()
        assertThat(p.hiking!!.signposted).isTrue()
        assertThat(p.hiking!!.estimatedMinutes).isNotNull()
    }

    @Test fun `clipped trail reports neither a length nor a loop`() {
        val p = osmPoi(
            OverpassElement(
                "relation", 6, bounds = OverpassBounds(40.0, 5.0, 55.0, 20.0),
                tags = mapOf("route" to "hiking", "name" to "E5"),
                members = listOf(OverpassMember("way", loop)),
            ),
            PoiCatalog.HIKING, box,
        )!!
        assertThat(p.hiking!!.lengthMeters).isNull()
        assertThat(p.hiking!!.roundTrip).isNull()
        assertThat(p.hiking!!.estimatedMinutes).isNull()
    }

    @Test fun `viewpoint is a point poi without trail details`() {
        val p = osmPoi(
            OverpassElement("node", 9, 48.0, 11.0, tags = mapOf("tourism" to "viewpoint")), PoiCatalog.HIKING, box,
        )!!
        assertThat(p.categoryId).isEqualTo(PoiCatalog.VIEWPOINT.id)
        assertThat(p.hiking).isNull()
    }

    @Test fun `brand search adds a brand constraint, so chains need no new code`() {
        val q = buildOverpassPoiQuery(listOf(GeoPoint(48.0, 11.0)), 500, listOf(PoiCatalog.supermarket("Lidl")))
        assertThat(q).contains("""["shop"="supermarket"]["brand"~"^(""")
        assertThat(q).contains("Lidl")
        val p = osmPoi(
            OverpassElement("node", 3, 48.0, 11.0, tags = mapOf("shop" to "supermarket", "brand" to "Aldi")),
            listOf(PoiCatalog.supermarket("Lidl")), box,
        )
        assertThat(p).isNull()
    }
}
