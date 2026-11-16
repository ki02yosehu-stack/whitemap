package com.whitemap.app.data

import androidx.test.core.app.ApplicationProvider
import com.whitemap.app.WhiteMapApp
import com.whitemap.app.util.GpxWriter
import com.whitemap.app.util.LocalProjection
import com.whitemap.app.util.TrackGeometry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.StringWriter
import kotlin.math.cos

/** 書き出し → アプリを入れ直す → 読みこみ で、地図が元どおりになること。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GpxImporterTest {
    private val app get() = ApplicationProvider.getApplicationContext<WhiteMapApp>()
    private val dao get() = app.database.mapDao()

    private val lat0 = 35.6812
    private val lon0 = 139.7671
    private val proj = LocalProjection(lat0, lon0)
    private val dLon = 1 / (111_319.0 * cos(Math.toRadians(lat0)))

    @Before
    fun cleanDatabase() {
        app.deleteDatabase("whitemap.db")
    }

    @After
    fun close() {
        app.database.close()
    }

    /** 同じ道を 3 回（徒歩）と、電車 1 回、目印 2 つ。時刻はミリ秒つき。 */
    private suspend fun recordSomeTrips() {
        var clock = 1_700_000_000_123L
        repeat(3) { trip ->
            val id = dao.insertTrack(TrackEntity(startTime = clock))
            for (i in 0..60) {
                clock += 3_217
                dao.insertPoint(
                    PointEntity(trackId = id, lat = lat0 + trip * 0.00003, lon = lon0 + i * 4 * dLon, time = clock, accuracy = 8f)
                )
            }
            dao.finishTrack(id, clock)
            clock += 86_400_000
        }
        val train = dao.insertTrack(TrackEntity(startTime = clock))
        for (i in 0..30) {
            clock += 12_000
            dao.insertPoint(
                PointEntity(trackId = train, lat = lat0 + 0.01, lon = lon0 + i * 333 * dLon, time = clock, accuracy = 60f)
            )
        }
        dao.insertLandmark(LandmarkEntity(lat = lat0 + 0.001, lon = lon0, name = "パン屋", createdAt = clock))
        dao.insertLandmark(LandmarkEntity(lat = lat0, lon = lon0 + 0.001, name = "川沿いのベンチ", createdAt = clock))
    }

    private suspend fun export(): String {
        val out = StringWriter()
        GpxWriter.write(out, dao.allPoints(), dao.allLandmarks())
        return out.toString()
    }

    /** 地図に描かれる内容（線ごとの濃さと点の数、総延長、目印）。 */
    private suspend fun mapSummary(): String {
        val net = TrackGeometry.build(dao.allPoints(), proj)
        return "strokes=" + net.strokes.map { "level${it.level}x${it.coords.size}" } +
            " meters=%.1f".format(net.totalMeters) +
            " landmarks=" + dao.allLandmarks().map { Triple(it.lat, it.lon, it.name) }
    }

    @Test
    fun exportThenImportIntoFreshAppRestoresTheSameMap() = runBlocking {
        recordSomeTrips()
        val before = mapSummary()
        val gpx = export()
        assertTrue("3 回通った道があるはず: $before", before.contains("level3"))

        dao.clearAll() // アンインストール → 入れ直し
        assertEquals(0, dao.allPoints().size)

        val result = GpxImporter(app.database).import(gpx.byteInputStream())
        assertEquals(4, result.trips)
        assertEquals(2, result.landmarks)
        assertEquals(0, result.skipped)
        assertEquals(lat0 to lon0, result.firstPoint)
        assertEquals(before, mapSummary())
    }

    @Test
    fun importingTwiceDoesNotDuplicateOrDarkenRoads() = runBlocking {
        recordSomeTrips()
        val gpx = export()
        dao.clearAll()
        GpxImporter(app.database).import(gpx.byteInputStream())
        val once = mapSummary()

        val again = GpxImporter(app.database).import(gpx.byteInputStream())
        assertEquals(0, again.trips)
        assertEquals(0, again.landmarks)
        assertEquals(6, again.skipped)
        assertEquals(once, mapSummary())
    }

    @Test
    fun importingOwnExportWithoutReinstallingDoesNotDuplicate() = runBlocking {
        // 入れ直さずに、書き出したファイルをそのまま読みこんでも二重にならない
        recordSomeTrips()
        val before = mapSummary()
        val result = GpxImporter(app.database).import(export().byteInputStream())
        assertEquals(0, result.trips)
        assertEquals(6, result.skipped)
        assertEquals(before, mapSummary())
    }

    @Test
    fun importAddsToExistingRecords() = runBlocking {
        recordSomeTrips()
        val countBefore = dao.allPoints().size
        val other = """<gpx><trk><trkseg>
            <trkpt lat="36.0" lon="140.0"/><trkpt lat="36.0001" lon="140.0"/>
        </trkseg></trk></gpx>"""
        val result = GpxImporter(app.database).import(other.byteInputStream(), now = 5_000)
        assertEquals(1, result.trips)
        assertEquals(countBefore + 2, dao.allPoints().size)
        // 時刻のない点は 1 秒ごとに並ぶ
        assertEquals(listOf(5_000L, 6_000L), dao.allPoints().filter { it.lat >= 36.0 }.map { it.time })
    }
}
