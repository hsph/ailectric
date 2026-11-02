package app.ailectric

import android.Manifest
import android.content.Context
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.PaneTemplate
import androidx.car.app.testing.TestCarContext
import androidx.test.core.app.ApplicationProvider
import app.ailectric.car.FilterCarScreen
import app.ailectric.car.StationCarScreen
import app.ailectric.core.geo.Distance
import app.ailectric.core.geo.DistanceKind
import app.ailectric.core.geo.GeoPoint
import app.ailectric.core.model.Address
import app.ailectric.core.model.DataSource
import app.ailectric.core.model.Station
import app.ailectric.core.search.StationHit
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

    @Test fun `format never presents missing values as data`() {
        assertThat(Format.power(sparse.station)).isEqualTo("Leistung unbekannt")
        assertThat(Format.plugs(sparse.station)).isEqualTo("Stecker unbekannt")
    }
}

private object NoOpCallback : androidx.car.app.OnDoneCallback

class TestApplication : AilectricApplication() {
    override fun initMapEngine() = Unit
}
