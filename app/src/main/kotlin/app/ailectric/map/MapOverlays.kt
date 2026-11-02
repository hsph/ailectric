package app.ailectric.map

import android.content.Context
import android.graphics.Color
import app.ailectric.data.MapBase
import app.ailectric.data.MapSettings
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.expressions.Expression.eq
import org.maplibre.android.style.expressions.Expression.get
import org.maplibre.android.style.expressions.Expression.interpolate
import org.maplibre.android.style.expressions.Expression.linear
import org.maplibre.android.style.expressions.Expression.literal
import org.maplibre.android.style.expressions.Expression.lte
import org.maplibre.android.style.expressions.Expression.neq
import org.maplibre.android.style.expressions.Expression.stop
import org.maplibre.android.style.expressions.Expression.switchCase
import org.maplibre.android.style.expressions.Expression.toNumber
import org.maplibre.android.style.expressions.Expression.zoom
import org.maplibre.android.style.layers.Layer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.lineCap
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineDasharray
import org.maplibre.android.style.layers.PropertyFactory.lineJoin
import org.maplibre.android.style.layers.PropertyFactory.lineOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.layers.PropertyFactory.symbolPlacement
import org.maplibre.android.style.layers.PropertyFactory.textColor
import org.maplibre.android.style.layers.PropertyFactory.textField
import org.maplibre.android.style.layers.PropertyFactory.textFont
import org.maplibre.android.style.layers.PropertyFactory.textHaloColor
import org.maplibre.android.style.layers.PropertyFactory.textHaloWidth
import org.maplibre.android.style.layers.PropertyFactory.textSize
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.RasterSource
import org.maplibre.android.style.sources.TileSet
import org.maplibre.android.style.sources.VectorSource
import java.io.File

/**
 * The optional views on top of the base map. Only the chosen ones exist on the map, so the others
 * cost no tile requests on these donated servers.
 */
object MapOverlays {
    private const val SATELLITE = "satellite"
    private const val HIKING = "hiking-routes"
    private const val OSM_ROUTES = "osm-routes"
    private const val BIKE_PATHS = "bike-paths"
    private const val BIKE_LANES = "bike-lanes"
    private const val OSM_HIKING = "osm-hiking"
    private const val OSM_HIKING_LABELS = "osm-hiking-labels"
    private const val SIGNED_RANK = 2

    /** Far from the station colours; magenta also keeps apart from Waymarked's red. */
    private val BIKE_COLOR = Color.rgb(30, 58, 138)
    private val HIKE_COLOR = Color.rgb(162, 28, 175)

    /** Built by tools/overlays and copied onto the phone, so it also works without reception. */
    fun tilesFile(context: Context) = File(context.getExternalFilesDir(null), "overlays.pmtiles")

    fun apply(style: Style, settings: MapSettings, tiles: File, routesMinZoom: Float = 0f) {
        listOf(OSM_HIKING_LABELS, OSM_HIKING, BIKE_LANES, BIKE_PATHS).forEach { style.removeLayer(it) }
        style.removeSource(OSM_ROUTES)
        listOf(HIKING, SATELLITE).forEach {
            style.removeLayer(it)
            style.removeSource(it)
        }
        // Below the base map's labels, so places stay readable.
        val labels = style.layers.first { it is SymbolLayer }.id
        if (settings.base == MapBase.SATELLITE) {
            style.addSource(raster(SATELLITE, SATELLITE_TILES, maxZoom = 16f))
            style.addLayerBelow(RasterLayer(SATELLITE, SATELLITE), labels)
        }
        if (settings.hikingTrails) {
            style.addSource(raster(HIKING, "https://tile.waymarkedtrails.org/hiking/{z}/{x}/{y}.png", maxZoom = 17f))
            style.addLayerBelow(RasterLayer(HIKING, HIKING).apply { setMinZoom(routesMinZoom) }, StationLayers.TRAILS_LAYER)
        }
        if ((settings.bikeRoutes || settings.osmHiking) && tiles.exists()) {
            style.addSource(VectorSource(OSM_ROUTES, "pmtiles://file://${tiles.path}"))
            val layers = (if (settings.bikeRoutes) bikeLayers() else emptyList()) + (if (settings.osmHiking) hikingLayers() else emptyList())
            layers.forEach {
                it.minZoom = maxOf(it.minZoom, routesMinZoom)
                style.addLayerBelow(it, labels)
            }
        }
    }

    private fun raster(id: String, tiles: String, maxZoom: Float) =
        RasterSource(id, TileSet("2.1.0", tiles).apply { setMaxZoom(maxZoom) }, 256)

    /** Grows with the zoom, so the lines don't cover the map in the overview. */
    private fun width(near: Float): Expression = interpolate(
        linear(), zoom(), stop(9, 0.6f), stop(12, near * 0.6f), stop(15, near),
    )

    private fun bikeLayers(): List<Layer> = listOf(
        LineLayer(BIKE_PATHS, OSM_ROUTES).withSourceLayer("bike").withFilter(eq(get("class"), literal("path"))).withProperties(
            lineColor(BIKE_COLOR), lineWidth(width(3f)),
            lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND),
        ),
        LineLayer(BIKE_LANES, OSM_ROUTES).withSourceLayer("bike").withFilter(eq(get("class"), literal("lane"))).withProperties(
            lineColor(BIKE_COLOR), lineWidth(width(3f)), lineDasharray(arrayOf(2f, 1.5f)),
        ),
    )

    private fun hikingLayers(): List<Layer> {
        val signed = lte(toNumber(get("rank")), literal(SIGNED_RANK))
        return listOf(
            LineLayer(OSM_HIKING, OSM_ROUTES).withSourceLayer("hike").withProperties(
                lineColor(HIKE_COLOR), lineWidth(width(3f)), lineDasharray(arrayOf(3f, 1.5f)),
                lineOpacity(switchCase(signed, literal(0.95f), literal(0.7f))),
            ),
            SymbolLayer(OSM_HIKING_LABELS, OSM_ROUTES).withSourceLayer("hike").withFilter(signed).apply { setMinZoom(13f) }.withProperties(
                symbolPlacement(Property.SYMBOL_PLACEMENT_LINE),
                // The tiles hold an empty name rather than none.
                textField(switchCase(neq(get("name"), literal("")), get("name"), get("ref"))),
                textFont(arrayOf("Noto Sans Regular")), textSize(12f), textColor(HIKE_COLOR),
                textHaloColor(Color.WHITE), textHaloWidth(1.5f),
            ),
        )
    }

    /** Non-commercial layer; the year also appears in [MapBase.SATELLITE]'s credit. */
    private const val SATELLITE_TILES =
        "https://tiles.maps.eox.at/wmts/1.0.0/s2cloudless-2025_3857/default/g/{z}/{y}/{x}.jpg"
}
