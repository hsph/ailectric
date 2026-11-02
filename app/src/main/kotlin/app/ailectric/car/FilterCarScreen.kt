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
import app.ailectric.ui.PowerGroup

/** Few, large choices for driving; the full filter lives on the phone and is shared with it. */
class FilterCarScreen(carContext: CarContext) : Screen(carContext) {
    private val session = carContext.app.session
    private val filter get() = session.state.value.filter

    private fun update(next: StationFilter) {
        session.setFilter(next)
        invalidate()
    }

    /** A value set by an older version can lie between the steps; the one below it is marked. */
    private fun choosePower(current: Double?) {
        val steps = PowerGroup.FILTER_STEPS
        val selected = steps.indexOfLast { it.fromKw <= (current ?: 0.0) } + 1
        screenManager.push(
            OptionCarScreen(carContext, "Mindestleistung", listOf("Alle") + steps.map { it.filterLabel }, selected) {
                update(filter.copy(minPowerKw = steps.getOrNull(it - 1)?.fromKw))
            },
        )
    }

    private fun toggleRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) =
        Row.Builder().setTitle(title).setToggle(Toggle.Builder(onChange).setChecked(checked).build()).build()

    override fun onGetTemplate(): Template {
        val f = filter
        val items = ItemList.Builder()
            .addItem(
                Row.Builder().setTitle("Mindestleistung").addText(f.minPowerKw?.let { "ab ${it.toInt()} kW" } ?: "Alle")
                    .setBrowsable(true).setOnClickListener { choosePower(f.minPowerKw) }
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
