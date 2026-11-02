package app.ailectric.core

import app.ailectric.core.geo.GeoPoint
import app.ailectric.core.model.Availability
import app.ailectric.core.model.ConnectorType
import app.ailectric.core.model.CurrentType
import app.ailectric.core.provider.NdwProvider
import app.ailectric.core.provider.ProviderException
import app.ailectric.core.provider.StationProvider
import app.ailectric.core.provider.StationQuery
import app.ailectric.core.provider.StationResult
import app.ailectric.core.search.Freshness
import app.ailectric.core.search.StationRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.Test

class NdwTest {
    private val vaals = GeoPoint(50.7725, 6.0107)
    private val server = MockWebServer()
    private val real = javaClass.getResource("/ndw_vaals_real.json")!!.readText()

    private fun answer(body: (RecordedRequest) -> String) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse.Builder().body(body(request)).build()
        }
    }
    private fun provider() = NdwProvider(baseUrl = server.url("/features").toString(), pauseMillis = 0, retryMillis = 0)
    private fun bbox(r: RecordedRequest) = r.url.queryParameter("bbox")!!.split(',').map { it.toDouble() }
    private fun feature(id: Int) = """{"id":"$id","geometry":{"coordinates":[6.0,51.0]},"properties":{}}"""

    @Before fun start() = server.start()
    @After fun stop() = server.close()

    @Test fun `a real answer becomes stations with live availability`() = runTest {
        answer { real }
        val s = provider().search(StationQuery(vaals, 2_000.0)).stations.first()
        assertThat(s.address.street).isEqualTo("Laatbankstraat 18")
        assertThat(s.address.countryCode).isEqualTo("NL")
        assertThat(s.operator).isEqualTo("Vattenfall InCharge")
        assertThat(s.location.lat).isWithin(1e-6).of(50.772335)
        assertThat(s.location.lon).isWithin(1e-6).of(6.010798)
        assertThat(s.pointCount).isEqualTo(2)
        assertThat(s.maxPowerKw).isWithin(0.01).of(11.04)
        assertThat(s.connectors.first().type).isEqualTo(ConnectorType.TYPE2)
        assertThat(s.connectors.first().currentType).isEqualTo(CurrentType.AC)
        assertThat(s.availability).isEqualTo(Availability.AVAILABLE)
        assertThat(s.freePoints).isEqualTo(2)
        assertThat(s.pricing).isNull()
    }

    @Test fun `what is not free stays not free and is not called occupied`() = runTest {
        answer {
            """{"features":[{"id":"1","geometry":{"coordinates":[6.0,51.0]},"properties":{"availabilities":
            [{"available":0,"total":2,"connector_type":"IEC_62196_T2_COMBO","power_max":150000,"power_type":"DC"}]}}]}"""
        }
        val s = provider().search(StationQuery(vaals, 2_000.0)).stations.single()
        assertThat(s.availability).isEqualTo(Availability.NOT_FREE)
        assertThat(s.freePoints).isEqualTo(0)
        assertThat(s.connectors.first().type).isEqualTo(ConnectorType.CCS2)
    }

    @Test fun `a search outside the Netherlands sends nothing`() = runTest {
        val r = provider().search(StationQuery(GeoPoint(48.1, 11.6), 75_000.0))
        assertThat(r.stations).isEmpty()
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test fun `every request stays within one square degree`() = runTest {
        answer { real }
        provider().search(StationQuery(GeoPoint(52.0, 5.0), 75_000.0))
        assertThat(server.requestCount).isGreaterThan(1)
        repeat(server.requestCount) {
            val (w, s, e, n) = bbox(server.takeRequest())
            assertThat((e - w) * (n - s)).isAtMost(1.0)
        }
    }

    @Test fun `a full page is split so nothing is cut off`() = runTest {
        answer { request ->
            val (w, _, e, _) = bbox(request)
            val width = e - w
            val ids = if (width > 0.1) (1..1000) else (1..3).map { (w * 1e6).toInt() + it }
            """{"features":[${ids.joinToString(",") { feature(it) }}]}"""
        }
        val r = provider().search(StationQuery(vaals, 5_000.0))
        assertThat(r.truncated).isFalse()
        assertThat(server.requestCount).isGreaterThan(1)
        assertThat(r.stations.size).isGreaterThan(1000)
    }

    @Test fun `a rejected request is tried once more`() = runTest {
        var calls = 0
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                calls++
                return if (calls == 1) MockResponse.Builder().code(429).build() else MockResponse.Builder().body(real).build()
            }
        }
        val r = provider().search(StationQuery(vaals, 2_000.0))
        assertThat(r.stations).isNotEmpty()
        assertThat(r.truncated).isFalse()
        assertThat(calls).isEqualTo(2)
    }

    @Test fun `a stuck dense area stops at the limit and says so`() = runTest {
        answer { """{"features":[${(1..1000).joinToString(",") { feature(it) }}]}""" }
        val r = provider().search(StationQuery(vaals, 5_000.0))
        assertThat(r.truncated).isTrue()
        assertThat(server.requestCount).isEqualTo(NdwProvider.MAX_REQUESTS)
    }

    @Test fun `live status is refetched soon and never shown from an old copy`() = runTest {
        var clock = 0L
        var online = true
        val free = station(CCS_FREE).copy(id = "ndw:1", location = GeoPoint(50.8, 6.0))
        val live = object : StationProvider {
            override val id = "ndw"
            override val maxAgeMillis = 1000L
            var calls = 0
            override suspend fun search(query: StationQuery): StationResult {
                calls++
                if (!online) throw ProviderException("offline")
                return StationResult(listOf(free), false)
            }
        }
        val repo = StationRepository(listOf(live), FakeCache(), now = { clock })
        repo.load(vaals, 50_000.0)
        repo.load(vaals, 50_000.0)
        assertThat(live.calls).isEqualTo(1)
        clock = 2000
        online = false
        val stale = repo.load(vaals, 50_000.0)
        assertThat(live.calls).isEqualTo(2)
        assertThat(stale.freshness).isEqualTo(Freshness.STALE_OFFLINE)
        assertThat(stale.stations.single().availability).isEqualTo(Availability.UNKNOWN)
        assertThat(stale.stations.single().freePoints).isNull()
    }
}
