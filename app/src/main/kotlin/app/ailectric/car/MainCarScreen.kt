package app.ailectric.car

import androidx.annotation.VisibleForTesting
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.car.app.model.CarLocation
import androidx.car.app.model.Distance as CarDistance
import androidx.car.app.model.DistanceSpan
import androidx.car.app.model.Header
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Metadata
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Place
import androidx.car.app.model.PlaceListMapTemplate
import androidx.car.app.model.PlaceMarker
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.MapController
import androidx.car.app.navigation.model.MapWithContentTemplate
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import android.content.Intent
import android.text.SpannableString
import android.text.Spanned
import app.ailectric.R
import app.ailectric.app
import app.ailectric.core.search.StationHit
import app.ailectric.map.Navigation
import app.ailectric.search.SearchState
import app.ailectric.ui.Format
import kotlinx.coroutines.launch

private const val MAP_TEMPLATE_API = 7
private const val FALLBACK_LIST_LIMIT = 6

internal fun CarContext.icon(res: Int) = CarIcon.Builder(IconCompat.createWithResource(this, res)).build()

internal fun CarContext.iconAction(res: Int, onClick: () -> Unit) =
    Action.Builder().setIcon(icon(res)).setOnClickListener(onClick).build()

internal fun CarContext.navigateTo(hit: StationHit) {
    val intent = Intent(CarContext.ACTION_NAVIGATE, Navigation.geoUri(hit.station.location, Format.title(hit.station)))
    startCarApp(intent)
}

/** The car's list-and-map home screen. See docs/ANDROID_AUTO.md for why it looks this way. */
class MainCarScreen(carContext: CarContext, private val renderer: CarMapRenderer) : Screen(carContext) {
    private val session = carContext.app.session
    @VisibleForTesting internal var state = SearchState()

    init {
        renderer.onStationClicked = { id -> state.result?.hits?.firstOrNull { it.station.id == id }?.let(::openDetail) }
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) {
                lifecycleScope.launch {
                    repeatOnLifecycle(Lifecycle.State.STARTED) {
                        session.state.collect { s ->
                            val movedCenter = s.searchCenter != state.searchCenter
                            state = s
                            renderer.show(s.result?.hits.orEmpty(), s.location)
                            if (movedCenter && !s.manualCenter) s.searchCenter?.let(renderer::center)
                            invalidate()
                        }
                    }
                }
            }
        })
    }

    private fun openDetail(hit: StationHit) {
        screenManager.push(StationCarScreen(carContext, hit))
    }

    override fun onGetTemplate(): Template {
        if (!state.locationPermission && state.searchCenter == null) return permissionTemplate()
        val api = carContext.carAppApiLevel
        return if (api >= MAP_TEMPLATE_API) mapTemplate() else placeListTemplate()
    }

    private fun permissionTemplate(): Template =
        MessageTemplate.Builder("Für die Suche in deiner Nähe braucht Ailectric deinen Standort.")
            .setHeader(Header.Builder().setTitle("Standort").setStartHeaderAction(Action.APP_ICON).build())
            .addAction(
                Action.Builder().setTitle("Erlauben").setBackgroundColor(CarColor.PRIMARY).setOnClickListener {
                    carContext.requestPermissions(AilectricCarAppService.LOCATION_PERMISSIONS) { granted, _ ->
                        if (granted.isNotEmpty()) session.start()
                        else CarToast.makeText(carContext, "Bitte in der App auf dem Telefon erlauben", CarToast.LENGTH_LONG).show()
                    }
                }.build(),
            )
            .build()

    private fun listLimit(): Int =
        carContext.getCarService(ConstraintManager::class.java)
            .getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_PLACE_LIST)
            .coerceAtLeast(1)
            .coerceAtMost(FALLBACK_LIST_LIMIT * 2)

    private fun subtitle(): String {
        val r = state.result ?: return state.error ?: "Suche…"
        val count = r.hits.size
        return "$count Stationen · ${Format.freshness(r.freshness)}"
    }

    private fun filterAction() = carContext.iconAction(R.drawable.ic_filter) {
        screenManager.push(FilterCarScreen(carContext))
    }

    private fun itemList(placeMarkers: Boolean): ItemList? {
        val hits = state.result?.hits?.take(listLimit()) ?: return null
        val builder = ItemList.Builder()
        if (hits.isEmpty()) builder.setNoItemsMessage(state.error ?: "Keine Station passt zu den Filtern")
        hits.forEachIndexed { index, hit -> builder.addItem(row(hit, index, placeMarkers)) }
        return builder.build()
    }

    private fun row(hit: StationHit, index: Int, placeMarkers: Boolean): Row {
        val s = hit.station
        val builder = Row.Builder()
            .setTitle(Format.title(s))
            .addText(distanceLine(hit))
            .apply { Format.free(s)?.let(::addText) }
            .setBrowsable(true)
            .setOnClickListener { openDetail(hit) }
        if (placeMarkers) {
            builder.setMetadata(
                Metadata.Builder().setPlace(
                    Place.Builder(CarLocation.create(s.location.lat, s.location.lon))
                        .setMarker(PlaceMarker.Builder().setLabel("${index + 1}").build())
                        .build(),
                ).build(),
            )
        }
        return builder.build()
    }

    /** The span lets the host format the distance; "Luftlinie" stays part of the text. */
    private fun distanceLine(hit: StationHit): CharSequence {
        val s = hit.station
        val km = hit.distance.meters / 1000
        val text = SpannableString("  Luftlinie · ${Format.power(s)} · ${Format.plugs(s)}")
        text.setSpan(
            DistanceSpan.create(CarDistance.create(km, CarDistance.UNIT_KILOMETERS_P1)),
            0, 1, Spanned.SPAN_INCLUSIVE_INCLUSIVE,
        )
        return text
    }

    private fun mapTemplate(): Template {
        val mapStrip = ActionStrip.Builder()
            .addAction(Action.PAN)
            .addAction(carContext.iconAction(R.drawable.ic_add) { renderer.zoomBy(1.0) })
            .addAction(carContext.iconAction(R.drawable.ic_remove) { renderer.zoomBy(-1.0) })
            .addAction(
                carContext.iconAction(if (state.manualCenter) R.drawable.ic_my_location else R.drawable.ic_search) {
                    if (state.manualCenter) {
                        session.followLocation()
                        state.location?.let(renderer::center)
                    } else {
                        renderer.cameraCenter?.let(session::searchAt)
                    }
                },
            )
            .build()
        val controller = MapController.Builder()
            .setMapActionStrip(mapStrip)
            .setPanModeListener { }
            .build()
        val list = ListTemplate.Builder()
            .setHeader(Header.Builder().setTitle(subtitle()).setStartHeaderAction(Action.APP_ICON).build())
        val items = itemList(placeMarkers = false)
        if (items == null) list.setLoading(true) else list.setSingleList(items)
        return MapWithContentTemplate.Builder()
            .setContentTemplate(list.build())
            .setActionStrip(ActionStrip.Builder().addAction(filterAction()).build())
            .setMapController(controller)
            .build()
    }

    /** Hosts below Car API 7: the host draws the map, so there is no pan or zoom. */
    private fun placeListTemplate(): Template {
        val b = PlaceListMapTemplate.Builder()
            .setTitle(subtitle())
            .setHeaderAction(Action.APP_ICON)
            .setActionStrip(ActionStrip.Builder().addAction(filterAction()).build())
            .setCurrentLocationEnabled(state.locationPermission)
        val items = itemList(placeMarkers = true)
        if (items == null) b.setLoading(true) else b.setItemList(items)
        return b.build()
    }
}

/** Station details with the handover to a navigation app. */
class StationCarScreen(carContext: CarContext, private val hit: StationHit) : Screen(carContext) {
    override fun onGetTemplate(): Template {
        val s = hit.station
        val pane = Pane.Builder()
            .addRow(Row.Builder().setTitle(Format.power(s)).addText(Format.plugs(s)).build())
            .addRow(
                Row.Builder().setTitle(Format.free(s) ?: Format.availability(s.availability))
                    .addText("${s.pointCount?.let { "$it Ladepunkte" } ?: "Ladepunkte ${Format.UNKNOWN}"} · ${Format.status(s.status)}")
                    .build(),
            )
            .addRow(
                Row.Builder().setTitle(s.operator ?: "Betreiber ${Format.UNKNOWN}")
                    .addText(s.address.singleLine() ?: "Adresse ${Format.UNKNOWN}").build(),
            )
        hit.nearby.firstOrNull()?.let { near ->
            pane.addRow(
                Row.Builder().setTitle(near.poi.name ?: "Wanderweg")
                    .addText("${near.airlineMeters.toInt()} m Luftlinie, Fußweg nicht geprüft").build(),
            )
        }
        pane.addAction(
            Action.Builder().setTitle("Navigieren").setBackgroundColor(CarColor.PRIMARY)
                .setIcon(carContext.icon(R.drawable.ic_navigation))
                .setOnClickListener { carContext.navigateTo(hit) }.build(),
        )
        return PaneTemplate.Builder(pane.build())
            .setHeader(Header.Builder().setTitle(Format.title(s)).setStartHeaderAction(Action.BACK).build())
            .build()
    }
}
