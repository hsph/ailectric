package app.ailectric.core.provider

import app.ailectric.core.geo.BoundingBox
import app.ailectric.core.geo.Geo
import app.ailectric.core.geo.GeoPoint
import app.ailectric.core.model.Address
import app.ailectric.core.model.ChargingDevice
import app.ailectric.core.model.Connector
import app.ailectric.core.model.ConnectorType
import app.ailectric.core.model.CurrentType
import app.ailectric.core.model.DataSource
import app.ailectric.core.model.OperationalStatus
import app.ailectric.core.model.Station
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.floor

/**
 * The German charging register of the Bundesnetzagentur, shipped with the app as tiles built by
 * tools/build_register.py. Works offline, knows no prices and covers only Germany.
 *
 * A tile covers 0.5° by 0.5° and is named `floor(lat*2)_floor(lon*2).tsv`. Columns: id, operator,
 * name, street, postcode, town, lat, lon, working (1/0), hours, access, charge points. Charge
 * points are `|`-separated `reference~TYPE:kW+TYPE:kW`.
 */
class RegisterProvider(
    private val stand: String?,
    /** Text of a tile, null when it has no stations. */
    private val tile: (String) -> String?,
) : StationProvider {
    override val id = "bnetza"
    override val local = true

    override suspend fun search(query: StationQuery): StationResult = withContext(Dispatchers.IO) {
        val box = BoundingBox.around(query.center, query.radiusMeters)
        val stations = tileNames(box).flatMap { name -> tile(name)?.lineSequence().orEmpty() }
            .mapNotNull(::parse)
            .filter { Geo.distanceMeters(query.center, it.location) <= query.radiusMeters }
            .toList()
        StationResult(stations, truncated = false)
    }

    private fun tileNames(box: BoundingBox) = sequence {
        for (lat in floor(box.south * 2).toInt()..floor(box.north * 2).toInt()) {
            for (lon in floor(box.west * 2).toInt()..floor(box.east * 2).toInt()) yield("${lat}_$lon.tsv")
        }
    }

    internal fun parse(line: String): Station? {
        val f = line.split('\t')
        if (f.size != COLUMNS) return null
        val location = GeoPoint.orNull(f[6].toDoubleOrNull(), f[7].toDoubleOrNull()) ?: return null
        val working = f[8] == "1"
        val status = if (working) OperationalStatus.OPERATIONAL else OperationalStatus.NOT_OPERATIONAL
        val devices = f[11].split('|').filter { it.isNotEmpty() }.map { device(it, status) }
        return Station(
            id = "bnetza:${f[0]}",
            name = f[2].ifEmpty { null },
            operator = f[1].ifEmpty { null },
            address = Address(f[3].ifEmpty { null }, f[4].ifEmpty { null }, f[5].ifEmpty { null }, "DE"),
            location = location,
            devices = devices,
            openingHours = f[9].ifEmpty { null },
            access = f[10].ifEmpty { null },
            status = status,
            lastUpdated = stand,
            source = DataSource("Ladesäulenregister", "Bundesnetzagentur.de", "CC BY 4.0"),
        )
    }

    private fun device(text: String, status: OperationalStatus): ChargingDevice {
        val reference = text.substringBefore('~').ifEmpty { null }
        val connectors = text.substringAfter('~').split('+').map { plug ->
            val (type, current) = connectorType(plug.substringBefore(':'))
            Connector(type, current, plug.substringAfter(':').toDoubleOrNull())
        }
        return ChargingDevice(reference, connectors, status)
    }

    private fun connectorType(name: String): Pair<ConnectorType, CurrentType> = when (name) {
        "CCS2" -> ConnectorType.CCS2 to CurrentType.DC
        "CHADEMO" -> ConnectorType.CHADEMO to CurrentType.DC
        "TYPE2" -> ConnectorType.TYPE2 to CurrentType.AC
        "TYPE2_TETHERED" -> ConnectorType.TYPE2_TETHERED to CurrentType.AC
        "TYPE1" -> ConnectorType.TYPE1 to CurrentType.AC
        "SCHUKO" -> ConnectorType.SCHUKO to CurrentType.AC
        "CEE" -> ConnectorType.CEE to CurrentType.AC
        "OTHER_DC" -> ConnectorType.OTHER to CurrentType.DC
        "OTHER_AC" -> ConnectorType.OTHER to CurrentType.AC
        else -> ConnectorType.OTHER to CurrentType.UNKNOWN
    }

    private companion object {
        const val COLUMNS = 12
    }
}
