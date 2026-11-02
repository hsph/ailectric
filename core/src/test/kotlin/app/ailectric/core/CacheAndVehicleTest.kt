package app.ailectric.core

import app.ailectric.core.cache.Coverage
import app.ailectric.core.cache.SearchPolicy
import app.ailectric.core.geo.GeoPoint
import app.ailectric.core.vehicle.ManualVehicleData
import app.ailectric.core.vehicle.VehicleDataSource
import app.ailectric.core.vehicle.VehicleProfile
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CacheAndVehicleTest {
    private val policy = SearchPolicy()
    private val home = GeoPoint(48.0, 11.0)
    private val coverage = Coverage(home, policy.fetchRadius(50_000.0), fetchedAtMillis = 0)

    @Test fun `small moves stay inside the stored circle`() {
        val movedTenKm = GeoPoint(48.09, 11.0)
        assertThat(policy.isCovered(movedTenKm, 50_000.0, coverage, 1000)).isTrue()
    }

    @Test fun `moving past the margin needs a new request`() {
        val movedThirtyKm = GeoPoint(48.27, 11.0)
        assertThat(policy.isCovered(movedThirtyKm, 50_000.0, coverage, 1000)).isFalse()
    }

    @Test fun `expired coverage needs a new request`() {
        assertThat(policy.isCovered(home, 50_000.0, coverage, policy.maxAgeMillis + 1)).isFalse()
    }

    @Test fun `a larger radius is not covered by a smaller search`() {
        assertThat(policy.isCovered(home, 100_000.0, coverage, 1000)).isFalse()
    }

    @Test fun `no profile yields no invented vehicle data`() {
        val state = ManualVehicleData(null).current()
        assertThat(state.consumptionKwhPer100Km).isNull()
        assertThat(state.stateOfChargePercent).isNull()
    }

    @Test fun `manual values carry their source and unit`() {
        val v = ManualVehicleData(VehicleProfile(consumptionKwhPer100Km = 18.5)).current().consumptionKwhPer100Km!!
        assertThat(v.source).isEqualTo(VehicleDataSource.MANUAL_PROFILE)
        assertThat(v.unit).isEqualTo("kWh/100km")
    }
}
