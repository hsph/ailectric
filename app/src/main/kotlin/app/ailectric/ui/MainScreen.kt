package app.ailectric.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ElectricBolt
import androidx.compose.material.icons.filled.Hiking
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.ailectric.core.filter.HikingFilter
import app.ailectric.core.filter.StationFilter
import app.ailectric.core.model.ConnectorType
import app.ailectric.core.model.CurrentType
import app.ailectric.core.search.StationHit
import app.ailectric.search.SearchSession
import app.ailectric.search.SearchState

private enum class ViewMode(val label: String) { MAP("Karte"), SPLIT("Geteilt"), LIST("Liste") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(session: SearchSession) {
    val state by session.state.collectAsState()
    var mode by rememberSaveable { mutableStateOf(ViewMode.SPLIT) }
    var mapShare by rememberSaveable { mutableFloatStateOf(0.55f) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var showFilter by rememberSaveable { mutableStateOf(false) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var userMoved by remember { mutableStateOf(false) }
    val handle = remember { MapHandle() }
    val hits = state.result?.hits.orEmpty()

    if (showSettings) {
        SettingsScreen(session, onClose = { showSettings = false })
        return
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding()) {
        TopBar(mode, { mode = it }, state.filter, { showFilter = true }, { showSettings = true })
        QuickFilters(state.filter, session::setFilter)
        StatusLine(state)
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val total = maxHeight
            Column(Modifier.fillMaxSize()) {
                if (mode != ViewMode.LIST) {
                    val mapModifier = if (mode == ViewMode.MAP) Modifier.weight(1f) else Modifier.height(total * mapShare)
                    Box(mapModifier.fillMaxWidth()) {
                        StationMap(
                            hits = hits, me = state.location, focus = state.searchCenter.takeIf { !state.manualCenter },
                            handle = handle, onStationClick = { selectedId = it }, onUserMoved = { userMoved = true },
                            modifier = Modifier.fillMaxSize(),
                        )
                        MapControls(
                            handle, showSearchHere = userMoved,
                            onSearchHere = { handle.target?.let(session::searchAt); userMoved = false },
                            onMyLocation = {
                                userMoved = false
                                session.followLocation()
                                state.location?.let(handle::center)
                            },
                        )
                    }
                }
                if (mode == ViewMode.SPLIT) {
                    DragHandle { deltaPx ->
                        mapShare = (mapShare + deltaPx / (total.value * 3f)).coerceIn(0.2f, 0.8f)
                    }
                }
                if (mode != ViewMode.MAP) {
                    StationList(state, onSelect = { selectedId = it.station.id }, Modifier.weight(1f))
                }
            }
        }
    }

    hits.firstOrNull { it.station.id == selectedId }?.let { hit ->
        StationDetailSheet(hit, onDismiss = { selectedId = null })
    }
    if (showFilter) {
        FilterSheet(state, session::setFilter, onDismiss = { showFilter = false })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TopBar(
    mode: ViewMode,
    onMode: (ViewMode) -> Unit,
    filter: StationFilter,
    onFilter: () -> Unit,
    onSettings: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.ElectricBolt, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(8.dp))
        SingleChoiceSegmentedButtonRow(Modifier.weight(1f)) {
            ViewMode.entries.forEachIndexed { i, m ->
                SegmentedButton(
                    selected = mode == m, onClick = { onMode(m) },
                    shape = SegmentedButtonDefaults.itemShape(i, ViewMode.entries.size), icon = {},
                ) { Text(m.label, maxLines = 1) }
            }
        }
        IconButton(onClick = onFilter) {
            BadgedBox(badge = { if (!filter.isDefault) Badge() }) { Icon(Icons.Default.Tune, "Filter") }
        }
        IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "Einstellungen") }
    }
}

@Composable
private fun QuickFilters(filter: StationFilter, onChange: (StationFilter) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = filter.minPowerKw == 150.0, label = { Text("ab 150 kW") },
            onClick = { onChange(filter.copy(minPowerKw = if (filter.minPowerKw == 150.0) null else 150.0)) },
        )
        FilterChip(
            selected = ConnectorType.CCS2 in filter.connectorTypes, label = { Text("CCS") },
            onClick = { onChange(filter.copy(connectorTypes = filter.connectorTypes.toggled(ConnectorType.CCS2))) },
        )
        FilterChip(
            selected = CurrentType.DC in filter.currentTypes, label = { Text("DC") },
            onClick = { onChange(filter.copy(currentTypes = filter.currentTypes.toggled(CurrentType.DC))) },
        )
        FilterChip(
            selected = filter.hiking != null, label = { Text("Wandern") },
            leadingIcon = { Icon(Icons.Default.Hiking, null, Modifier.size(18.dp)) },
            onClick = { onChange(filter.copy(hiking = if (filter.hiking == null) HikingFilter() else null)) },
        )
    }
}

internal fun <T> Set<T>.toggled(item: T): Set<T> = if (item in this) this - item else this + item

@Composable
private fun StatusLine(state: SearchState) {
    if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
    val r = state.result
    val text = when {
        state.error != null -> state.error
        r != null -> buildString {
            append("${r.hits.size} Stationen · ${Format.freshness(r.freshness)}")
            if (r.truncated) append(" · Ergebnis gekürzt")
        }
        !state.locationPermission -> "Kein Standort: Karte verschieben und „Hier suchen“ antippen"
        state.location == null -> "Warte auf Standort…"
        else -> null
    }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        text?.let {
            Text(
                it, style = MaterialTheme.typography.labelMedium,
                color = if (state.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Notices(r?.notices.orEmpty())
    }
}

/** One line while driving past it; the map matters more than a list of caveats. */
@Composable
private fun Notices(notices: List<String>) {
    if (notices.isEmpty()) return
    var expanded by rememberSaveable { mutableStateOf(false) }
    val shown = if (expanded || notices.size == 1) notices else listOf("${notices.first()} (+${notices.size - 1})")
    Column(Modifier.fillMaxWidth().clickable(enabled = notices.size > 1) { expanded = !expanded }) {
        shown.forEach {
            Text(
                it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary,
                maxLines = if (expanded) 3 else 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun DragHandle(onDrag: (Float) -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(20.dp).background(MaterialTheme.colorScheme.surfaceVariant)
            .pointerInput(Unit) { detectVerticalDragGestures { _, delta -> onDrag(delta) } },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(40.dp, 4.dp).background(MaterialTheme.colorScheme.outline, RoundedCornerShape(2.dp)))
    }
}

@Composable
private fun MapControls(
    handle: MapHandle,
    showSearchHere: Boolean,
    onSearchHere: () -> Unit,
    onMyLocation: () -> Unit,
) {
    Box(Modifier.fillMaxSize().padding(12.dp)) {
        Column(Modifier.align(Alignment.CenterEnd), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallFloatingActionButton(onClick = { handle.zoom(1.0) }) { Icon(Icons.Default.Add, "Vergrößern") }
            SmallFloatingActionButton(onClick = { handle.zoom(-1.0) }) { Icon(Icons.Default.Remove, "Verkleinern") }
            SmallFloatingActionButton(onClick = onMyLocation) { Icon(Icons.Default.MyLocation, "Mein Standort") }
        }
        PowerLegend(Modifier.align(Alignment.BottomEnd))
        if (showSearchHere) {
            ExtendedFloatingActionButton(
                onClick = onSearchHere, modifier = Modifier.align(Alignment.TopCenter),
                icon = { Icon(Icons.Default.Search, null) }, text = { Text("Hier suchen") },
            )
        }
    }
}

/** What the dot colours mean; kept small, the map matters more. */
@Composable
private fun PowerLegend(modifier: Modifier) {
    Surface(modifier, color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f), shape = RoundedCornerShape(8.dp)) {
        Row(
            Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            PowerGroup.LEGEND.forEach { group ->
                Box(Modifier.padding(start = 3.dp).size(8.dp).background(Color(group.color), CircleShape))
                Text(group.label, style = MaterialTheme.typography.labelSmall, fontSize = 9.sp)
            }
        }
    }
}

@Composable
private fun StationList(state: SearchState, onSelect: (StationHit) -> Unit, modifier: Modifier) {
    val hits = state.result?.hits.orEmpty()
    if (hits.isEmpty()) {
        Box(modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(
                when {
                    state.loading -> "Suche läuft…"
                    state.error != null -> state.error
                    state.result != null -> "Keine Station passt zu den Filtern."
                    else -> "Noch keine Suche."
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    val listState = rememberLazyListState()
    // A keyed list stays on its item, so a closer station loaded later would land above the view.
    LaunchedEffect(hits.firstOrNull()?.station?.id) { listState.scrollToItem(0) }
    LazyColumn(modifier.fillMaxWidth(), state = listState) {
        items(hits, key = { it.station.id }) { hit ->
            StationRow(hit, onClick = { onSelect(hit) })
            HorizontalDivider()
        }
    }
}

@Composable
private fun StationRow(hit: StationHit, onClick: () -> Unit) {
    val s = hit.station
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(10.dp)) {
            Text(
                s.maxPowerKw?.let { "${it.toInt()}\nkW" } ?: "?\nkW",
                Modifier.padding(horizontal = 10.dp, vertical = 6.dp).width(36.dp),
                style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(Format.title(s), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(s.operator, Format.plugs(s)).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                hit.distance.label() + if (hit.nearby.isNotEmpty()) " · ${hit.nearby.size} Wanderziele" else "",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Format.free(s)?.let { free ->
            Spacer(Modifier.width(12.dp))
            Text(
                free, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold,
                color = if (s.freePoints == 0) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
internal fun PrimaryAction(text: String, onClick: () -> Unit) {
    Button(onClick = onClick, Modifier.fillMaxWidth().height(56.dp)) { Text(text) }
}
