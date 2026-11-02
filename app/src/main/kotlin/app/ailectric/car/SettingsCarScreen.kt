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
import app.ailectric.data.CarSettings
import app.ailectric.data.CarTileSize
import kotlin.math.abs

private val ZOOM_NAMES = listOf("Sehr weit", "Weit", "Mittel", "Nah", "Sehr nah")

/** The car has no sliders, so each value opens a short list of presets. Shared with the phone. */
class SettingsCarScreen(carContext: CarContext) : Screen(carContext) {
    private val session = carContext.app.session
    private val car get() = session.state.value.car

    private fun save(next: CarSettings) {
        session.saveSettings(carContext.app.settings.copy(car = next))
        invalidate()
    }

    private fun row(title: String, value: String, onClick: () -> Unit) =
        Row.Builder().setTitle(title).addText(value).setBrowsable(true).setOnClickListener(onClick).build()

    /** A value set on the phone can lie between presets; the closest one is marked. */
    private fun chooseZoom(title: String, presets: List<Int>, current: Int, onChoose: (Int) -> Unit) {
        val options = presets.mapIndexed { i, zoom -> "${ZOOM_NAMES[i]} (Stufe $zoom)" }
        val closest = presets.indices.minBy { abs(presets[it] - current) }
        screenManager.push(OptionCarScreen(carContext, title, options, closest) { onChoose(presets[it]) })
    }

    override fun onGetTemplate(): Template {
        val c = car
        val items = ItemList.Builder()
            .addItem(
                row("Kachelgröße", c.tileSize.label) {
                    val sizes = CarTileSize.entries
                    screenManager.push(
                        OptionCarScreen(carContext, "Kachelgröße", sizes.map { it.label }, c.tileSize.ordinal) {
                            save(car.copy(tileSize = sizes[it]))
                        },
                    )
                },
            )
            .addItem(
                row("Startzoom", "Stufe ${c.startZoom}") {
                    chooseZoom("Startzoom", CarSettings.START_ZOOM_PRESETS, c.startZoom) { save(car.copy(startZoom = it)) }
                },
            )
            .addItem(
                row("Zoom bei Auswahl", "Stufe ${c.focusZoom}") {
                    chooseZoom("Zoom bei Auswahl", CarSettings.FOCUS_ZOOM_PRESETS, c.focusZoom) { save(car.copy(focusZoom = it)) }
                },
            )
            .addItem(
                row("Karte", c.map.base.label) {
                    val bases = CarSettings.MAP_BASES
                    screenManager.push(
                        OptionCarScreen(carContext, "Karte", bases.map { it.label }, bases.indexOf(c.map.base).coerceAtLeast(0)) {
                            save(car.copy(map = car.map.copy(base = bases[it])))
                        },
                    )
                },
            )
            .addItem(
                Row.Builder().setTitle("Wanderwege").addText("Bei näherem Zoom")
                    .setToggle(
                        Toggle.Builder { save(car.copy(map = car.map.copy(hikingTrails = it))) }
                            .setChecked(c.map.hikingTrails).build(),
                    ).build(),
            )
            .build()
        return ListTemplate.Builder()
            .setHeader(Header.Builder().setTitle("Einstellungen").setStartHeaderAction(Action.BACK).build())
            .setSingleList(items)
            .build()
    }
}

/** The nearest thing to a dropdown here: a selectable list that closes on the choice. */
class OptionCarScreen(
    carContext: CarContext,
    private val title: String,
    private val options: List<String>,
    private val selected: Int,
    private val onChoose: (Int) -> Unit,
) : Screen(carContext) {
    override fun onGetTemplate(): Template {
        val items = ItemList.Builder()
        options.forEach { items.addItem(Row.Builder().setTitle(it).build()) }
        items.setSelectedIndex(selected).setOnSelectedListener {
            // First back on the calling screen: it can only redraw itself once it is in front.
            screenManager.pop()
            onChoose(it)
        }
        return ListTemplate.Builder()
            .setHeader(Header.Builder().setTitle(title).setStartHeaderAction(Action.BACK).build())
            .setSingleList(items.build())
            .build()
    }
}
