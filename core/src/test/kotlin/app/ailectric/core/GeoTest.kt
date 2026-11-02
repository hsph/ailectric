package app.ailectric.core

import app.ailectric.core.geo.BoundingBox
import app.ailectric.core.geo.Distance
import app.ailectric.core.geo.DistanceKind
import app.ailectric.core.geo.Geo
import app.ailectric.core.geo.GeoPoint
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GeoTest {
    private val munich = GeoPoint(48.137, 11.575)
    private val paris = GeoPoint(48.8566, 2.3522)
    private val oslo = GeoPoint(59.9139, 10.7522)

    @Test fun `distance matches known city pairs`() {
        assertThat(Geo.distanceMeters(munich, paris) / 1000).isWithin(5.0).of(685.0)
        assertThat(Geo.distanceMeters(munich, oslo) / 1000).isWithin(8.0).of(1310.0)
        assertThat(Geo.distanceMeters(munich, munich)).isEqualTo(0.0)
    }

    @Test fun `bounding box contains the whole circle in the far north`() {
        val tromso = GeoPoint(69.65, 18.96)
        val box = BoundingBox.around(tromso, 50_000.0)
        assertThat(box.contains(GeoPoint(69.65, 20.2))).isTrue()
        assertThat(box.contains(GeoPoint(70.2, 18.96))).isFalse()
    }

    @Test fun `distance to line uses the nearest segment, not the nearest vertex`() {
        val line = listOf(GeoPoint(48.0, 11.0), GeoPoint(48.0, 12.0))
        val p = GeoPoint(48.001, 11.5)
        assertThat(Geo.distanceToLineMeters(p, line)).isWithin(2.0).of(111.2)
    }

    @Test fun `coordinates from bad data are rejected`() {
        assertThat(GeoPoint.orNull(null, 1.0)).isNull()
        assertThat(GeoPoint.orNull(95.0, 1.0)).isNull()
        assertThat(GeoPoint.orNull(0.0, 0.0)).isNull()
        assertThat(GeoPoint.orNull(0.0, 9.0)).isNotNull()
    }

    @Test fun `an airline distance is never labelled as a route`() {
        assertThat(Distance(12_345.0, DistanceKind.AIRLINE).label()).isEqualTo("12,3 km Luftlinie")
        assertThat(Distance(800.0, DistanceKind.WALKING).label()).isEqualTo("800 m Fußweg")
    }
}
