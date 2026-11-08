package com.whitemap.app.ui

import android.Manifest
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import com.whitemap.app.WhiteMapApp
import com.whitemap.app.data.LandmarkEntity
import com.whitemap.app.data.PointEntity
import com.whitemap.app.data.TrackEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.cos
import kotlin.math.sin

/**
 * 実際の MapScreen を描画して PNG に保存する（目視確認用 + 描画が壊れていないかの確認）。
 * 出力先: app/build/outputs/render/ 以下の PNG
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
abstract class MapRenderTestBase {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    protected val app get() = ApplicationProvider.getApplicationContext<WhiteMapApp>()

    @Before
    fun cleanDatabase() {
        app.deleteDatabase("whitemap.db")
    }

    protected fun grant() {
        shadowOf(ApplicationProvider.getApplicationContext<Application>()).grantPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.POST_NOTIFICATIONS,
        )
    }

    protected fun save(name: String): Bitmap {
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bmp))
        val dir = File("build/outputs/render").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return bmp
    }
}

/** 何も記録していないときの真っ白な地図。 */
class EmptyMapRenderTest : MapRenderTestBase() {
    @Test
    fun rendersEmptyMap() {
        grant()
        app.prefs.setOrigin(35.6812, 139.7671)
        compose.setContent { MapScreen() }
        save("empty")
    }
}

/** 歩いた道と目印が描かれた地図。 */
class RoadsMapRenderTest : MapRenderTestBase() {
    @Test
    fun rendersWalkedRoads() {
        grant()
        val lat0 = 35.6812
        val lon0 = 139.7671
        app.prefs.setOrigin(lat0, lon0)
        val dLat = 1 / 111_133.0
        val dLon = 1 / (111_319.0 * cos(Math.toRadians(lat0)))
        runBlocking {
            val dao = app.database.mapDao()
            var t = 0L
            fun walk(track: Long, coords: List<Pair<Double, Double>>) = coords.map { (east, north) ->
                PointEntity(trackId = track, lat = lat0 + north * dLat, lon = lon0 + east * dLon, time = t++, accuracy = 5f)
            }
            // 何回かの散歩：大通り、曲がりくねった川沿いの道、路地
            val trips = listOf(
                (0..40).map { -200.0 + it * 10 to -40.0 },
                (0..30).map { 20.0 to -40.0 + it * 8 },
                (0..60).map { val a = it / 60.0 * Math.PI; -150.0 + it * 6 to 120 + 60 * sin(a * 2) },
                (0..20).map { 20.0 + it * 6 to 200.0 - it * 4 },
                (0..25).map { -120.0 to -40.0 - it * 7 },
                (0..15).map { -120.0 + it * 9 to -215.0 },
            )
            trips.forEachIndexed { i, coords ->
                val id = dao.insertTrack(TrackEntity(startTime = i.toLong()))
                walk(id, coords).forEach { dao.insertPoint(it) }
            }
            dao.insertLandmark(LandmarkEntity(lat = lat0 + 120 * dLat, lon = lon0 - 150 * dLon, name = "川沿いのベンチ", createdAt = 1))
            dao.insertLandmark(LandmarkEntity(lat = lat0 - 40 * dLat, lon = lon0 + 20 * dLon, name = "パン屋", createdAt = 2))
        }
        compose.setContent { MapScreen() }
        compose.waitUntil(5_000) { runBlocking { app.database.mapDao().allPoints().isNotEmpty() } }
        compose.mainClock.advanceTimeBy(2_000)
        val bmp = save("roads")
        // 道（グレーの縁取り）が描かれていること
        var gray = 0
        for (x in 0 until bmp.width step 4) for (y in 0 until bmp.height step 4) {
            val c = bmp.getPixel(x, y)
            val r = (c shr 16) and 0xFF
            if (r in 0x90..0xB8 && r == (c and 0xFF)) gray++
        }
        assertTrue("roads should be drawn (gray px=$gray)", gray > 200)
    }
}
