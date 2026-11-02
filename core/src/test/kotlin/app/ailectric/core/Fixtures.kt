package app.ailectric.core

import app.ailectric.core.geo.GeoPoint
import app.ailectric.core.model.Address
import app.ailectric.core.model.Availability
import app.ailectric.core.model.ChargingDevice
import app.ailectric.core.model.Connector
import app.ailectric.core.model.ConnectorType
import app.ailectric.core.model.CurrentType
import app.ailectric.core.model.DataSource
import app.ailectric.core.model.HikingDetails
import app.ailectric.core.model.Poi
import app.ailectric.core.model.PoiCatalog
import app.ailectric.core.model.Station

val TEST_SOURCE = DataSource("Test", "Test")

fun station(
    vararg connectors: Connector,
    operator: String? = "IONITY",
    location: GeoPoint = GeoPoint(48.0, 11.0),
) = Station(
    id = "t:1", name = "S", operator = operator, address = Address(), location = location,
    devices = connectors.map { ChargingDevice(connectors = listOf(it)) }, source = TEST_SOURCE,
)

val CCS_350 = Connector(ConnectorType.CCS2, CurrentType.DC, 350.0)
val TYPE2_22 = Connector(ConnectorType.TYPE2, CurrentType.AC, 22.0)
val TYPE2_UNKNOWN = Connector(ConnectorType.TYPE2, CurrentType.AC, null)
val CCS_FREE = CCS_350.copy(availability = Availability.AVAILABLE)

fun trail(
    lat: Double = 48.0,
    lon: Double = 11.0,
    lengthKm: Double? = 4.0,
    roundTrip: Boolean? = true,
    signposted: Boolean = true,
    ascent: Double? = null,
) = Poi(
    id = "osm:relation/1", categoryId = PoiCatalog.HIKING_ROUTE.id, name = "T",
    location = GeoPoint(lat, lon),
    geometry = listOf(listOf(GeoPoint(lat, lon), GeoPoint(lat, lon + 0.01))),
    hiking = HikingDetails(lengthKm?.times(1000), roundTrip = roundTrip, signposted = signposted, ascentMeters = ascent),
    source = TEST_SOURCE,
)
