package com.whitemap.app.util

import com.whitemap.app.data.PointEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackGeometryTest {
    private val proj = LocalProjection(35.6812, 139.7671) // 東京駅

    private fun p(track: Long, lat: Double, lon: Double, t: Long) =
        PointEntity(trackId = track, lat = lat, lon = lon, time = t, accuracy = 5f)

    @Test
    fun distanceIsRoughlyCorrect() {
        // 緯度 0.001度 ≒ 111m
        val d = Geo.distanceMeters(35.0, 139.0, 35.001, 139.0)
        assertEquals(111.2, d, 0.5)
    }

    @Test
    fun projectionRoundTrips() {
        val x = proj.x(139.78)
        val y = proj.y(35.69)
        assertEquals(139.78, proj.lon(x), 1e-9)
        assertEquals(35.69, proj.lat(y), 1e-9)
        assertTrue("north should be up (negative y)", y < 0)
        assertTrue("east should be right (positive x)", x > 0)
    }

    @Test
    fun projectedDistanceMatchesHaversineLocally() {
        val a = p(1, 35.6812, 139.7671, 0)
        val b = p(1, 35.6852, 139.7721, 0)
        val dx = proj.x(b.lon) - proj.x(a.lon)
        val dy = proj.y(b.lat) - proj.y(a.lat)
        val planar = kotlin.math.sqrt(dx * dx + dy * dy)
        val real = Geo.distanceMeters(a.lat, a.lon, b.lat, b.lon)
        assertEquals(real, planar, real * 0.005)
    }

    @Test
    fun splitsSegmentsOnNewTripAndOnGaps() {
        val points = listOf(
            p(1, 35.6812, 139.7671, 0),
            p(1, 35.6813, 139.7671, 1),
            p(1, 35.6814, 139.7671, 2),
            // 2km 先へワープ → 線はつながない
            p(1, 35.7000, 139.7671, 3),
            p(1, 35.7001, 139.7671, 4),
            // 別の旅（さらに別の場所）
            p(2, 35.7200, 139.7671, 5),
            p(2, 35.7201, 139.7671, 6),
        )
        val net = TrackGeometry.build(points, proj)
        assertEquals(3, net.strokes.size)
        assertTrue(net.strokes.all { it.level == 1 })
        assertEquals(6, net.strokes.single { it.coords.size == 6 }.coords.size)
        // ワープ分は距離に含めない（11m × 4 ≒ 44m）
        assertEquals(44.5, net.totalMeters, 2.0)
    }

    @Test
    fun keepsLonePointAsDot() {
        val net = TrackGeometry.build(listOf(p(1, 35.0, 139.0, 0)), proj)
        assertEquals(1, net.strokes.size)
        assertEquals(4, net.strokes[0].coords.size)
    }

    // ---- 何度も通った道ほど濃くなる ----

    private val dLat = 1 / 111_133.0
    private val dLon = 1 / (111_319.0 * kotlin.math.cos(Math.toRadians(35.6812)))

    /** 原点から東へ east m、北へ north m の地点。 */
    private fun at(track: Long, east: Double, north: Double, timeSec: Long) =
        p(track, 35.6812 + north * dLat, 139.7671 + east * dLon, timeSec * 1000)

    /** 東西にまっすぐ 200m 歩く（5m ごと、1 秒に 1.25m）。 */
    private fun walkEast(track: Long, north: Double, startSec: Long, reverse: Boolean = false) =
        (0..40).map { i ->
            val e = if (reverse) 200.0 - i * 5 else i * 5.0
            at(track, e, north, startSec + i * 4)
        }

    private fun levelAt(net: RoadNetwork, east: Double, north: Double): Int {
        val x = proj.x(139.7671 + east * dLon)
        val y = proj.y(35.6812 + north * dLat)
        return net.strokes.filter { s ->
            (0 until s.coords.size / 2).any { i ->
                kotlin.math.hypot(s.coords[i * 2] - x, s.coords[i * 2 + 1] - y) < 6
            }
        }.maxOf { it.level }
    }

    @Test
    fun onceWalkedRoadIsLevelOne() {
        val net = TrackGeometry.build(walkEast(1, 0.0, 0), proj)
        assertTrue(net.strokes.all { it.level == 1 })
    }

    @Test
    fun sameRoadOnDifferentTripsGetsDarkerEvenWithGpsOffset() {
        // 3回の旅。GPS のずれで毎回 4〜6m ずれている
        val points = walkEast(1, 0.0, 0) + walkEast(2, 4.0, 10_000) + walkEast(3, -5.0, 20_000)
        val net = TrackGeometry.build(points, proj)
        assertEquals(3, levelAt(net, 100.0, 0.0))
        assertEquals(3, levelAt(net, 100.0, 4.0))
    }

    @Test
    fun goingThereAndBackInOneTripCountsTwice() {
        val out = walkEast(1, 0.0, 0)
        val back = walkEast(1, 3.0, 400, reverse = true)
        val net = TrackGeometry.build(out + back, proj)
        assertEquals(2, levelAt(net, 100.0, 0.0))
    }

    @Test
    fun standingStillDoesNotCountAsAnotherVisit() {
        // 100m 歩いて 20 分立ち止まり、また歩き出す
        val first = (0..20).map { at(1, it * 5.0, 0.0, it * 4L) }
        val second = (21..40).map { at(1, it * 5.0, 0.0, 1_200 + it * 4L) }
        val net = TrackGeometry.build(first + second, proj)
        assertTrue(net.strokes.all { it.level == 1 })
    }

    @Test
    fun gpsJitterDoesNotCountAsAnotherVisit() {
        // 同じ場所で 1 分間ふらつく（±12m）
        val jitter = (0..30).map { i -> at(1, if (i % 2 == 0) 0.0 else 12.0, if (i % 3 == 0) 0.0 else 6.0, i * 2L) }
        val net = TrackGeometry.build(jitter, proj)
        assertTrue(net.strokes.all { it.level == 1 })
    }

    @Test
    fun crossingRoadOnlyDarkensNearTheCrossing() {
        val eastWest = walkEast(1, 0.0, 0)
        val northSouth = (0..40).map { i -> at(2, 100.0, -100.0 + i * 5, 10_000 + i * 4L) }
        val net = TrackGeometry.build(eastWest + northSouth, proj)
        assertEquals(1, levelAt(net, 20.0, 0.0))
        assertEquals(1, levelAt(net, 100.0, 80.0))
    }

    @Test
    fun crossingStreetIsNotDarkenedAtTheIntersection() {
        val eastWest = walkEast(1, 0.0, 0)
        val northSouth = (0..40).map { i -> at(2, 100.0, -100.0 + i * 5, 10_000 + i * 4L) }
        val net = TrackGeometry.build(eastWest + northSouth, proj)
        assertTrue(net.strokes.all { it.level == 1 })
    }

    @Test
    fun repeatedPassesAreMergedIntoOneLine() {
        // 3回とも GPS のずれ方が違う（+4m, 0m, -5m）。濃い線は平均（約 -0.3m）のあたりに 1 本にまとまる
        val points = walkEast(1, 4.0, 0) + walkEast(2, 0.0, 10_000) + walkEast(3, -5.0, 20_000)
        val net = TrackGeometry.build(points, proj)
        val meanY = proj.y(35.6812 + (-1.0 / 3) * dLat)
        // 端の部分（まわりのデータが少ない）を除いた中央 160m で確認
        val x20 = proj.x(139.7671 + 20 * dLon)
        val x180 = proj.x(139.7671 + 180 * dLon)
        for (stroke in net.strokes.filter { it.level == 3 }) {
            for (i in 0 until stroke.coords.size / 2) {
                if (stroke.coords[i * 2] !in x20..x180) continue
                assertEquals(meanY, stroke.coords[i * 2 + 1].toDouble(), 1.0)
            }
        }
    }

    @Test
    fun strokesAreSortedLightToDark() {
        val points = walkEast(1, 0.0, 0) + walkEast(2, 0.0, 10_000) +
            (0..40).map { i -> at(3, 100.0, -100.0 + i * 5, 20_000 + i * 4L) }
        val levels = TrackGeometry.build(points, proj).strokes.map { it.level }
        assertEquals(levels.sorted(), levels)
    }

    @Test
    fun niceDistances() {
        assertEquals(50.0, TrackGeometry.niceDistance(73.0), 0.0)
        assertEquals(200.0, TrackGeometry.niceDistance(260.0), 0.0)
        assertEquals(1000.0, TrackGeometry.niceDistance(1999.0), 0.0)
        assertEquals(5.0, TrackGeometry.niceDistance(9.9), 0.0)
    }

    @Test
    fun gpxEscapesNames() {
        assertEquals("a &amp; b &lt;c&gt;", GpxWriter.escape("a & b <c>"))
    }
}
