package app.ailectric

import android.Manifest
import android.content.Context
import androidx.car.app.model.Action
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.PlaceListMapTemplate
import androidx.car.app.navigation.model.MapWithContentTemplate
import androidx.car.app.testing.TestCarContext
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.core.app.ApplicationProvider
import app.ailectric.car.CarMapRenderer
import app.ailectric.car.FilterCarScreen
import app.ailectric.car.MainCarScreen
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
import app.ailectric.core.model.Station
import app.ailectric.core.search.Freshness
import app.ailectric.core.search.SearchResult
import app.ailectric.core.search.StationHit
import app.ailectric.search.SearchState
import app.ailectric.ui.Format
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

    private fun home(hits: List<StationHit>): MainCarScreen {
        val owner = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry.createUnsafe(this)
        }
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
        val list = template.contentTemplate as ListTemplate
        assertThat(list.singleList!!.items.size).isAtMost(12)
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
