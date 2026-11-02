package app.ailectric.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.Header
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.model.Toggle
import app.ailectric.app
import app.ailectric.core.filter.HikingFilter
import app.ailectric.core.filter.StationFilter
import app.ailectric.core.model.ConnectorType
import app.ailectric.core.model.CurrentType

/** Few, large choices for driving; the full filter lives on the phone and is shared with it. */
class FilterCarScreen(carContext: CarContext) : Screen(carContext) {
    private val session = carContext.app.session
    private val filter get() = session.state.value.filter

    private fun update(next: StationFilter) {
        session.setFilter(next)
        invalidate()
    }

    private fun nextPower(current: Double?): Double? {
        val steps = listOf<Double?>(null, 50.0, 150.0, 250.0, 350.0)
        return steps[(steps.indexOf(current).coerceAtLeast(0) + 1) % steps.size]
    }

    private fun toggleRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) =
        Row.Builder().setTitle(title).setToggle(Toggle.Builder(onChange).setChecked(checked).build()).build()

    override fun onGetTemplate(): Template {
        val f = filter
        val items = ItemList.Builder()
            .addItem(
                Row.Builder().setTitle("Mindestleistung: ${f.minPowerKw?.let { "${it.toInt()} kW" } ?: "alle"}")
                    .addText("Tippen zum Wechseln")
                    .setOnClickListener { update(f.copy(minPowerKw = nextPower(f.minPowerKw))) }
                    .build(),
            )
            .addItem(
                toggleRow("Nur CCS", f.connectorTypes == setOf(ConnectorType.CCS2)) {
                    update(filter.copy(connectorTypes = if (it) setOf(ConnectorType.CCS2) else emptySet()))
                },
            )
            .addItem(
                toggleRow("Nur DC", f.currentTypes == setOf(CurrentType.DC)) {
                    update(filter.copy(currentTypes = if (it) setOf(CurrentType.DC) else emptySet()))
                },
            )
            .addItem(
                toggleRow("Wanderweg in der Nähe", f.hiking != null) {
                    update(filter.copy(hiking = if (it) HikingFilter() else null))
                },
            )
            .addItem(
                Row.Builder().setTitle("Filter zurücksetzen")
                    .setOnClickListener { update(StationFilter()) }.build(),
            )
            .build()
        return ListTemplate.Builder()
            .setHeader(Header.Builder().setTitle("Filter").setStartHeaderAction(Action.BACK).build())
            .setSingleList(items)
            .build()
    }
}
