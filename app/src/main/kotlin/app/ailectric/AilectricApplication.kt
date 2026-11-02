package app.ailectric

import android.app.Application
import android.content.Context
import app.ailectric.core.provider.Http
import app.ailectric.core.provider.NdwProvider
import app.ailectric.core.provider.OpenChargeMapProvider
import app.ailectric.core.provider.OsmStationProvider
import app.ailectric.core.provider.OverpassPoiProvider
import app.ailectric.core.provider.RegisterProvider
import app.ailectric.core.provider.SwissProvider
import app.ailectric.core.search.ChargerSearch
import app.ailectric.core.search.StationRepository
import app.ailectric.data.AppDatabase
import app.ailectric.data.RoomSearchCache
import app.ailectric.data.Settings
import app.ailectric.data.SettingsStore
import app.ailectric.location.LocationSource
import app.ailectric.search.SearchSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.maplibre.android.MapLibre
import java.io.FileNotFoundException

open class AilectricApplication : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val settingsStore by lazy { SettingsStore(this) }
    val locationSource by lazy { LocationSource(this) }

    @Volatile var settings = Settings()

    /** Phone and car show one shared search state. */
    val session by lazy {
        val http = Http.client()
        val cache = RoomSearchCache(AppDatabase.build(this).cache())
        val endpoints = { settings.overpassEndpoints }
        val stations = StationRepository(
            providers = listOf(
                RegisterProvider(asset("stand.txt")?.trim(), ::asset),
                NdwProvider(http),
                SwissProvider(http),
                OpenChargeMapProvider({ settings.effectiveOcmKey }, http),
                OsmStationProvider(http, endpoints),
            ),
            cache = cache,
        )
        SearchSession(ChargerSearch(stations, OverpassPoiProvider(http, endpoints), cache), this, appScope)
    }

    private fun asset(name: String): String? = try {
        assets.open("register/$name").bufferedReader().use { it.readText() }
    } catch (_: FileNotFoundException) {
        null
    }

    override fun onCreate() {
        super.onCreate()
        initMapEngine()
    }

    /** Native code, so JVM tests replace it. */
    protected open fun initMapEngine() {
        MapLibre.getInstance(this)
    }
}

val Context.app: AilectricApplication get() = applicationContext as AilectricApplication
