package app.ailectric.core

import app.ailectric.core.geo.GeoPoint
import app.ailectric.core.model.Availability
import app.ailectric.core.model.ConnectorType
import app.ailectric.core.provider.ProviderException
import app.ailectric.core.provider.StationQuery
import app.ailectric.core.provider.SwissProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class SwissTest {
    private val server = MockWebServer()
    private var clock = 0L
    private var dataCalls = 0
    private var statusBody = resource("/bfe_status_real.json")
    private val ch = GeoPoint(46.8, 8.2)

    private fun resource(name: String) = javaClass.getResource(name)!!.readText()
    private fun provider() = SwissProvider(
        dataUrl = server.url("/data").toString(), statusUrl = server.url("/status").toString(), now = { clock },
    )

    @Before fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.url.encodedPath == "/status") return MockResponse.Builder().body(statusBody).build()
                dataCalls++
                return MockResponse.Builder().body(resource("/bfe_data_real.json")).build()
            }
        }
        server.start()
    }

    @After fun stop() = server.close()

    @Test fun `real records group into sites with a live count per charge point`() = runTest {
        val stations = provider().search(StationQuery(ch, 400_000.0)).stations
        assertThat(stations).hasSize(2)
        val mixed = stations.first { (it.devices.size) > 1 }
        assertThat(mixed.freePoints).isAtLeast(1)
        assertThat(mixed.freePoints).isLessThan(mixed.devices.size)
        assertThat(mixed.availability).isEqualTo(Availability.AVAILABLE)
        assertThat(mixed.address.countryCode).isEqualTo("CH")
        assertThat(mixed.source.attribution).isEqualTo("Bundesamt für Energie BFE")
        assertThat(mixed.operator).isNotNull()
        assertThat(mixed.maxPowerKw).isNotNull()
    }

    @Test fun `a name as single object and a numeric postcode still parse`() = runTest {
        val single = """{"EVSEData":[{"OperatorName":"X","EVSEDataRecord":[{"EvseID":"CH*X*1",
            "ChargingStationNames":{"lang":"de","value":"Einzeln"},"Address":{"PostalCode":3000},"GeoCoordinates":{"Google":"46.8 8.2"},
            "Plugs":["Type 2 Outlet"]}]}]}"""
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) =
                MockResponse.Builder().body(if (request.url.encodedPath == "/status") statusBody else single).build()
        }
        val s = provider().search(StationQuery(ch, 10_000.0)).stations.single()
        assertThat(s.name).isEqualTo("Einzeln")
        assertThat(s.address.postcode).isEqualTo("3000")
        assertThat(s.freePoints).isNull()
    }

    @Test fun `a charge point with several plugs keeps them on one device`() = runTest {
        val stations = provider().search(StationQuery(ch, 400_000.0)).stations
        assertThat(stations.flatMap { it.devices }.any { it.connectors.size > 1 }).isTrue()
    }

    @Test fun `a search elsewhere sends nothing`() = runTest {
        provider().search(StationQuery(GeoPoint(52.5, 13.4), 75_000.0))
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test fun `the country file is loaded once a day, the status every time`() = runTest {
        val p = provider()
        p.search(StationQuery(ch, 400_000.0))
        p.search(StationQuery(ch, 400_000.0))
        assertThat(dataCalls).isEqualTo(1)
        assertThat(server.requestCount).isEqualTo(3)
        clock = SwissProvider.STATIC_MAX_AGE_MS + 1
        p.search(StationQuery(ch, 400_000.0))
        assertThat(dataCalls).isEqualTo(2)
    }

    @Test fun `unknown states stay unknown`() {
        assertThat(SwissProvider.availability("Unknown")).isEqualTo(Availability.UNKNOWN)
        assertThat(SwissProvider.availability("EvseNotFound")).isEqualTo(Availability.UNKNOWN)
        assertThat(SwissProvider.availability(null)).isEqualTo(Availability.UNKNOWN)
        assertThat(SwissProvider.availability("Reserved")).isEqualTo(Availability.OCCUPIED)
    }

    @Test fun `plug names map to connector types`() {
        assertThat(SwissProvider.connectorType("CCS Combo 2 Plug (Cable Attached)")).isEqualTo(ConnectorType.CCS2)
        assertThat(SwissProvider.connectorType("Type 2 Outlet")).isEqualTo(ConnectorType.TYPE2)
        assertThat(SwissProvider.connectorType("Type 2 Connector (Cable Attached)")).isEqualTo(ConnectorType.TYPE2_TETHERED)
        assertThat(SwissProvider.connectorType("Type J Swiss Standard")).isEqualTo(ConnectorType.OTHER)
    }

    @Test fun `a broken file is reported as a source failure`() = runTest {
        statusBody = "<html>"
        try {
            provider().search(StationQuery(ch, 400_000.0))
            fail("expected failure")
        } catch (e: ProviderException) {
            assertThat(e.message).contains("unerwartete Antwort")
        }
    }
}
