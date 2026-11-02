package app.ailectric

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import androidx.car.app.SurfaceContainer
import androidx.car.app.testing.TestCarContext
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.ailectric.car.CarMapRenderer
import app.ailectric.core.geo.GeoPoint
import app.ailectric.core.model.Address
import app.ailectric.core.model.ChargingDevice
import app.ailectric.core.model.Connector
import app.ailectric.core.model.ConnectorType
import app.ailectric.core.model.DataSource
import app.ailectric.core.model.Station
import app.ailectric.data.CarSettings
import app.ailectric.data.MapBase
import app.ailectric.data.MapSettings
import app.ailectric.ui.PowerGroup
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/** Draws onto a host-like surface for real; needs a device or emulator and internet for tiles. */
@RunWith(AndroidJUnit4::class)
class CarMapRendererTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val width = 1280
    private val height = 720
    private lateinit var reader: ImageReader
    private lateinit var thread: HandlerThread
    private lateinit var renderer: CarMapRenderer
    private val latest = AtomicReference<Bitmap?>()

    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)

    private fun <T> onMain(block: () -> T): T {
        val out = AtomicReference<T>()
        main { out.set(block()) }
        return out.get()
    }

    private fun await(what: String, timeoutMs: Long = 60_000, check: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (!check()) {
            check(SystemClock.uptimeMillis() < end) { "timeout waiting for $what" }
            SystemClock.sleep(200)
        }
    }

    @Before fun setUp() {
        thread = HandlerThread("frames").apply { start() }
        reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
        reader.setOnImageAvailableListener({ r ->
            r.acquireLatestImage()?.use { image ->
                val plane = image.planes[0]
                val padded = plane.rowStride / plane.pixelStride
                val bitmap = Bitmap.createBitmap(padded, height, Bitmap.Config.ARGB_8888)
                bitmap.copyPixelsFromBuffer(plane.buffer)
                latest.set(bitmap)
            }
        }, Handler(thread.looper))
        main {
            val context = TestCarContext.createCarContext(instrumentation.targetContext)
            val owner = object : LifecycleOwner {
                override val lifecycle = LifecycleRegistry.createUnsafe(this)
            }
            renderer = CarMapRenderer(context, owner.lifecycle)
            renderer.center(GeoPoint(48.137, 11.575))
            renderer.onSurfaceAvailable(SurfaceContainer(reader.surface, width, height, 160))
        }
    }

    @After fun tearDown() {
        if (::renderer.isInitialized) {
            main { renderer.onSurfaceDestroyed(SurfaceContainer(reader.surface, width, height, 160)) }
        }
        reader.close()
        thread.quitSafely()
    }

    private fun distinctColors(b: Bitmap): Int {
        val colors = HashSet<Int>()
        for (y in 0 until height step 8) for (x in 0 until width step 8) colors += b.getPixel(x, y)
        return colors.size
    }

    @Test fun startsCenteredBesideThePanelAndKeepsItThroughThePanMode() {
        await("style") { onMain { renderer.styleLoaded } }
        main { renderer.onVisibleAreaChanged(Rect(400, 0, width, height)) }
        SystemClock.sleep(500)
        assertThat(onMain { renderer.centerOnSurface }!!.x).isWithin(2f).of(840f)
        assertThat(onMain { renderer.zoomLevel }!!).isWithin(0.01).of(13.0)

        main {
            renderer.holdPosition()
            renderer.onVisibleAreaChanged(Rect(0, 0, width, height))
        }
        SystemClock.sleep(500)
        assertThat(onMain { renderer.centerOnSurface }!!.x).isWithin(2f).of(840f)
    }

    @Test fun terrainAndTrailsReachTheMapAndComeBackOff() {
        await("style") { onMain { renderer.styleLoaded } }
        main { renderer.apply(CarSettings(map = MapSettings(MapBase.TERRAIN, hikingTrails = true))) }
        SystemClock.sleep(8_000) // Tiles.
        File(instrumentation.targetContext.getExternalFilesDir(null), "car_terrain.png").outputStream().use {
            latest.get()!!.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        main { renderer.apply(CarSettings()) }
        SystemClock.sleep(1_000)
        assertThat(onMain { renderer.styleLoaded }).isTrue()
    }

    @Test fun zoomingOutAndInWithOverlaysKeepsTheMapAlive() {
        await("style") { onMain { renderer.styleLoaded } }
        for (base in listOf(MapBase.STANDARD, MapBase.TERRAIN)) {
            main { renderer.apply(CarSettings(map = MapSettings(base, hikingTrails = true))) }
            repeat(3) {
                repeat(16) {
                    main { renderer.onScale(640f, 360f, 0.6f) }
                    SystemClock.sleep(120)
                }
                main { renderer.zoomBy(-3.0) }
                SystemClock.sleep(1_500)
                repeat(16) {
                    main { renderer.onScale(640f, 360f, 1.6f) }
                    SystemClock.sleep(120)
                }
                SystemClock.sleep(1_500)
            }
        }
        assertThat(onMain { renderer.styleLoaded }).isTrue()
        assertThat(onMain { renderer.zoomLevel }).isNotNull()
    }

    @Test fun selectedStationIsMarkedLikeARadioButton() {
        await("style") { onMain { renderer.styleLoaded } }
        val point = GeoPoint(48.137, 11.575)
        val plug = Connector(ConnectorType.CCS2, powerKw = 350.0)
        val station = Station(
            id = "t:1", name = null, operator = null, address = Address(), location = point,
            devices = listOf(ChargingDevice(connectors = listOf(plug))), source = DataSource("Test", "Test"),
        )
        main { renderer.focus(station) }
        SystemClock.sleep(8_000) // Animation and tiles.
        File(instrumentation.targetContext.getExternalFilesDir(null), "car_selected.png").outputStream().use {
            latest.get()!!.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        val at = onMain { renderer.centerOnSurface }!!
        fun pixel(dx: Int) = latest.get()!!.getPixel(at.x.toInt() + dx, at.y.toInt())
        assertThat(pixel(0)).isEqualTo(Color.WHITE)
        assertThat(pixel(12)).isEqualTo(PowerGroup.ULTRA.color)

        main { renderer.unfocus() }
        SystemClock.sleep(2_000)
        assertThat(onMain { renderer.zoomLevel }!!).isWithin(0.01).of(13.0)
    }

    @Test fun rendersMapAndForwardsHostGestures() {
        await("style") { onMain { renderer.styleLoaded } }
        await("map frames") { latest.get()?.let(::distinctColors)?.let { it > 50 } == true }

        SystemClock.sleep(12_000) // Remaining tiles, so the saved image is worth looking at.
        File(instrumentation.targetContext.getExternalFilesDir(null), "car_map.png").outputStream().use {
            latest.get()!!.compress(Bitmap.CompressFormat.PNG, 100, it)
        }

        val before = onMain { renderer.cameraCenter }!!
        main { renderer.onScroll(200f, 0f) }
        await("pan", 10_000) { onMain { renderer.cameraCenter }!!.lon > before.lon }

        val zoom = onMain { renderer.zoomLevel }!!
        main { renderer.onScale(640f, 360f, 2f) }
        await("zoom", 10_000) { onMain { renderer.zoomLevel }!! > zoom + 0.9 }
    }
}
