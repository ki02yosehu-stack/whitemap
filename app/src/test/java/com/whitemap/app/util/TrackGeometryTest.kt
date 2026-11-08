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
            // 別の旅
            p(2, 35.7002, 139.7671, 5),
            p(2, 35.7003, 139.7671, 6),
        )
        val net = TrackGeometry.build(points, proj)
        assertEquals(3, net.segments.size)
        assertEquals(6, net.segments[0].size)
        // ワープ分は距離に含めない（11m + 11m + 11m + 11m ≒ 44m）
        assertEquals(44.5, net.totalMeters, 2.0)
    }

    @Test
    fun keepsLonePointAsDot() {
        val net = TrackGeometry.build(listOf(p(1, 35.0, 139.0, 0)), proj)
        assertEquals(1, net.segments.size)
        assertEquals(4, net.segments[0].size)
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
