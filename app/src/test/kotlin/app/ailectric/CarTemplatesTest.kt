package app.ailectric

import android.Manifest
import android.content.Context
import androidx.car.app.model.Action
import androidx.car.app.model.GridItem
import androidx.car.app.model.GridTemplate
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.PlaceListMapTemplate
import androidx.car.app.model.Row
import androidx.car.app.navigation.model.MapWithContentTemplate
import androidx.car.app.testing.ScreenController
import androidx.car.app.testing.TestCarContext
import androidx.car.app.testing.TestScreenManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.core.app.ApplicationProvider
import app.ailectric.car.CarMapRenderer
import app.ailectric.car.FilterCarScreen
import app.ailectric.car.MainCarScreen
import app.ailectric.car.OptionCarScreen
import app.ailectric.car.PinchZoom
import app.ailectric.car.SettingsCarScreen
import app.ailectric.car.StationCarScreen
import app.ailectric.core.geo.Distance
import app.ailectric.core.geo.DistanceKind
import app.ailectric.core.geo.GeoPoint
import app.ailectric.core.model.Address
import app.ailectric.core.model.ConnectorType
import app.ailectric.core.model.Connector
import app.ailectric.core.model.ChargingDevice
import app.ailectric.core.model.Availability
import app.ailectric.core.model.DataSource
import app.ailectric.core.model.OperationalStatus
import app.ailectric.core.model.Station
import app.ailectric.core.search.Freshness
import app.ailectric.core.search.SearchResult
import app.ailectric.core.search.StationHit
import app.ailectric.data.CarSettings
import app.ailectric.data.MapSettings
import app.ailectric.data.CarTileSize
import app.ailectric.search.SearchState
import app.ailectric.ui.Format
import app.ailectric.ui.PowerGroup
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = TestApplication::class)
class CarTemplatesTest {
    private val context = TestCarContext.createCarContext(ApplicationProvider.getApplicationContext<Context>())

    private val sparse = StationHit(
        Station(
            id = "t:1", name = null, operator = null, address = Address(), location = GeoPoint(48.0, 11.0),
            devices = emptyList(), source = DataSource("Test", "Test"),
        ),
        Distance(1000.0, DistanceKind.AIRLINE),
    )

    @Test fun `station detail builds for a record with no optional data and invents nothing`() {
        val template = StationCarScreen(context, sparse).onGetTemplate() as PaneTemplate
        val rows = template.pane.rows
        val text = rows.joinToString { r -> r.title.toString() + r.texts.joinToString() }
        assertThat(text).contains("Leistung unbekannt")
        assertThat(text).contains("Belegung unbekannt")
        assertThat(text).contains("Adresse unbekannt")
        assertThat(template.pane.actions).hasSize(1)
    }

    @Test fun `operational status shows only when it is clear`() {
        fun text(status: OperationalStatus): String {
            val hit = sparse.copy(station = sparse.station.copy(status = status))
            val rows = (StationCarScreen(context, hit).onGetTemplate() as PaneTemplate).pane.rows
            return rows.joinToString { r -> r.texts.joinToString() }
        }
        assertThat(text(OperationalStatus.UNKNOWN)).doesNotContain("Betriebsstatus")
        assertThat(text(OperationalStatus.UNKNOWN)).doesNotContain("·")
        assertThat(text(OperationalStatus.NOT_OPERATIONAL)).contains("nicht in Betrieb")
    }

    @Test fun `the grid collapses to its closest station and back`() {
        CarAppApiLevelTestSupport.set(context, 7)
        val screen = home(List(5) { sparse.copy(station = sparse.station.copy(id = "t:$it")) })
        fun grid() = (screen.onGetTemplate() as MapWithContentTemplate).contentTemplate as GridTemplate
        assertThat(grid().singleList!!.items).hasSize(5)
        grid().header!!.endHeaderActions.single().onClickDelegate!!.sendClick(NoOpCallback)
        assertThat(grid().singleList!!.items).hasSize(1)
        grid().header!!.endHeaderActions.single().onClickDelegate!!.sendClick(NoOpCallback)
        assertThat(grid().singleList!!.items).hasSize(5)
    }

    @Test fun `tiles show distance and name and the header says it is airline`() {
        CarAppApiLevelTestSupport.set(context, 7)
        val grid = (home(listOf(sparse)).onGetTemplate() as MapWithContentTemplate).contentTemplate as GridTemplate
        val tile = grid.singleList!!.items.single() as GridItem
        assertThat(tile.title.toString()).isEqualTo("1,0 km")
        assertThat(tile.text.toString()).isEqualTo("Ladestation")
        assertThat(tile.image).isNotNull()
        assertThat(grid.header!!.title.toString()).isEqualTo("1 Stationen · Luftlinie")
    }

    @Test fun `tiles name the operator first and don't repeat it`() {
        val named = sparse.station.copy(name = "Laatbankstraat 18", operator = "Vattenfall InCharge")
        assertThat(Format.operatorAndTitle(named)).isEqualTo("Vattenfall InCharge · Laatbankstraat 18")
        val onlyOperator = sparse.station.copy(operator = "Vattenfall InCharge")
        assertThat(Format.operatorAndTitle(onlyOperator)).isEqualTo("Vattenfall InCharge")
        assertThat(Format.operatorAndTitle(sparse.station)).isEqualTo("Ladestation")
    }

    @Test fun `the tile size setting reaches the grid and medium is the default`() {
        CarAppApiLevelTestSupport.set(context, 7)
        val screen = home(listOf(sparse))
        fun grid() = (screen.onGetTemplate() as MapWithContentTemplate).contentTemplate as GridTemplate
        assertThat(grid().itemSize).isEqualTo(GridTemplate.ITEM_SIZE_MEDIUM)
        screen.state = screen.state.copy(car = CarSettings(tileSize = CarTileSize.LARGE))
        assertThat(grid().itemSize).isEqualTo(GridTemplate.ITEM_SIZE_LARGE)
    }

    @Test fun `the legend covers every group and ends with the unknown one`() {
        assertThat(PowerGroup.LEGEND).containsExactlyElementsIn(PowerGroup.entries)
        assertThat(PowerGroup.LEGEND.last()).isEqualTo(PowerGroup.UNKNOWN)
        assertThat(PowerGroup.LEGEND.map { it.label }).containsExactly("bis 22", "23–149", "150–299", "ab 300 kW", "?").inOrder()
    }

    @Test fun `home screen offers the filter and the settings`() {
        CarAppApiLevelTestSupport.set(context, 7)
        val template = home(listOf(sparse)).onGetTemplate() as MapWithContentTemplate
        assertThat(template.actionStrip!!.actions).hasSize(2)
    }

    @Test fun `filter offers the steps of the colour legend as a menu`() {
        assertThat(PowerGroup.FILTER_STEPS.map { it.filterLabel }).containsExactly("ab 23 kW", "ab 150 kW", "ab 300 kW").inOrder()
        val row = (FilterCarScreen(context).onGetTemplate() as ListTemplate).singleList!!.items.first() as Row
        assertThat(row.title.toString()).isEqualTo("Mindestleistung")
        assertThat(row.isBrowsable).isTrue()
    }

    @Test fun `car map starts plain`() {
        assertThat(CarSettings().map).isEqualTo(MapSettings())
    }

    @Test fun `settings list their values`() {
        val rows = (SettingsCarScreen(context).onGetTemplate() as ListTemplate).singleList!!.items.map { (it as Row) }
        assertThat(rows.map { it.title.toString() }).containsExactly("Kachelgröße", "Startzoom", "Zoom bei Auswahl", "Wanderwege (Waymarked)", "Wanderwege (OSM)", "Radwege").inOrder()
        assertThat(rows.first().texts.single().toString()).isEqualTo("Mittel")
        assertThat(rows[1].texts.single().toString()).isEqualTo("Stufe 13")
    }

    @Test fun `each zoom has five presets containing its default`() {
        val defaults = CarSettings()
        assertThat(CarSettings.START_ZOOM_PRESETS).hasSize(5)
        assertThat(CarSettings.FOCUS_ZOOM_PRESETS).hasSize(5)
        assertThat(CarSettings.START_ZOOM_PRESETS).contains(defaults.startZoom)
        assertThat(CarSettings.FOCUS_ZOOM_PRESETS).contains(defaults.focusZoom)
        assertThat(CarSettings.START_ZOOM_PRESETS.all { it in CarSettings.START_ZOOM_RANGE }).isTrue()
    }

    @Test fun `an option list marks the current value, reports the choice and closes`() {
        var chosen = -1
        val screen = OptionCarScreen(context, "Startzoom", listOf("a", "b", "c"), 1) { chosen = it }
        val screens = context.getCarService(TestScreenManager::class.java)
        ScreenController(SettingsCarScreen(context)).moveToState(Lifecycle.State.RESUMED)
        ScreenController(screen).moveToState(Lifecycle.State.RESUMED)
        val list = (screen.onGetTemplate() as ListTemplate).singleList!!
        assertThat(list.selectedIndex).isEqualTo(1)
        list.onSelectedDelegate!!.sendSelected(2, NoOpCallback)
        assertThat(chosen).isEqualTo(2)
        assertThat(screens.screensRemoved).contains(screen)
    }

    @Test fun `old data still says so in the header`() {
        CarAppApiLevelTestSupport.set(context, 7)
        val screen = home(listOf(sparse))
        screen.state = screen.state.copy(result = screen.state.result!!.copy(freshness = Freshness.STALE_OFFLINE))
        val grid = (screen.onGetTemplate() as MapWithContentTemplate).contentTemplate as GridTemplate
        assertThat(grid.header!!.title.toString()).contains("offline")
    }

    @Test fun `an empty result still builds a grid`() {
        CarAppApiLevelTestSupport.set(context, 7)
        val grid = (home(emptyList()).onGetTemplate() as MapWithContentTemplate).contentTemplate as GridTemplate
        assertThat(grid.singleList!!.noItemsMessage.toString()).contains("Keine Station")
    }

    @Test fun `tile figures never present missing values as data`() {
        val plug = Connector(ConnectorType.CCS2, powerKw = 22.08, availability = Availability.AVAILABLE)
        val busy = plug.copy(availability = Availability.NOT_FREE)
        val live = sparse.station.copy(devices = listOf(ChargingDevice(connectors = listOf(plug)), ChargingDevice(connectors = listOf(busy))))
        assertThat(Format.powerShort(live)).isEqualTo("22")
        assertThat(Format.freeShort(live)).isEqualTo("1/2")
        assertThat(Format.freeShort(sparse.station.copy(declaredPoints = 6))).isEqualTo("–/6")
        assertThat(Format.powerShort(sparse.station)).isEqualTo("?")
        assertThat(Format.freeShort(sparse.station)).isEqualTo("–/–")
    }

    @Test fun `speed groups follow the shown figure and tell unknown from slow`() {
        assertThat(PowerGroup.of(null)).isEqualTo(PowerGroup.UNKNOWN)
        assertThat(PowerGroup.of(11.04)).isEqualTo(PowerGroup.SLOW)
        assertThat(PowerGroup.of(22.08)).isEqualTo(PowerGroup.SLOW)
        assertThat(PowerGroup.of(50.0)).isEqualTo(PowerGroup.FAST)
        assertThat(PowerGroup.of(150.0)).isEqualTo(PowerGroup.RAPID)
        assertThat(PowerGroup.of(300.0)).isEqualTo(PowerGroup.ULTRA)
    }

    @Test fun `details collapse to one row and keep the navigation button`() {
        CarAppApiLevelTestSupport.set(context, 7)
        val screen = StationCarScreen(context, sparse, CarMapRenderer(context, owner.lifecycle))
        fun pane() = (screen.onGetTemplate() as MapWithContentTemplate).contentTemplate as PaneTemplate
        assertThat(pane().pane.rows.size).isGreaterThan(1)
        pane().header!!.endHeaderActions.single().onClickDelegate!!.sendClick(NoOpCallback)
        assertThat(pane().pane.rows).hasSize(1)
        assertThat(pane().pane.actions).hasSize(1)
    }

    @Test fun `free points show as a figure and only with live data`() {
        val plug = Connector(ConnectorType.TYPE2, availability = Availability.AVAILABLE)
        val busy = plug.copy(availability = Availability.NOT_FREE)
        val live = sparse.copy(
            station = sparse.station.copy(devices = listOf(ChargingDevice(connectors = listOf(plug)), ChargingDevice(connectors = listOf(busy)))),
        )
        val rows = (StationCarScreen(context, live).onGetTemplate() as PaneTemplate).pane.rows
        assertThat(rows.joinToString { it.title.toString() }).contains("1 von 2 frei")
        val unknown = (StationCarScreen(context, sparse).onGetTemplate() as PaneTemplate).pane.rows
        assertThat(unknown.joinToString { it.title.toString() }).doesNotContain("frei")
    }

    @Test fun `a pinch cannot run away while a double tap keeps its full step`() {
        var clock = 0L
        val pinch = PinchZoom { clock }
        assertThat(pinch.zoomDelta(2f)).isWithin(1e-6).of(1.0)
        assertThat(pinch.isPinching).isTrue()
        var total = 0.0
        repeat(60) {
            clock += 16
            total += pinch.zoomDelta(1.5f)
        }
        assertThat(total).isAtMost(6.0)
        assertThat(pinch.zoomDelta(0.1f)).isWithin(1e-6).of(-0.1)
        clock += 1000
        assertThat(pinch.isPinching).isFalse()
        assertThat(pinch.zoomDelta(0.5f)).isWithin(1e-6).of(-1.0)
    }

    @Test fun `a negative factor from the host zooms out instead of breaking the camera`() {
        assertThat(PinchZoom { 0L }.zoomDelta(-1f)).isWithin(1e-6).of(-1.0)
    }

    @Test fun `filter screen stays within the host's list limit`() {
        val template = FilterCarScreen(context).onGetTemplate() as ListTemplate
        assertThat(template.singleList!!.items.size).isAtMost(6)
    }

    @Test fun `navigation hands the coordinates to the host`() {
        val template = StationCarScreen(context, sparse).onGetTemplate() as PaneTemplate
        template.pane.actions.single().onClickDelegate!!.sendClick(NoOpCallback)
        val intent = context.startCarAppIntents.single()
        assertThat(intent.action).isEqualTo(androidx.car.app.CarContext.ACTION_NAVIGATE)
        assertThat(intent.data.toString()).startsWith("geo:48.0,11.0?q=48.0,11.0")
    }

    private val owner = object : LifecycleOwner {
        override val lifecycle = LifecycleRegistry.createUnsafe(this)
    }

    private fun home(hits: List<StationHit>): MainCarScreen {
        val screen = MainCarScreen(context, CarMapRenderer(context, owner.lifecycle))
        screen.state = SearchState(
            location = GeoPoint(48.0, 11.0), searchCenter = GeoPoint(48.0, 11.0), locationPermission = true,
            result = SearchResult(hits, emptyList(), Freshness.LIVE_FETCH, false, emptyList(), emptyList()),
        )
        return screen
    }

    @Test @Config(application = TestApplication::class)
    fun `home screen uses the app-drawn map with pan on Car API 7 and passes the host's constraints`() {
        CarAppApiLevelTestSupport.set(context, 7)
        val hits = List(30) { sparse.copy(station = sparse.station.copy(id = "t:$it")) }
        val template = home(hits).onGetTemplate() as MapWithContentTemplate
        assertThat(template.mapController!!.mapActionStrip!!.actions).contains(Action.PAN)
        assertThat(template.mapController!!.mapActionStrip!!.actions.size).isAtMost(4)
        val grid = template.contentTemplate as GridTemplate
        assertThat(grid.singleList!!.items.size).isAtMost(9)
    }

    @Test fun `details open beside the map where the list was`() {
        CarAppApiLevelTestSupport.set(context, 7)
        val template = StationCarScreen(context, sparse, CarMapRenderer(context, owner.lifecycle)).onGetTemplate()
        assertThat(template).isInstanceOf(MapWithContentTemplate::class.java)
        val map = template as MapWithContentTemplate
        assertThat(map.contentTemplate).isInstanceOf(PaneTemplate::class.java)
        assertThat(map.mapController).isNotNull()
    }

    @Test fun `details fill the screen on hosts without the map template`() {
        CarAppApiLevelTestSupport.set(context, 6)
        val template = StationCarScreen(context, sparse, CarMapRenderer(context, owner.lifecycle)).onGetTemplate()
        assertThat(template).isInstanceOf(PaneTemplate::class.java)
    }

    @Test fun `older hosts fall back to the host-rendered map`() {
        CarAppApiLevelTestSupport.set(context, 6)
        val template = home(listOf(sparse)).onGetTemplate()
        assertThat(template).isInstanceOf(PlaceListMapTemplate::class.java)
    }

    @Test fun `no location and no search asks for permission`() {
        val owner = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry.createUnsafe(this)
        }
        val template = MainCarScreen(context, CarMapRenderer(context, owner.lifecycle)).onGetTemplate()
        assertThat(template).isInstanceOf(MessageTemplate::class.java)
    }

    @Test fun `format never presents missing values as data`() {
        assertThat(Format.power(sparse.station)).isEqualTo("Leistung unbekannt")
        assertThat(Format.plugs(sparse.station)).isEqualTo("Stecker unbekannt")
    }
}

private object NoOpCallback : androidx.car.app.OnDoneCallback

class TestApplication : AilectricApplication() {
    override fun initMapEngine() = Unit
}

/** CarContext has no public setter for the negotiated API level. */
private object CarAppApiLevelTestSupport {
    fun set(context: androidx.car.app.CarContext, level: Int) {
        val field = androidx.car.app.CarContext::class.java.getDeclaredField("mCarAppApiLevel")
        field.isAccessible = true
        field.setInt(context, level)
    }
}
