package app.ailectric.map

import app.ailectric.data.MapBase
import app.ailectric.data.MapSettings
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.sources.RasterSource
import org.maplibre.android.style.sources.TileSet

/**
 * The optional raster views between the base map and the station layers. Only the chosen
 * ones exist on the map, so the others cost no tile requests on these donated servers.
 */
object MapOverlays {
    private const val TERRAIN = "terrain"
    private const val SATELLITE = "satellite"
    private const val HIKING = "hiking-routes"

    fun apply(style: Style, settings: MapSettings, hikingMinZoom: Float = 0f) {
        listOf(HIKING, SATELLITE, TERRAIN).forEach {
            style.removeLayer(it)
            style.removeSource(it)
        }
        when (settings.base) {
            MapBase.STANDARD -> Unit
            MapBase.TERRAIN -> add(style, TERRAIN, "https://a.tile.opentopomap.org/{z}/{x}/{y}.png", maxZoom = 17f)
            MapBase.SATELLITE -> add(style, SATELLITE, SATELLITE_TILES, maxZoom = 16f)
        }
        if (settings.hikingTrails) {
            add(style, HIKING, "https://tile.waymarkedtrails.org/hiking/{z}/{x}/{y}.png", maxZoom = 17f, minZoom = hikingMinZoom)
        }
    }

    private fun add(style: Style, id: String, tiles: String, maxZoom: Float, minZoom: Float = 0f) {
        style.addSource(RasterSource(id, TileSet("2.1.0", tiles).apply { setMaxZoom(maxZoom) }, 256))
        style.addLayerBelow(RasterLayer(id, id).apply { setMinZoom(minZoom) }, StationLayers.TRAILS_LAYER)
    }

    /** Non-commercial layer; the year also appears in [MapBase.SATELLITE]'s credit. */
    private const val SATELLITE_TILES =
        "https://tiles.maps.eox.at/wmts/1.0.0/s2cloudless-2025_3857/default/g/{z}/{y}/{x}.jpg"
}
