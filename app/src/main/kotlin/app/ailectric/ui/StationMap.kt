package app.ailectric.ui

import android.graphics.PointF
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import app.ailectric.core.geo.GeoPoint
import app.ailectric.core.search.StationHit
import app.ailectric.map.StationLayers
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

/** Handle for the zoom buttons that sit outside the map view. */
class MapHandle {
    var map: MapLibreMap? = null
    fun zoom(delta: Double) { map?.animateCamera(CameraUpdateFactory.zoomBy(delta), 200) }
    fun center(p: GeoPoint) { map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(p.lat, p.lon), 10.0)) }
    val target: GeoPoint? get() = map?.cameraPosition?.target?.let { GeoPoint.orNull(it.latitude, it.longitude) }
}

@Composable
fun StationMap(
    hits: List<StationHit>,
    me: GeoPoint?,
    focus: GeoPoint?,
    handle: MapHandle,
    onStationClick: (String) -> Unit,
    onUserMoved: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var style by remember { mutableStateOf<Style?>(null) }
    val click by rememberUpdatedState(onStationClick)
    val moved by rememberUpdatedState(onUserMoved)
    val mapView = remember { MapView(context).also { it.onCreate(null) } }

    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            mapView.onDestroy()
        }
    }

    LaunchedEffect(mapView) {
        mapView.getMapAsync { map ->
            handle.map = map
            val start = focus ?: StationLayers.DEFAULT_CENTER
            map.cameraPosition = CameraPosition.Builder()
                .target(LatLng(start.lat, start.lon)).zoom(if (focus == null) 5.0 else 10.0).build()
            map.uiSettings.isRotateGesturesEnabled = false
            map.uiSettings.isTiltGesturesEnabled = false
            map.setStyle(StationLayers.STYLE_URL) { loaded ->
                StationLayers.install(loaded)
                style = loaded
            }
            map.addOnMapClickListener { latLng ->
                val point: PointF = map.projection.toScreenLocation(latLng)
                val feature = map.queryRenderedFeatures(point, StationLayers.STATION_LAYER).firstOrNull()
                val id = feature?.getStringProperty(StationLayers.PROP_ID)
                if (id != null) click(id)
                else if (map.queryRenderedFeatures(point, StationLayers.CLUSTER_LAYER).isNotEmpty()) {
                    map.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, map.cameraPosition.zoom + 2))
                }
                id != null
            }
            map.addOnCameraMoveStartedListener { reason ->
                if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) moved()
            }
        }
    }

    LaunchedEffect(style, hits, me) { style?.let { StationLayers.update(it, hits, me) } }
    LaunchedEffect(focus) { focus?.let(handle::center) }

    AndroidView(factory = { mapView }, modifier = modifier)
}
