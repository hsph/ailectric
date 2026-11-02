package app.ailectric.core

import app.ailectric.core.filter.AvailabilityFilter
import app.ailectric.core.filter.HikingFilter
import app.ailectric.core.filter.StationFilter
import app.ailectric.core.filter.nearbyPois
import app.ailectric.core.geo.GeoPoint
import app.ailectric.core.model.ConnectorType
import app.ailectric.core.model.CurrentType
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FilterTest {
    private val mixed = station(CCS_350, TYPE2_22)

    @Test fun `default filter keeps everything, including empty stations`() {
        assertThat(StationFilter().matches(station())).isTrue()
        assertThat(StationFilter().isDefault).isTrue()
    }

    @Test fun `min power matches any connector`() {
        assertThat(StationFilter(minPowerKw = 150.0).matches(mixed)).isTrue()
        assertThat(StationFilter(minPowerKw = 400.0).matches(mixed)).isFalse()
    }

    @Test fun `power and plug must hold for the same connector`() {
        val f = StationFilter(minPowerKw = 150.0, connectorTypes = setOf(ConnectorType.TYPE2))
        assertThat(f.matches(mixed)).isFalse()
    }

    @Test fun `max power excludes stations with only stronger connectors`() {
        assertThat(StationFilter(maxPowerKw = 50.0).matches(station(CCS_350))).isFalse()
        assertThat(StationFilter(maxPowerKw = 50.0).matches(mixed)).isTrue()
    }

    @Test fun `unknown power fails a power filter unless explicitly included`() {
        val s = station(TYPE2_UNKNOWN)
        assertThat(StationFilter(minPowerKw = 50.0).matches(s)).isFalse()
        assertThat(StationFilter(minPowerKw = 50.0, includeUnknownPower = true).matches(s)).isTrue()
    }

    @Test fun `current type and operator filters combine`() {
        val f = StationFilter(currentTypes = setOf(CurrentType.DC), operators = setOf("IONITY"))
        assertThat(f.matches(mixed)).isTrue()
        assertThat(f.matches(station(CCS_350, operator = "EnBW"))).isFalse()
        assertThat(f.matches(station(TYPE2_22))).isFalse()
    }

    @Test fun `free filter never treats unknown availability as free`() {
        assertThat(StationFilter(availability = AvailabilityFilter.FREE).matches(mixed)).isFalse()
        assertThat(StationFilter(availability = AvailabilityFilter.FREE).matches(station(CCS_FREE))).isTrue()
        assertThat(StationFilter(availability = AvailabilityFilter.UNKNOWN).matches(mixed)).isTrue()
        assertThat(StationFilter(availability = AvailabilityFilter.UNKNOWN).matches(station(CCS_FREE))).isFalse()
    }

    @Test fun `example from the brief - signposted loop of 2 to 5 km near a 150 kW station`() {
        val f = HikingFilter(withinMeters = 1000, signpostedOnly = true, roundTripOnly = true, minLengthKm = 2.0, maxLengthKm = 5.0)
        val s = station(CCS_350)
        assertThat(nearbyPois(s, listOf(trail()), f)).hasSize(1)
        assertThat(nearbyPois(s, listOf(trail(lengthKm = 9.0)), f)).isEmpty()
        assertThat(nearbyPois(s, listOf(trail(roundTrip = false)), f)).isEmpty()
        assertThat(nearbyPois(s, listOf(trail(signposted = false)), f)).isEmpty()
        assertThat(nearbyPois(s, listOf(trail(lat = 48.1)), f)).isEmpty()
    }

    @Test fun `unknown trail attributes fail active criteria`() {
        val s = station()
        assertThat(nearbyPois(s, listOf(trail(lengthKm = null)), HikingFilter(minLengthKm = 1.0))).isEmpty()
        assertThat(nearbyPois(s, listOf(trail(roundTrip = null)), HikingFilter(roundTripOnly = true))).isEmpty()
        assertThat(nearbyPois(s, listOf(trail(ascent = null)), HikingFilter(maxAscentMeters = 500.0))).isEmpty()
        assertThat(nearbyPois(s, listOf(trail(ascent = 300.0)), HikingFilter(maxAscentMeters = 500.0))).hasSize(1)
    }

    @Test fun `proximity uses trail geometry, not the POI's reference point`() {
        val far = trail(lat = 48.0, lon = 10.9).copy(
            geometry = listOf(listOf(GeoPoint(48.0, 10.9), GeoPoint(48.0, 11.0))),
        )
        assertThat(nearbyPois(station(), listOf(far), HikingFilter(withinMeters = 500))).hasSize(1)
    }
}
