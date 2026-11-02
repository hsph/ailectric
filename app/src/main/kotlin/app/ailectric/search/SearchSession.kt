package app.ailectric.search

import app.ailectric.AilectricApplication
import app.ailectric.core.filter.StationFilter
import app.ailectric.core.geo.Geo
import app.ailectric.core.geo.GeoPoint
import app.ailectric.core.provider.ProviderException
import app.ailectric.core.search.ChargerSearch
import app.ailectric.core.search.SearchResult
import app.ailectric.data.CarSettings
import app.ailectric.data.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SearchState(
    val location: GeoPoint? = null,
    /** Where the last search was centred; differs from [location] after a manual map search. */
    val searchCenter: GeoPoint? = null,
    val manualCenter: Boolean = false,
    val loading: Boolean = false,
    val result: SearchResult? = null,
    val error: String? = null,
    val filter: StationFilter = StationFilter(),
    val locationPermission: Boolean = false,
    val car: CarSettings = CarSettings(),
)

/** The single search state behind both the phone UI and the car screens. */
class SearchSession(
    private val search: ChargerSearch,
    private val app: AilectricApplication,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(SearchState())
    val state: StateFlow<SearchState> = _state.asStateFlow()

    private var searchJob: Job? = null
    private var locationJob: Job? = null
    private var settingsLoaded = false

    /** Safe to call repeatedly, e.g. after the permission was granted. */
    fun start() {
        scope.launch {
            if (!settingsLoaded) {
                app.settings = app.settingsStore.load()
                settingsLoaded = true
                _state.update { it.copy(filter = app.settings.filter, car = app.settings.car) }
            }
            val granted = app.locationSource.hasPermission()
            _state.update { it.copy(locationPermission = granted) }
            if (granted && locationJob == null) {
                locationJob = launch { app.locationSource.updates().collect(::onLocation) }
            }
        }
    }

    private fun onLocation(point: GeoPoint) {
        val s = _state.value
        _state.update { it.copy(location = point) }
        if (s.manualCenter) return
        val last = s.searchCenter
        if (last == null || Geo.distanceMeters(last, point) >= MIN_MOVE_METERS) run(point, manual = false)
    }

    fun searchAt(center: GeoPoint) = run(center, manual = true)

    /** Back to following the GPS position. */
    fun followLocation() {
        val location = _state.value.location ?: return
        run(location, manual = false)
    }

    fun refresh() {
        val s = _state.value
        val center = s.searchCenter ?: s.location ?: return
        run(center, s.manualCenter)
    }

    fun setFilter(filter: StationFilter) {
        _state.update { it.copy(filter = filter) }
        saveSettings(app.settings.copy(filter = filter))
        refresh()
    }

    fun saveSettings(settings: Settings) {
        app.settings = settings
        _state.update { it.copy(car = settings.car) }
        scope.launch { app.settingsStore.save(settings) }
    }

    private fun run(center: GeoPoint, manual: Boolean) {
        searchJob?.cancel()
        _state.update { it.copy(searchCenter = center, manualCenter = manual, loading = true) }
        searchJob = scope.launch {
            try {
                val result = search.search(center, _state.value.filter) { partial ->
                    _state.update { it.copy(result = partial, error = null) }
                }
                _state.update { it.copy(result = result, error = null, loading = false) }
            } catch (e: ProviderException) {
                _state.update { it.copy(result = null, error = e.message, loading = false) }
            }
        }
    }

    private companion object {
        /** Below this the result list barely changes; the cache would answer anyway. */
        const val MIN_MOVE_METERS = 2_000.0
    }
}
