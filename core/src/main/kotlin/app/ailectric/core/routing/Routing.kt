package app.ailectric.core.routing

import app.ailectric.core.geo.Distance
import app.ailectric.core.geo.DistanceKind
import app.ailectric.core.geo.Geo
import app.ailectric.core.geo.GeoPoint

/** Prepared for Valhalla/GraphHopper: a real implementation returns WALKING or DRIVING values. */
interface RoutingProvider {
    suspend fun walking(from: GeoPoint, to: GeoPoint): Distance
}

/** Without a routing engine: an honest straight line, labelled as such. */
object AirlineRouting : RoutingProvider {
    override suspend fun walking(from: GeoPoint, to: GeoPoint) =
        Distance(Geo.distanceMeters(from, to), DistanceKind.AIRLINE)
}
