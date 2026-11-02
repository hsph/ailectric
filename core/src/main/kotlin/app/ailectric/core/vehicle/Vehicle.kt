package app.ailectric.core.vehicle

import app.ailectric.core.model.ConnectorType
import kotlinx.serialization.Serializable

enum class VehicleDataSource { CAR_API, MANUAL_PROFILE }
enum class VehicleValueStatus { AVAILABLE, UNAVAILABLE, UNSUPPORTED }

/** Source-independent vehicle value: value, unit, source, timestamp and availability. */
data class VehicleValue<T>(
    val value: T?,
    val unit: String,
    val source: VehicleDataSource,
    val timestampMillis: Long?,
    val status: VehicleValueStatus,
)

data class VehicleState(
    val stateOfChargePercent: VehicleValue<Float>? = null,
    val rangeRemainingMeters: VehicleValue<Float>? = null,
    val speedMetersPerSecond: VehicleValue<Float>? = null,
    val consumptionKwhPer100Km: VehicleValue<Double>? = null,
)

/** Implementations: the manual profile now, Android for Cars hardware APIs in phase 5. */
interface VehicleDataSourceProvider {
    fun current(): VehicleState
}

/** All fields optional: the app works without any profile. */
@Serializable
data class VehicleProfile(
    val make: String? = null,
    val model: String? = null,
    val batteryKwh: Double? = null,
    val maxChargeKw: Double? = null,
    val connectors: Set<ConnectorType> = emptySet(),
    val consumptionKwhPer100Km: Double? = null,
)

class ManualVehicleData(private val profile: VehicleProfile?) : VehicleDataSourceProvider {
    override fun current(): VehicleState = VehicleState(
        consumptionKwhPer100Km = profile?.consumptionKwhPer100Km?.let {
            VehicleValue(it, "kWh/100km", VehicleDataSource.MANUAL_PROFILE, null, VehicleValueStatus.AVAILABLE)
        },
    )
}
