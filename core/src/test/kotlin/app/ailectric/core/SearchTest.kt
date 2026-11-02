package app.ailectric.core

import app.ailectric.core.cache.Coverage
import app.ailectric.core.cache.SearchPolicy
import app.ailectric.core.filter.HikingFilter
import app.ailectric.core.filter.StationFilter
import app.ailectric.core.geo.BoundingBox
import app.ailectric.core.geo.GeoPoint
import app.ailectric.core.model.Poi
import app.ailectric.core.model.PoiCategory
import app.ailectric.core.model.Station
import app.ailectric.core.provider.PoiProvider
import app.ailectric.core.provider.ProviderException
import app.ailectric.core.provider.StationProvider
import app.ailectric.core.provider.StationQuery
import app.ailectric.core.provider.StationResult
import app.ailectric.core.search.ChargerSearch
import app.ailectric.core.search.Freshness
import app.ailectric.core.search.SearchCache
import app.ailectric.core.search.StationRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.Assert.fail
import org.junit.Test

class FakeCache : SearchCache {
    val coverage = mutableMapOf<String, MutableList<Pair<Coverage, Boolean>>>()
    val stations = mutableMapOf<String, MutableMap<String, Station>>()
    val pois = mutableMapOf<String, List<Poi>>()
    override suspend fun coverages(providerId: String) = coverage[providerId].orEmpty().map { it.first }
    override suspend fun stationsIn(providerId: String, box: BoundingBox) =
        stations[providerId].orEmpty().values.filter { box.contains(it.location) }
    override suspend fun store(providerId: String, coverage: Coverage, stations: List<Station>, truncated: Boolean) {
        this.coverage.getOrPut(providerId) { mutableListOf() } += coverage to truncated
        this.stations.getOrPut(providerId) { mutableMapOf() }.putAll(stations.associateBy { it.id })
    }
    override suspend fun wasTruncated(providerId: String, coverage: Coverage) =
        this.coverage[providerId].orEmpty().first { it.first == coverage }.second
    override suspend fun poisFor(stationId: String, notBeforeMillis: Long) = pois[stationId]
    override suspend fun storePois(stationId: String, pois: List<Poi>, nowMillis: Long) { this.pois[stationId] = pois }
}

class FakeStations(override val id: String, var result: () -> StationResult) : StationProvider {
    var calls = 0
    override suspend fun search(query: StationQuery): StationResult { calls++; return result() }
}

class FakePois(var result: () -> List<Poi>) : PoiProvider {
    override val id = "fake"
    var calls = 0
    override suspend fun near(points: List<GeoPoint>, radiusMeters: Int, categories: List<PoiCategory>): List<Poi> {
        calls++
        return result()
    }
}

class SearchTest {
    private val center = GeoPoint(48.0, 11.0)
    private val near = station(CCS_350).copy(id = "a", location = GeoPoint(48.1, 11.0))
    private val far = station(TYPE2_22).copy(id = "b", location = GeoPoint(48.3, 11.0))
    private val outside = station(TYPE2_22).copy(id = "c", location = GeoPoint(48.6, 11.0))
    private val cache = FakeCache()
    private val offline = { throw ProviderException("offline") }
    private var clock = 1000L

    private fun repo(vararg p: StationProvider) = StationRepository(p.toList(), cache, SearchPolicy(), { clock })
    private fun search(pois: PoiProvider, vararg p: StationProvider) = ChargerSearch(repo(*p), pois, cache, { clock })
    private fun ok(vararg s: Station) = { StationResult(s.toList(), false) }

    @Test fun `a second nearby search is served from cache without network`() = runTest {
        val p = FakeStations("p", ok(near, far))
        val r = repo(p)
        assertThat(r.load(center, 50_000.0).freshness).isEqualTo(Freshness.LIVE_FETCH)
        val second = r.load(GeoPoint(48.05, 11.0), 50_000.0)
        assertThat(second.freshness).isEqualTo(Freshness.CACHED)
        assertThat(p.calls).isEqualTo(1)
    }

    @Test fun `no internet with cache shows old data, flagged as stale`() = runTest {
        val p = FakeStations("p", ok(near))
        repo(p).load(center, 50_000.0)
        clock += SearchPolicy().maxAgeMillis + 1
        p.result = offline
        val r = repo(p).load(center, 50_000.0)
        assertThat(r.freshness).isEqualTo(Freshness.STALE_OFFLINE)
        assertThat(r.stations.map { it.id }).containsExactly("a")
        assertThat(r.notice).isEqualTo("offline")
    }

    @Test fun `no internet and no cache is an error, not an empty result`() = runTest {
        try {
            repo(FakeStations("p", offline)).load(center, 50_000.0)
            fail("expected failure")
        } catch (e: ProviderException) {
            assertThat(e.message).isEqualTo("offline")
        }
    }

    @Test fun `all sources are asked, not only the first that answers`() = runTest {
        val first = FakeStations("ocm", ok(near))
        val second = FakeStations("osm", ok(far))
        val r = repo(first, second).load(center, 50_000.0)
        assertThat(r.stations.map { it.id }).containsExactly("a", "b")
        assertThat(second.calls).isEqualTo(1)
    }

    @Test fun `a failing source leaves the others and the reason is reported`() = runTest {
        val primary = FakeStations("ocm") { throw ProviderException("kein Schlüssel", retryable = false) }
        val r = repo(primary, FakeStations("osm", ok(near))).load(center, 50_000.0)
        assertThat(r.stations.map { it.id }).containsExactly("a")
        assertThat(r.notice).isEqualTo("kein Schlüssel")
    }

    @Test fun `when every source fails the error names all causes`() = runTest {
        val noKey = FakeStations("ocm") { throw ProviderException("kein Schlüssel", retryable = false) }
        val busy = FakeStations("osm") { throw ProviderException("Overpass ausgelastet") }
        try {
            repo(noKey, busy).load(center, 50_000.0)
            fail("expected failure")
        } catch (e: ProviderException) {
            assertThat(e.message).isEqualTo("kein Schlüssel · Overpass ausgelastet")
        }
    }

    @Test fun `results of a fast source appear before a slow one is done`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val slow = object : StationProvider {
            override val id = "osm"
            override suspend fun search(query: StationQuery): StationResult {
                gate.await()
                return StationResult(listOf(far), false)
            }
        }
        val partials = mutableListOf<List<String>>()
        val r = repo(FakeStations("ocm", ok(near)), slow).load(center, 50_000.0) {
            partials += it.stations.map { s -> s.id }
            gate.complete(Unit)
        }
        assertThat(partials).containsExactly(listOf("a"))
        assertThat(r.stations.map { it.id }).containsExactly("a", "b")
    }

    @Test fun `a local source needs no cache and offline only it still answers`() = runTest {
        val local = object : StationProvider {
            override val id = "bnetza"
            override val local = true
            override suspend fun search(query: StationQuery) = StationResult(listOf(near), false)
        }
        val r = repo(local, FakeStations("ocm", offline)).load(center, 50_000.0)
        assertThat(r.stations.map { it.id }).containsExactly("a")
        assertThat(r.notice).isEqualTo("offline")
        assertThat(cache.coverage).isEmpty()
    }

    @Test fun `a local source with nothing here does not hide that the network is down`() = runTest {
        val empty = object : StationProvider {
            override val id = "bnetza"
            override val local = true
            override suspend fun search(query: StationQuery) = StationResult(emptyList(), false)
        }
        try {
            repo(empty, FakeStations("ocm", offline)).load(center, 50_000.0)
            fail("expected failure")
        } catch (e: ProviderException) {
            assertThat(e.message).isEqualTo("offline")
        }
    }

    @Test fun `truncation survives a cache hit`() = runTest {
        val p = FakeStations("p") { StationResult(listOf(near), true) }
        repo(p).load(center, 50_000.0)
        assertThat(repo(p).load(center, 50_000.0).truncated).isTrue()
    }

    @Test fun `results are limited to the radius and sorted, overfetched stations stay hidden`() = runTest {
        val r = search(FakePois { emptyList() }, FakeStations("p", ok(outside, far, near))).search(center, StationFilter())
        assertThat(r.hits.map { it.station.id }).containsExactly("a", "b").inOrder()
    }

    @Test fun `operators stay selectable even when the filter hides them`() = runTest {
        val enbw = near.copy(id = "e", operator = "EnBW")
        val f = StationFilter(operators = setOf("EnBW"))
        val r = search(FakePois { emptyList() }, FakeStations("p", ok(near, enbw))).search(center, f)
        assertThat(r.operators).containsExactly("EnBW", "IONITY").inOrder()
        assertThat(r.hits).hasSize(1)
    }

    @Test fun `station and hiking filters combine`() = runTest {
        val pois = FakePois { listOf(trail(lat = 48.1)) }
        val f = StationFilter(minPowerKw = 150.0, hiking = HikingFilter(signpostedOnly = true, roundTripOnly = true, minLengthKm = 2.0, maxLengthKm = 5.0))
        val r = search(pois, FakeStations("p", ok(near, far))).search(center, f)
        assertThat(r.hits.map { it.station.id }).containsExactly("a")
        assertThat(r.hits.single().nearby).hasSize(1)
        assertThat(r.notices.joinToString()).contains("Luftlinie")
    }

    @Test fun `hiking results are cached across filter changes`() = runTest {
        val pois = FakePois { listOf(trail(lat = 48.1)) }
        val s = search(pois, FakeStations("p", ok(near)))
        s.search(center, StationFilter(hiking = HikingFilter(withinMeters = 500)))
        s.search(center, StationFilter(hiking = HikingFilter(withinMeters = 2000)))
        assertThat(pois.calls).isEqualTo(1)
    }

    @Test fun `failing hiking lookup keeps stations search alive and says so`() = runTest {
        val pois = FakePois { throw ProviderException("Overpass: Dienst ausgelastet") }
        val r = search(pois, FakeStations("p", ok(near))).search(center, StationFilter(hiking = HikingFilter()))
        assertThat(r.hits).isEmpty()
        assertThat(r.notices.joinToString()).contains("unvollständig")
        assertThat(cache.pois).isEmpty()
    }

    @Test fun `hiking check is capped and the cap is disclosed`() = runTest {
        val many = (0 until 30).map { near.copy(id = "s$it", location = GeoPoint(48.0 + it * 0.001, 11.0)) }
        val pois = FakePois { emptyList() }
        val r = search(pois, FakeStations("p", ok(*many.toTypedArray()))).search(center, StationFilter(hiking = HikingFilter()))
        assertThat(pois.calls).isEqualTo(2)
        assertThat(r.notices.joinToString()).contains("24 nächsten von 30")
    }
}
