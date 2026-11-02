package app.ailectric.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.ailectric.app
import app.ailectric.core.vehicle.VehicleProfile
import app.ailectric.search.SearchSession

@Composable
fun SettingsScreen(session: SearchSession, onClose: () -> Unit) {
    val app = LocalContext.current.app
    val saved = remember { app.settings }
    var key by rememberSaveable { mutableStateOf(saved.ocmApiKey) }
    var overpass by rememberSaveable { mutableStateOf(saved.overpassEndpoint) }
    var make by rememberSaveable { mutableStateOf(saved.vehicle?.make.orEmpty()) }
    var model by rememberSaveable { mutableStateOf(saved.vehicle?.model.orEmpty()) }
    var battery by rememberSaveable { mutableStateOf(saved.vehicle?.batteryKwh?.toString().orEmpty()) }
    var maxCharge by rememberSaveable { mutableStateOf(saved.vehicle?.maxChargeKw?.toString().orEmpty()) }
    var consumption by rememberSaveable { mutableStateOf(saved.vehicle?.consumptionKwhPer100Km?.toString().orEmpty()) }

    val close = {
        fun number(s: String) = s.replace(',', '.').toDoubleOrNull()
        val vehicle = VehicleProfile(
            make.ifBlank { null }, model.ifBlank { null }, number(battery), number(maxCharge),
            consumptionKwhPer100Km = number(consumption),
        ).takeIf { it != VehicleProfile() }
        val keyChanged = key.trim() != saved.ocmApiKey
        session.saveSettings(app.settings.copy(ocmApiKey = key.trim(), overpassEndpoint = overpass.trim(), vehicle = vehicle))
        if (keyChanged) session.refresh()
        onClose()
    }
    BackHandler(onBack = close)

    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.safeDrawingPadding().padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = close) { Icon(Icons.AutoMirrored.Default.ArrowBack, "Zurück") }
                Text("Einstellungen", style = MaterialTheme.typography.titleLarge)
            }
            Heading("Datenquellen")
            OutlinedTextField(
                key, { key = it }, Modifier.fillMaxWidth(), label = { Text("Open-Charge-Map-API-Schlüssel") },
                singleLine = true, visualTransformation = PasswordVisualTransformation(),
                supportingText = { Text("Kostenlos auf openchargemap.org. Ohne Schlüssel kommen die Stationen aus OpenStreetMap, dort fehlen oft Leistungsangaben.") },
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                overpass, { overpass = it }, Modifier.fillMaxWidth(), label = { Text("Eigener Overpass-Server (optional)") },
                singleLine = true, supportingText = { Text("Wird vor den öffentlichen Servern versucht, z. B. auf TrueNAS.") },
            )
            Heading("Fahrzeug (optional)")
            Text("Noch ohne Einfluss auf die Suche. Vorbereitet für die reichweitenabhängige Suche.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(make, { make = it }, Modifier.fillMaxWidth(), label = { Text("Hersteller") }, singleLine = true)
            OutlinedTextField(model, { model = it }, Modifier.fillMaxWidth(), label = { Text("Modell") }, singleLine = true)
            OutlinedTextField(battery, { battery = it }, Modifier.fillMaxWidth(), label = { Text("Akkukapazität (kWh)") }, singleLine = true)
            OutlinedTextField(maxCharge, { maxCharge = it }, Modifier.fillMaxWidth(), label = { Text("Max. Ladeleistung (kW)") }, singleLine = true)
            OutlinedTextField(consumption, { consumption = it }, Modifier.fillMaxWidth(), label = { Text("Verbrauch (kWh/100 km)") }, singleLine = true)
            Heading("Quellen und Lizenzen")
            Text(
                "Ladestationen: Open Charge Map (CC BY 4.0, importierte Daten je Datenanbieter, siehe Stationsdetails) " +
                    "und OpenStreetMap-Mitwirkende (ODbL 1.0).\n" +
                    "Wanderwege und POIs: © OpenStreetMap-Mitwirkende (ODbL 1.0).\n" +
                    "Karte: OpenFreeMap © OpenMapTiles, Daten von OpenStreetMap. Darstellung mit MapLibre.\n" +
                    "Einstellungen und Standort bleiben auf dem Gerät. Für die Suche werden Koordinaten an die genannten Dienste gesendet.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Heading(text: String) {
    Spacer(Modifier.height(20.dp))
    Text(text, style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(6.dp))
}
