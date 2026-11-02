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
import androidx.car.app.model.GridItem
import androidx.car.app.model.GridTemplate
import androidx.car.app.model.ItemList
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
import app.ailectric.core.search.Freshness
import app.ailectric.core.search.StationHit
import app.ailectric.data.CarTileSize
import app.ailectric.map.Navigation
import app.ailectric.search.SearchState
import app.ailectric.ui.Format
import kotlinx.coroutines.launch

private const val MAP_TEMPLATE_API = 7
private const val FALLBACK_LIST_LIMIT = 6
/** Three by three, as far as a host shows that; also keeps the tile bitmaps within one binder call. */
private const val MAX_GRID_ITEMS = 9

internal fun CarContext.icon(res: Int) = CarIcon.Builder(IconCompat.createWithResource(this, res)).build()

internal fun CarContext.iconAction(res: Int, onClick: () -> Unit) =
    Action.Builder().setIcon(icon(res)).setOnClickListener(onClick).build()

internal fun CarContext.navigateTo(hit: StationHit) {
    val intent = Intent(CarContext.ACTION_NAVIGATE, Navigation.geoUri(hit.station.location, Format.title(hit.station)))
    startCarApp(intent)
}

/** The host can't hide the panel beside the map, so collapsing leaves a single row. */
internal fun CarContext.collapseAction(collapsed: Boolean, onToggle: () -> Unit) =
    iconAction(if (collapsed) R.drawable.ic_expand_more else R.drawable.ic_expand_less, onToggle)

/** Pan and zoom, the same on every screen that shows the map. */
internal fun CarContext.mapController(renderer: CarMapRenderer, extra: Action? = null): MapController {
    val strip = ActionStrip.Builder()
        .addAction(Action.PAN)
        .addAction(iconAction(R.drawable.ic_add) { renderer.zoomBy(1.0) })
        .addAction(iconAction(R.drawable.ic_remove) { renderer.zoomBy(-1.0) })
    extra?.let(strip::addAction)
    return MapController.Builder().setMapActionStrip(strip.build())
        .setPanModeListener { renderer.holdPosition() }.build()
}

/** The car's list-and-map home screen. See docs/ANDROID_AUTO.md for why it looks this way. */
class MainCarScreen(carContext: CarContext, private val renderer: CarMapRenderer) : Screen(carContext) {
    private val session = carContext.app.session
    @VisibleForTesting internal var state = SearchState()
    private var collapsed = false

    init {
        renderer.onStationClicked = { id -> state.result?.hits?.firstOrNull { it.station.id == id }?.let(::openDetail) }
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) {
                lifecycleScope.launch {
                    repeatOnLifecycle(Lifecycle.State.STARTED) {
                        session.state.collect { s ->
                            val movedCenter = s.searchCenter != state.searchCenter
                            state = s
                            renderer.apply(s.car)
                            renderer.show(s.result?.hits.orEmpty(), s.location)
                            if (movedCenter && !s.manualCenter) s.searchCenter?.let(renderer::center)
                            invalidate()
                        }
                    }
                }
            }

            override fun onResume(owner: LifecycleOwner) = renderer.unfocus()
        })
    }

    private fun openDetail(hit: StationHit) {
        renderer.focus(hit.station)
        screenManager.push(StationCarScreen(carContext, hit, renderer))
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

    private fun gridLimit(): Int =
        carContext.getCarService(ConstraintManager::class.java)
            .getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_GRID)
            .coerceIn(1, MAX_GRID_ITEMS)

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

    private fun settingsAction() = carContext.iconAction(R.drawable.ic_settings) {
        screenManager.push(SettingsCarScreen(carContext))
    }

    /** The tiles have no room for "Luftlinie" per distance, so the header says it once. */
    private fun gridTitle(): String {
        val r = state.result ?: return subtitle()
        val lead = if (r.freshness == Freshness.STALE_OFFLINE) Format.freshness(r.freshness) else "${r.hits.size} Stationen"
        return "$lead · Luftlinie"
    }

    private fun gridItems(): ItemList? {
        val hits = state.result?.hits?.take(if (collapsed) 1 else gridLimit()) ?: return null
        val builder = ItemList.Builder()
        if (hits.isEmpty()) builder.setNoItemsMessage(state.error ?: "Keine Station passt zu den Filtern")
        hits.forEach { hit ->
            builder.addItem(
                GridItem.Builder()
                    .setImage(ChargerTile.icon(hit.station), GridItem.IMAGE_TYPE_LARGE)
                    .setTitle(hit.distance.value())
                    .setText(Format.operatorAndTitle(hit.station))
                    .setOnClickListener { openDetail(hit) }
                    .build(),
            )
        }
        return builder.build()
    }

    private fun placeItems(): ItemList? {
        val hits = state.result?.hits?.take(listLimit()) ?: return null
        val builder = ItemList.Builder()
        if (hits.isEmpty()) builder.setNoItemsMessage(state.error ?: "Keine Station passt zu den Filtern")
        hits.forEachIndexed { index, hit -> builder.addItem(row(hit, index)) }
        return builder.build()
    }

    private fun row(hit: StationHit, index: Int): Row {
        val s = hit.station
        return Row.Builder()
            .setTitle(Format.title(s))
            .addText(distanceLine(hit))
            .apply { Format.free(s)?.let(::addText) }
            .setBrowsable(true)
            .setOnClickListener { openDetail(hit) }
            .setMetadata(
                Metadata.Builder().setPlace(
                    Place.Builder(CarLocation.create(s.location.lat, s.location.lon))
                        .setMarker(PlaceMarker.Builder().setLabel("${index + 1}").build())
                        .build(),
                ).build(),
            )
            .build()
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
        val searchAction = carContext.iconAction(if (state.manualCenter) R.drawable.ic_my_location else R.drawable.ic_search) {
            if (state.manualCenter) {
                session.followLocation()
                state.location?.let(renderer::center)
            } else {
                renderer.cameraCenter?.let(session::searchAt)
            }
        }
        val toggle = carContext.collapseAction(collapsed) {
            collapsed = !collapsed
            invalidate()
        }
        val size = when (state.car.tileSize) {
            CarTileSize.SMALL -> GridTemplate.ITEM_SIZE_SMALL
            CarTileSize.MEDIUM -> GridTemplate.ITEM_SIZE_MEDIUM
            CarTileSize.LARGE -> GridTemplate.ITEM_SIZE_LARGE
        }
        val grid = GridTemplate.Builder().setItemSize(size).setHeader(
            Header.Builder().setTitle(gridTitle()).setStartHeaderAction(Action.APP_ICON).addEndHeaderAction(toggle).build(),
        )
        val items = gridItems()
        if (items == null) grid.setLoading(true) else grid.setSingleList(items)
        return MapWithContentTemplate.Builder()
            .setContentTemplate(grid.build())
            .setActionStrip(ActionStrip.Builder().addAction(filterAction()).addAction(settingsAction()).build())
            .setMapController(carContext.mapController(renderer, searchAction))
            .build()
    }

    /** Hosts below Car API 7: the host draws the map, so there is no pan or zoom. */
    private fun placeListTemplate(): Template {
        val b = PlaceListMapTemplate.Builder()
            .setTitle(subtitle())
            .setHeaderAction(Action.APP_ICON)
            .setActionStrip(ActionStrip.Builder().addAction(filterAction()).build())
            .setCurrentLocationEnabled(state.locationPermission)
        val items = placeItems()
        if (items == null) b.setLoading(true) else b.setItemList(items)
        return b.build()
    }
}

/** Station details with the handover to a navigation app, beside the map when the host allows it. */
class StationCarScreen(
    carContext: CarContext,
    private val hit: StationHit,
    private val renderer: CarMapRenderer? = null,
) : Screen(carContext) {
    private var collapsed = false

    override fun onGetTemplate(): Template {
        val s = hit.station
        val map = renderer?.takeIf { carContext.carAppApiLevel >= MAP_TEMPLATE_API }
        val pane = Pane.Builder()
            .addRow(Row.Builder().setTitle(Format.power(s)).addText(Format.plugs(s)).build())
        if (map == null || !collapsed) addDetailRows(pane)
        pane.addAction(
            Action.Builder().setTitle("Navigieren").setBackgroundColor(CarColor.PRIMARY)
                .setIcon(carContext.icon(R.drawable.ic_navigation))
                .setOnClickListener { carContext.navigateTo(hit) }.build(),
        )
        val header = Header.Builder().setTitle(Format.title(s)).setStartHeaderAction(Action.BACK)
        if (map != null) {
            header.addEndHeaderAction(
                carContext.collapseAction(collapsed) {
                    collapsed = !collapsed
                    invalidate()
                },
            )
        }
        val details = PaneTemplate.Builder(pane.build()).setHeader(header.build()).build()
        if (map == null) return details
        return MapWithContentTemplate.Builder()
            .setContentTemplate(details)
            .setMapController(carContext.mapController(map))
            .build()
    }

    private fun addDetailRows(pane: Pane.Builder) {
        val s = hit.station
        val points = s.pointCount?.let { "$it Ladepunkte" } ?: "Ladepunkte ${Format.UNKNOWN}"
        pane.addRow(
            Row.Builder().setTitle(Format.free(s) ?: Format.availability(s.availability))
                .addText(listOfNotNull(points, Format.status(s.status)).joinToString(" · "))
                .build(),
        )
        pane.addRow(
            Row.Builder().setTitle(s.operator ?: "Betreiber ${Format.UNKNOWN}")
                .addText(s.address.singleLine() ?: "Adresse ${Format.UNKNOWN}").build(),
        )
        hit.nearby.firstOrNull()?.let { near ->
            pane.addRow(
                Row.Builder().setTitle(near.poi.name ?: "Wanderweg")
                    .addText("${near.airlineMeters.toInt()} m Luftlinie, Fußweg nicht geprüft").build(),
            )
        }
    }
}
