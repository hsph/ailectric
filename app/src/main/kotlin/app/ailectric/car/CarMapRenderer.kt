package app.ailectric.car

import android.app.Presentation
import android.graphics.PointF
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import app.ailectric.core.geo.GeoPoint
import app.ailectric.core.search.StationHit
import app.ailectric.map.StationLayers
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import kotlin.math.ln

/**
 * Draws a real MapLibre map onto the host's surface: a Presentation on a VirtualDisplay backed by
 * that surface hosts a normal MapView, and host gestures are forwarded to the map.
 */
class CarMapRenderer(
    private val carContext: CarContext,
    lifecycle: Lifecycle,
) : SurfaceCallback, DefaultLifecycleObserver {
    private var display: VirtualDisplay? = null
    private var presentation: Presentation? = null
    private var mapView: MapView? = null
    private var map: MapLibreMap? = null
    private var style: Style? = null
    private var hits: List<StationHit> = emptyList()
    private var me: GeoPoint? = null
    private var pendingCenter: GeoPoint? = null
    private var visibleArea: Rect? = null

    var onStationClicked: (String) -> Unit = {}

    init {
        lifecycle.addObserver(this)
    }

    override fun onCreate(owner: LifecycleOwner) {
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(this)
    }

    override fun onDestroy(owner: LifecycleOwner) = release()

    val cameraCenter: GeoPoint?
        get() = map?.cameraPosition?.target?.let { GeoPoint.orNull(it.latitude, it.longitude.wrapLon()) }

    val zoomLevel: Double? get() = map?.cameraPosition?.zoom

    val styleLoaded: Boolean get() = style != null

    fun show(hits: List<StationHit>, me: GeoPoint?) {
        this.hits = hits
        this.me = me
        style?.let { StationLayers.update(it, hits, me) }
    }

    fun center(point: GeoPoint) {
        val m = map
        if (m == null) pendingCenter = point
        else m.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(point.lat, point.lon), SEARCH_ZOOM))
    }

    fun zoomBy(delta: Double) {
        map?.animateCamera(CameraUpdateFactory.zoomBy(delta), ANIMATION_MS)
    }

    override fun onSurfaceAvailable(container: SurfaceContainer) {
        val surface = container.surface ?: return
        if (container.width == 0 || container.height == 0) return
        release()
        val virtual = carContext.getSystemService(DisplayManager::class.java).createVirtualDisplay(
            "ailectric-map", container.width, container.height, container.dpi, surface,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
        ) ?: return
        val shown = Presentation(carContext, virtual.display)
        val view = MapView(
            shown.context,
            MapLibreMapOptions.createFromAttributes(shown.context).textureMode(true)
                .logoEnabled(false).attributionEnabled(false),
        )
        val root = FrameLayout(shown.context).apply {
            addView(view)
            // Required by the OSM/OpenFreeMap licences; the car UI has no attribution dialog.
            addView(
                TextView(shown.context).apply {
                    text = "OpenFreeMap © OpenMapTiles © OpenStreetMap"
                    textSize = 9f
                    setShadowLayer(3f, 0f, 0f, android.graphics.Color.WHITE)
                },
                FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, 8, 4) },
            )
        }
        shown.setContentView(root)
        view.onCreate(null)
        view.getMapAsync { m ->
            map = m
            val start = pendingCenter ?: me ?: StationLayers.DEFAULT_CENTER
            pendingCenter = null
            m.cameraPosition = CameraPosition.Builder().target(LatLng(start.lat, start.lon)).zoom(SEARCH_ZOOM).build()
            applyPadding()
            m.setStyle(StationLayers.STYLE_URL) { loaded ->
                style = loaded
                StationLayers.install(loaded)
                StationLayers.update(loaded, hits, me)
            }
        }
        shown.show()
        view.onStart()
        view.onResume()
        display = virtual
        presentation = shown
        mapView = view
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) {
        this.visibleArea = visibleArea
        applyPadding()
    }

    /** Keeps the map's centre in the part not covered by the template's list. */
    private fun applyPadding() {
        val area = visibleArea ?: return
        val view = mapView ?: return
        map?.setPadding(area.left, area.top, (view.width - area.right).coerceAtLeast(0), (view.height - area.bottom).coerceAtLeast(0))
    }

    override fun onSurfaceDestroyed(container: SurfaceContainer) = release()

    override fun onScroll(distanceX: Float, distanceY: Float) {
        map?.scrollBy(-distanceX, -distanceY, 0)
    }

    override fun onScale(focusX: Float, focusY: Float, scaleFactor: Float) {
        // scaleFactor is the host's, e.g. 2 for double tap; a map zoom level is a power of two.
        val delta = ln(scaleFactor.toDouble()) / ln(2.0)
        map?.moveCamera(CameraUpdateFactory.zoomBy(delta, android.graphics.Point(focusX.toInt(), focusY.toInt())))
    }

    override fun onClick(x: Float, y: Float) {
        val feature = map?.queryRenderedFeatures(PointF(x, y), StationLayers.STATION_LAYER)?.firstOrNull() ?: return
        feature.getStringProperty(StationLayers.PROP_ID)?.let(onStationClicked)
    }

    private fun release() {
        style = null
        map = null
        mapView?.run {
            onPause()
            onStop()
            onDestroy()
        }
        mapView = null
        presentation?.dismiss()
        presentation = null
        display?.release()
        display = null
    }

    private fun Double.wrapLon() = ((this + 540) % 360) - 180

    private companion object {
        const val SEARCH_ZOOM = 9.0
        const val ANIMATION_MS = 200
    }
}
