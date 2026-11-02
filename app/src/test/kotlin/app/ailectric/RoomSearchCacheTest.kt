package app.ailectric

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.ailectric.core.cache.Coverage
import app.ailectric.core.geo.BoundingBox
import app.ailectric.core.geo.GeoPoint
import app.ailectric.core.model.Address
import app.ailectric.core.model.ChargingDevice
import app.ailectric.core.model.Connector
import app.ailectric.core.model.ConnectorType
import app.ailectric.core.model.CurrentType
import app.ailectric.core.model.DataSource
import app.ailectric.core.model.Station
import app.ailectric.data.AppDatabase
import app.ailectric.data.RoomSearchCache
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class RoomSearchCacheTest {
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
        .allowMainThreadQueries().build()
    private val cache = RoomSearchCache(db.cache())

    private fun station(id: String, lat: Double) = Station(
        id = id, name = "N", operator = null, address = Address(), location = GeoPoint(lat, 11.0),
        devices = listOf(ChargingDevice(connectors = listOf(Connector(ConnectorType.CCS2, CurrentType.DC, 300.0)))),
        source = DataSource("P", "P"),
    )

    @After fun close() = db.close()

    @Test fun `stations round-trip completely and are found by area`() = runBlocking {
        val coverage = Coverage(GeoPoint(48.0, 11.0), 75_000.0, 5)
        cache.store("ocm", coverage, listOf(station("a", 48.0), station("b", 52.0)), truncated = true)
        val found = cache.stationsIn("ocm", BoundingBox(47.0, 10.0, 49.0, 12.0))
        assertThat(found).containsExactly(station("a", 48.0))
        assertThat(cache.coverages("ocm")).containsExactly(coverage)
        assertThat(cache.wasTruncated("ocm", coverage)).isTrue()
    }

    @Test fun `providers do not see each other's stations`() = runBlocking {
        cache.store("ocm", Coverage(GeoPoint(48.0, 11.0), 1.0, 5), listOf(station("a", 48.0)), false)
        assertThat(cache.stationsIn("osm", BoundingBox(47.0, 10.0, 49.0, 12.0))).isEmpty()
    }

    @Test fun `old pois are not returned`() = runBlocking {
        cache.storePois("a", emptyList(), nowMillis = 100)
        assertThat(cache.poisFor("a", notBeforeMillis = 50)).isEmpty()
        assertThat(cache.poisFor("a", notBeforeMillis = 200)).isNull()
    }
}
