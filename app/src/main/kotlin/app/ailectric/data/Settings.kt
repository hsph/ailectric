package app.ailectric.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.ailectric.BuildConfig
import app.ailectric.core.filter.StationFilter
import app.ailectric.core.provider.OVERPASS_PUBLIC_ENDPOINTS
import app.ailectric.core.vehicle.VehicleProfile
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val Context.dataStore by preferencesDataStore("settings")

@Serializable
enum class CarTileSize(val label: String) { SMALL("Klein"), MEDIUM("Mittel"), LARGE("Groß") }

/** How the Android Auto screen looks; the host still decides what these mean in pixels. */
@Serializable
data class CarSettings(
    val tileSize: CarTileSize = CarTileSize.MEDIUM,
    val startZoom: Int = 13,
    val focusZoom: Int = 17,
    val map: MapSettings = MapSettings(),
) {
    companion object {
        /** No satellite: the picture is coarse and has no street names, which helps nobody driving. */
        val MAP_BASES = listOf(MapBase.STANDARD, MapBase.TERRAIN)
        val START_ZOOM_RANGE = 8..16
        val FOCUS_ZOOM_RANGE = 14..18
        /** The car's choices, one name each; the phone can set anything within the ranges. */
        val START_ZOOM_PRESETS = listOf(9, 11, 13, 15, 16)
        val FOCUS_ZOOM_PRESETS = FOCUS_ZOOM_RANGE.toList()
    }
}

private const val HIKING_CREDIT = "Wanderrouten: © Waymarked Trails, © OpenStreetMap-Mitwirkende (CC BY-SA)"

/** The licences ask for these credits to stay visible on the map. */
@Serializable
enum class MapBase(val label: String, val credit: String?) {
    STANDARD("Standard", null),
    TERRAIN("Gelände", "Kartendaten: © OpenStreetMap-Mitwirkende, SRTM | Kartendarstellung: © OpenTopoMap (CC-BY-SA)"),
    SATELLITE("Satellit", "EOxCloudless https://cloudless.eox.at by EOX IT Services GmbH (Contains modified Copernicus Sentinel data 2025)"),
}

/** One map view; phone and car each keep their own. */
@Serializable
data class MapSettings(val base: MapBase = MapBase.STANDARD, val hikingTrails: Boolean = false) {
    val credits: List<String>
        get() = listOfNotNull(base.credit, HIKING_CREDIT.takeIf { hikingTrails })
}

data class Settings(
    val filter: StationFilter = StationFilter(),
    val ocmApiKey: String = "",
    /** Optional own Overpass server, tried before the public ones. */
    val overpassEndpoint: String = "",
    val vehicle: VehicleProfile? = null,
    val car: CarSettings = CarSettings(),
    val map: MapSettings = MapSettings(),
) {
    val effectiveOcmKey: String get() = ocmApiKey.ifBlank { BuildConfig.OCM_API_KEY }
    val overpassEndpoints: List<String>
        get() = listOfNotNull(overpassEndpoint.trim().ifBlank { null }) + OVERPASS_PUBLIC_ENDPOINTS
}

/** Everything is stored on the device only. */
class SettingsStore(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun load(): Settings {
        val p = context.dataStore.data.first()
        return Settings(
            // A filter from an older app version is not worth a crash.
            filter = p[FILTER]?.let { runCatching { json.decodeFromString<StationFilter>(it) }.getOrNull() } ?: StationFilter(),
            ocmApiKey = p[OCM_KEY].orEmpty(),
            overpassEndpoint = p[OVERPASS].orEmpty(),
            vehicle = p[VEHICLE]?.let { runCatching { json.decodeFromString<VehicleProfile>(it) }.getOrNull() },
            car = p[CAR]?.let { runCatching { json.decodeFromString<CarSettings>(it) }.getOrNull() } ?: CarSettings(),
            map = p[MAP]?.let { runCatching { json.decodeFromString<MapSettings>(it) }.getOrNull() } ?: MapSettings(),
        )
    }

    suspend fun save(s: Settings) {
        context.dataStore.edit {
            it[FILTER] = json.encodeToString(s.filter)
            it[OCM_KEY] = s.ocmApiKey
            it[OVERPASS] = s.overpassEndpoint
            if (s.vehicle != null) it[VEHICLE] = json.encodeToString(s.vehicle) else it.remove(VEHICLE)
            it[CAR] = json.encodeToString(s.car)
            it[MAP] = json.encodeToString(s.map)
        }
    }

    private companion object {
        val FILTER = stringPreferencesKey("filter")
        val OCM_KEY = stringPreferencesKey("ocm_key")
        val OVERPASS = stringPreferencesKey("overpass_endpoint")
        val VEHICLE = stringPreferencesKey("vehicle")
        val CAR = stringPreferencesKey("car")
        val MAP = stringPreferencesKey("map")
    }
}
