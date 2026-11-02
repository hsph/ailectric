package app.ailectric.map

import android.graphics.Color
import app.ailectric.core.geo.GeoPoint
import app.ailectric.core.model.Station
import app.ailectric.core.search.StationHit
import app.ailectric.ui.PowerGroup
import org.maplibre.android.style.expressions.Expression.color
import org.maplibre.android.style.expressions.Expression.get
import org.maplibre.android.style.expressions.Expression.gte
import org.maplibre.android.style.expressions.Expression.has
import org.maplibre.android.style.expressions.Expression.literal
import org.maplibre.android.style.expressions.Expression.not
import org.maplibre.android.style.expressions.Expression.step
import org.maplibre.android.style.expressions.Expression.stop
import org.maplibre.android.style.expressions.Expression.toNumber
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.layers.PropertyFactory.textAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.textColor
import org.maplibre.android.style.layers.PropertyFactory.textField
import org.maplibre.android.style.layers.PropertyFactory.textFont
import org.maplibre.android.style.layers.PropertyFactory.textSize
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.maps.Style
import org.maplibre.android.style.sources.GeoJsonOptions
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/** Map content shared by the phone's MapView and the car's surface renderer. */
object StationLayers {
    /** Free vector tiles from OSM data; needs no key. Attribution is shown by the map. */
    const val STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"
    const val STATION_LAYER = "stations-points"
    const val CLUSTER_LAYER = "stations-clusters"
    const val PROP_ID = "id"
    val DEFAULT_CENTER = GeoPoint(50.1, 9.0)

    private const val STATIONS = "stations"
    private const val TRAILS = "trails"
    private const val ME = "me"
    private const val SELECTED = "selected"

    fun install(style: Style) {
        style.addSource(
            GeoJsonSource(
                STATIONS, FeatureCollection.fromFeatures(emptyList()),
                GeoJsonOptions().withCluster(true).withClusterRadius(42).withClusterMaxZoom(12),
            ),
        )
        style.addSource(GeoJsonSource(TRAILS, FeatureCollection.fromFeatures(emptyList())))
        style.addSource(GeoJsonSource(ME, FeatureCollection.fromFeatures(emptyList())))
        style.addSource(GeoJsonSource(SELECTED, FeatureCollection.fromFeatures(emptyList())))

        style.addLayer(
            LineLayer("trails-line", TRAILS).withProperties(
                lineColor(Color.rgb(196, 80, 20)), lineWidth(3f), lineOpacity(0.8f),
            ),
        )
        style.addLayer(
            CircleLayer(CLUSTER_LAYER, STATIONS).withFilter(has("point_count")).withProperties(
                circleColor(Color.rgb(0, 96, 100)), circleRadius(17f),
                circleStrokeColor(Color.WHITE), circleStrokeWidth(2f),
            ),
        )
        style.addLayer(
            SymbolLayer("stations-count", STATIONS).withFilter(has("point_count")).withProperties(
                textField(get("point_count_abbreviated")), textSize(13f), textColor(Color.WHITE),
                textFont(arrayOf("Noto Sans Bold")), textAllowOverlap(true),
            ),
        )
        style.addLayer(
            CircleLayer(STATION_LAYER, STATIONS).withFilter(not(has("point_count"))).withProperties(
                circleRadius(9f),
                // Dark, because the brightest groups would vanish on the pale map behind a white ring.
                circleStrokeColor(Color.rgb(20, 20, 20)), circleStrokeWidth(2f),
                circleColor(powerColor()),
            ),
        )
        // Like a selected radio button: larger, with a white centre, above everything but "me".
        style.addLayer(
            CircleLayer("selected-ring", SELECTED).withProperties(
                circleRadius(17f), circleColor(powerColor()),
                circleStrokeColor(Color.rgb(20, 20, 20)), circleStrokeWidth(2.5f),
            ),
        )
        style.addLayer(
            CircleLayer("selected-centre", SELECTED).withProperties(
                circleRadius(7f), circleColor(Color.WHITE),
                circleStrokeColor(Color.rgb(20, 20, 20)), circleStrokeWidth(1.5f),
            ),
        )
        style.addLayer(
            CircleLayer("me-dot", ME).withProperties(
                circleColor(Color.rgb(25, 118, 210)), circleRadius(8f),
                circleStrokeColor(Color.WHITE), circleStrokeWidth(3f),
            ),
        )
    }

    /** Unknown power has its own colour so it can't be mistaken for slow charging. */
    private fun powerColor() = step(
        toNumber(get("power")), color(PowerGroup.UNKNOWN.color),
        *PowerGroup.entries.drop(1).map { stop(it.fromKw, color(it.color)) }.toTypedArray(),
    )

    private fun Station.toFeature() = Feature.fromGeometry(location.toPoint()).apply {
        addStringProperty(PROP_ID, id)
        addNumberProperty("power", maxPowerKw ?: 0.0)
    }

    /** Marks one station on the map; null clears the mark. */
    fun highlight(style: Style, station: Station?) {
        style.getSourceAs<GeoJsonSource>(SELECTED)?.setGeoJson(
            FeatureCollection.fromFeatures(listOfNotNull(station?.toFeature())),
        )
    }

    fun update(style: Style, hits: List<StationHit>, me: GeoPoint?) {
        style.getSourceAs<GeoJsonSource>(STATIONS)?.setGeoJson(
            FeatureCollection.fromFeatures(hits.map { it.station.toFeature() }),
        )
        style.getSourceAs<GeoJsonSource>(TRAILS)?.setGeoJson(
            FeatureCollection.fromFeatures(
                hits.flatMap { it.nearby }.distinctBy { it.poi.id }.flatMap { near ->
                    near.poi.geometry.map { line -> Feature.fromGeometry(LineString.fromLngLats(line.map { it.toPoint() })) }
                },
            ),
        )
        style.getSourceAs<GeoJsonSource>(ME)?.setGeoJson(
            FeatureCollection.fromFeatures(listOfNotNull(me?.let { Feature.fromGeometry(it.toPoint()) })),
        )
    }

    private fun GeoPoint.toPoint() = Point.fromLngLat(lon, lat)
}
