package app.ailectric

import app.ailectric.data.MapBase
import app.ailectric.data.MapSettings
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MapSettingsTest {
    @Test fun `standard map needs no extra credit`() {
        assertTrue(MapSettings().credits.isEmpty())
    }

    @Test fun `each optional view brings its own credit`() {
        val credits = MapSettings(MapBase.SATELLITE, hikingTrails = true).credits
        assertEquals(2, credits.size)
        assertTrue(credits[0].contains("EOX IT Services"))
        assertTrue(credits[1].contains("Waymarked Trails"))
        assertTrue(MapSettings(MapBase.TERRAIN).credits.single().contains("OpenTopoMap"))
    }

    @Test fun `choice survives saving`() {
        val view = MapSettings(MapBase.TERRAIN, hikingTrails = true)
        assertEquals(view, Json.decodeFromString<MapSettings>(Json.encodeToString(view)))
    }
}
