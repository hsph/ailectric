package app.ailectric.ui

import android.content.ActivityNotFoundException
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.ailectric.core.filter.AvailabilityFilter
import app.ailectric.core.filter.HikingFilter
import app.ailectric.core.filter.NearbyPoi
import app.ailectric.core.filter.StationFilter
import app.ailectric.core.model.ConnectorType
import app.ailectric.core.model.CurrentType
import app.ailectric.core.model.PoiCatalog
import app.ailectric.core.search.StationHit
import app.ailectric.map.Navigation
import app.ailectric.search.SearchState
import java.util.Locale

@Composable
private fun Section(title: String) {
    Spacer(Modifier.height(16.dp))
    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun Info(label: String, value: String?) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, Modifier.weight(0.4f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value ?: Format.UNKNOWN, Modifier.weight(0.6f), style = MaterialTheme.typography.bodyMedium)
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun StationDetailSheet(hit: StationHit, onDismiss: () -> Unit) {
    val s = hit.station
    val context = LocalContext.current
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 20.dp).verticalScroll(rememberScrollState()).navigationBarsPadding()) {
            Text(Format.title(s), style = MaterialTheme.typography.headlineSmall)
            Text(hit.distance.label(), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            PrimaryAction("Navigation starten") {
                try {
                    context.startActivity(Navigation.phoneIntent(s.location, Format.title(s)))
                } catch (_: ActivityNotFoundException) {
                    Toast.makeText(context, "Keine Navigations-App installiert", Toast.LENGTH_LONG).show()
                }
            }
            Section("Station")
            Info("Betreiber", s.operator)
            Info("Adresse", s.address.singleLine())
            Info("Koordinaten", String.format(Locale.ROOT, "%.5f, %.5f", s.location.lat, s.location.lon))
            Info("Max. Leistung", s.maxPowerKw?.let { "${it.toInt()} kW" })
            Info("Ladepunkte", s.pointCount?.toString())
            Info("Belegung", Format.availability(s.availability))
            Info("Status", Format.status(s.status))
            Info("Öffnungszeiten", s.openingHours)
            Info("Preise", s.pricing)
            Info("Zugang", s.access)
            Info("Aktualisiert", s.lastUpdated?.take(10))
            if (s.devices.isNotEmpty()) {
                Section("Ladepunkte")
                val groups = s.connectors.groupingBy { it.type to it.powerKw }.eachCount()
                groups.forEach { (key, count) ->
                    val (type, power) = key
                    Info("$count × ${type.label}", power?.let { "${it.toInt()} kW" })
                }
            }
            if (hit.nearby.isNotEmpty()) {
                Section("Wandern in der Nähe")
                Text(
                    "Entfernungen sind Luftlinie. Ob der Weg zu Fuß erreichbar ist, wurde nicht geprüft.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary,
                )
                hit.nearby.take(12).forEach { TrailItem(it) }
            }
            Section("Datenquelle")
            Text(
                listOfNotNull(s.source.attribution, s.source.license).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun TrailItem(near: NearbyPoi) {
    val poi = near.poi
    val category = PoiCatalog.HIKING.firstOrNull { it.id == poi.categoryId }?.label ?: "Ziel"
    Column(Modifier.padding(vertical = 6.dp)) {
        Text(poi.name ?: category, style = MaterialTheme.typography.titleSmall)
        Text("$category · ${near.airlineMeters.toInt()} m Luftlinie", style = MaterialTheme.typography.bodySmall)
        poi.hiking?.let { h ->
            val facts = listOfNotNull(
                h.lengthMeters?.let { String.format(Locale.GERMANY, "%.1f km", it / 1000) + if (h.lengthDerived) " (gemessen)" else "" },
                h.ascentMeters?.let { "${it.toInt()} Hm" },
                h.estimatedMinutes?.let { "ca. ${it / 60} h ${it % 60} min" },
                h.roundTrip?.let { if (it) "Rundweg" else "Streckenwanderung" },
                "ausgeschildert".takeIf { h.signposted },
                h.difficulty, h.surface,
            )
            Text(facts.joinToString(" · ").ifBlank { "Keine Details in OSM" }, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FilterSheet(state: SearchState, onChange: (StationFilter) -> Unit, onDismiss: () -> Unit) {
    val f = state.filter
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 20.dp).verticalScroll(rememberScrollState()).navigationBarsPadding()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Filter", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                TextButton(onClick = { onChange(StationFilter()) }, enabled = !f.isDefault) { Text("Zurücksetzen") }
            }

            Section("Mindestleistung: ${f.minPowerKw?.let { "${it.toInt()} kW" } ?: "alle"}")
            Slider(
                value = (f.minPowerKw ?: 0.0).toFloat(), valueRange = 0f..400f,
                onValueChange = { onChange(f.copy(minPowerKw = it.toDouble().takeIf { v -> v >= 1 }?.let(Math::rint))) },
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StationFilter.POWER_PRESETS_KW.forEach { kw ->
                    FilterChip(
                        selected = f.minPowerKw == kw.toDouble(), label = { Text("$kw") },
                        onClick = { onChange(f.copy(minPowerKw = kw.toDouble().takeIf { it != f.minPowerKw })) },
                    )
                }
            }
            Section("Höchstleistung: ${f.maxPowerKw?.let { "${it.toInt()} kW" } ?: "keine Grenze"}")
            Slider(
                value = (f.maxPowerKw ?: 400.0).toFloat(), valueRange = 0f..400f,
                onValueChange = { onChange(f.copy(maxPowerKw = it.toDouble().takeIf { v -> v < 400 }?.let(Math::rint))) },
            )
            ToggleLine("Stationen ohne Leistungsangabe behalten", f.includeUnknownPower) { onChange(f.copy(includeUnknownPower = it)) }

            Section("Stecker")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ConnectorType.entries.forEach { t ->
                    FilterChip(t in f.connectorTypes, { onChange(f.copy(connectorTypes = f.connectorTypes.toggled(t))) }, { Text(t.label) })
                }
            }
            Section("Ladeart")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(CurrentType.AC, CurrentType.DC).forEach { c ->
                    FilterChip(c in f.currentTypes, { onChange(f.copy(currentTypes = f.currentTypes.toggled(c))) }, { Text(c.name) })
                }
            }
            val operators = (state.result?.operators.orEmpty() + f.operators).distinct().sorted()
            if (operators.isNotEmpty()) {
                Section("Betreiber")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    operators.take(40).forEach { o ->
                        FilterChip(o in f.operators, { onChange(f.copy(operators = f.operators.toggled(o))) }, { Text(o) })
                    }
                }
            }
            Section("Verfügbarkeit")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(AvailabilityFilter.ALL to "Alle", AvailabilityFilter.FREE to "Frei", AvailabilityFilter.UNKNOWN to "Unbekannt").forEach { (v, l) ->
                    FilterChip(f.availability == v, { onChange(f.copy(availability = v)) }, { Text(l) })
                }
            }
            Text(
                "Die aktuellen Datenquellen liefern keine Live-Belegung. „Frei“ zeigt daher keine Stationen.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary,
            )
            Section("Suchradius: ${f.radiusKm.toInt()} km")
            Slider(
                value = f.radiusKm.toFloat(), valueRange = 5f..150f,
                onValueChange = { onChange(f.copy(radiusKm = Math.rint(it.toDouble()))) },
            )

            HorizontalDivider(Modifier.padding(top = 12.dp))
            HikingSection(f, onChange)
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ToggleLine(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked, onChange)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HikingSection(f: StationFilter, onChange: (StationFilter) -> Unit) {
    Section("Wandern")
    ToggleLine("Nur Stationen mit Wanderzielen", f.hiking != null) {
        onChange(f.copy(hiking = if (it) HikingFilter() else null))
    }
    val h = f.hiking ?: return
    fun set(next: HikingFilter) = onChange(f.copy(hiking = next))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        HikingFilter.DISTANCE_PRESETS_M.forEach { m ->
            FilterChip(h.withinMeters == m, { set(h.copy(withinMeters = m)) }, { Text(if (m < 1000) "$m m" else "${m / 1000} km") })
        }
    }
    ToggleLine("Nur ausgeschilderte Routen", h.signpostedOnly) { set(h.copy(signpostedOnly = it)) }
    ToggleLine("Nur Rundwege", h.roundTripOnly) { set(h.copy(roundTripOnly = it)) }
    Section("Mindestlänge: ${h.minLengthKm?.let { "${it.toInt()} km" } ?: "keine"}")
    Slider(
        value = (h.minLengthKm ?: 0.0).toFloat(), valueRange = 0f..30f,
        onValueChange = { set(h.copy(minLengthKm = Math.rint(it.toDouble()).takeIf { v -> v >= 1 })) },
    )
    Section("Maximallänge: ${h.maxLengthKm?.let { "${it.toInt()} km" } ?: "keine"}")
    Slider(
        value = (h.maxLengthKm ?: 30.0).toFloat(), valueRange = 1f..30f,
        onValueChange = { set(h.copy(maxLengthKm = Math.rint(it.toDouble()).takeIf { v -> v < 30 })) },
    )
    Section("Max. Höhenmeter: ${h.maxAscentMeters?.let { "${it.toInt()} m" } ?: "keine Grenze"}")
    Slider(
        value = (h.maxAscentMeters ?: 2000.0).toFloat(), valueRange = 0f..2000f,
        onValueChange = { set(h.copy(maxAscentMeters = (Math.rint(it / 50.0) * 50).takeIf { v -> v < 2000 })) },
    )
    Text(
        "Wege ohne Längen- oder Höhenangabe in OSM fallen bei aktivem Kriterium heraus.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
