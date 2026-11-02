package app.ailectric.car

import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.VisibleForTesting
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import app.ailectric.core.geo.GeoPoint
import app.ailectric.core.model.Station
import app.ailectric.data.CarSettings
import app.ailectric.data.MapBase
import app.ailectric.core.search.StationHit
import app.ailectric.map.MapOverlays
import app.ailectric.map.StationLayers
import app.ailectric.ui.PowerGroup
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

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
    private var beforeFocus: CameraPosition? = null
    private var selected: Station? = null
    private var settings = CarSettings()
    private var credit: TextView? = null
    /** Until the driver moves the map, the start point follows the panel's edge. */
    private var atStart = true
    private val pinch = PinchZoom()

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

    /** Where the camera's target is drawn on the surface. */
    @get:VisibleForTesting
    val centerOnSurface: PointF? get() = map?.let { it.projection.toScreenLocation(it.cameraPosition.target!!) }

    /** Takes effect at once, also when the stored values arrive after the map. */
    fun apply(next: CarSettings) {
        val zoomChanged = next.startZoom != settings.startZoom
        val mapChanged = next.map != settings.map
        settings = next
        if (mapChanged) showMapView()
        if (zoomChanged && beforeFocus == null) {
            map?.animateCamera(CameraUpdateFactory.zoomTo(next.startZoom.toDouble()), ANIMATION_MS)
        }
    }

    /** The host hides the panel in pan mode; the map must not jump when the area changes then. */
    fun holdPosition() {
        atStart = false
    }

    fun show(hits: List<StationHit>, me: GeoPoint?) {
        this.hits = hits
        this.me = me
        style?.let { StationLayers.update(it, hits, me) }
    }

    fun center(point: GeoPoint) {
        val m = map
        if (m == null) pendingCenter = point
        else m.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(point.lat, point.lon), settings.startZoom.toDouble()))
    }

    /** Marks a station and zooms in on it, or only moves there if the driver is closer already. */
    fun focus(station: Station) {
        selected = station
        style?.let { StationLayers.highlight(it, station) }
        val m = map ?: return
        atStart = false
        val point = station.location
        if (beforeFocus == null) beforeFocus = m.cameraPosition
        val zoom = maxOf(m.cameraPosition.zoom, settings.focusZoom.toDouble())
        m.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(point.lat, point.lon), zoom), FOCUS_ANIMATION_MS)
    }

    /** Back to the overview the station was selected from. */
    fun unfocus() {
        selected = null
        style?.let { StationLayers.highlight(it, null) }
        val before = beforeFocus ?: return
        beforeFocus = null
        map?.animateCamera(CameraUpdateFactory.newCameraPosition(before), FOCUS_ANIMATION_MS)
    }

    fun zoomBy(delta: Double) {
        atStart = false
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
            // Stacked, because the credit grows with the chosen map view.
            addView(
                LinearLayout(shown.context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.END
                    addView(legend(shown.context))
                    // Required by the licences; the car UI has no attribution dialog.
                    addView(
                        TextView(shown.context).apply {
                            textSize = 9f
                            gravity = Gravity.END
                            // On its own ground: bare text gets lost on the busy overlays.
                            setTextColor(Color.rgb(20, 20, 20))
                            setBackgroundColor(Color.argb(240, 255, 255, 255))
                            setPadding(6, 2, 6, 2)
                            credit = this
                        },
                    )
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
            m.setMinZoomPreference(MIN_ZOOM)
            m.setMaxZoomPreference(MAX_ZOOM)
            m.cameraPosition = CameraPosition.Builder().target(LatLng(start.lat, start.lon)).zoom(settings.startZoom.toDouble()).build()
            applyPadding()
            m.setStyle(StationLayers.STYLE_URL) { loaded ->
                style = loaded
                StationLayers.install(loaded)
                StationLayers.update(loaded, hits, me)
                StationLayers.highlight(loaded, selected)
                showMapView()
            }
        }
        showCredit()
        shown.show()
        view.onStart()
        view.onResume()
        display = virtual
        presentation = shown
        mapView = view
    }

    private fun showMapView() {
        style?.let { MapOverlays.apply(it, settings.map, MapOverlays.tilesFile(carContext), ROUTES_MIN_ZOOM) }
        showCredit()
    }

    /** Satellite covers OpenFreeMap's tiles entirely, so only the shown sources are named. */
    private fun showCredit() {
        val map = settings.map
        val base = BASE_CREDIT.takeIf { map.base == MapBase.STANDARD }
        credit?.text = (listOfNotNull(base) + map.credits).joinToString("\n")
    }

    /** Small and pale, so it explains the colours without competing with the map. */
    private fun legend(context: Context) = LinearLayout(context).apply {
        fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(4), dp(3), dp(8), dp(3))
        background = GradientDrawable().apply {
            setColor(Color.argb(200, 255, 255, 255))
            cornerRadius = dp(8).toFloat()
        }
        PowerGroup.LEGEND.forEach { group ->
            addView(
                View(context).apply {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(group.color)
                        setStroke(1, Color.rgb(20, 20, 20))
                    }
                },
                LinearLayout.LayoutParams(dp(10), dp(10)).apply { marginStart = dp(6) },
            )
            addView(
                TextView(context).apply {
                    text = group.label
                    textSize = 11f
                    setTextColor(Color.rgb(20, 20, 20))
                },
                LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(3) },
            )
        }
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) {
        this.visibleArea = visibleArea
        applyPadding()
    }

    /** Keeps the map's centre in the part not covered by the template's list. */
    private fun applyPadding() {
        val area = visibleArea ?: return
        val view = mapView ?: return
        val m = map ?: return
        val right = (view.width - area.right).coerceAtLeast(0)
        val bottom = (view.height - area.bottom).coerceAtLeast(0)
        m.setPadding(area.left, area.top, right, bottom)
        // setPadding only affects the next camera move, so the first position needs one of its own.
        if (atStart) {
            m.moveCamera(CameraUpdateFactory.paddingTo(area.left.toDouble(), area.top.toDouble(), right.toDouble(), bottom.toDouble()))
        }
    }

    override fun onSurfaceDestroyed(container: SurfaceContainer) = release()

    override fun onScroll(distanceX: Float, distanceY: Float) {
        atStart = false
        if (pinch.isPinching) return
        map?.scrollBy(-distanceX, -distanceY, 0)
    }

    /** Zooms around the map's centre: the focus point moves the camera with every event of a pinch. */
    override fun onScale(focusX: Float, focusY: Float, scaleFactor: Float) {
        atStart = false
        map?.moveCamera(CameraUpdateFactory.zoomBy(pinch.zoomDelta(scaleFactor)))
    }

    override fun onClick(x: Float, y: Float) {
        val feature = map?.queryRenderedFeatures(PointF(x, y), StationLayers.STATION_LAYER)?.firstOrNull() ?: return
        feature.getStringProperty(StationLayers.PROP_ID)?.let(onStationClicked)
    }

    private fun release() {
        atStart = true
        style = null
        credit = null
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
        /** Further out a charger search means nothing, and it is how a pinch gets lost in Europe. */
        const val MIN_ZOOM = 5.0
        const val MAX_ZOOM = 18.0
        /** Further out the routes bury the roads and the station dots. */
        const val ROUTES_MIN_ZOOM = 12f
        const val BASE_CREDIT = "OpenFreeMap © OpenMapTiles © OpenStreetMap"
        const val ANIMATION_MS = 200
        const val FOCUS_ANIMATION_MS = 600
    }
}
