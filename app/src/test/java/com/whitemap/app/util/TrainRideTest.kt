package com.whitemap.app.util

import com.whitemap.app.data.PointEntity
import org.junit.Assert.assertEquals
import org.junit.Test

/** 電車など、速く移動しているときにも道がつながること。 */
class TrainRideTest {
    private val lat0 = 35.6812
    private val lon0 = 139.7671
    private val proj = LocalProjection(lat0, lon0)
    private val dLon = 1 / (111_319.0 * kotlin.math.cos(Math.toRadians(lat0)))

    /** 東へ speedMps で走り、intervalSec 秒ごとに 1 点取れたときの記録。 */
    private fun ride(speedMps: Double, intervalSec: Int, count: Int) = (0 until count).map { i ->
        PointEntity(
            trackId = 1, lat = lat0, lon = lon0 + speedMps * intervalSec * i * dLon,
            time = i * intervalSec * 1000L, accuracy = 20f,
        )
    }

    @Test
    fun walkingIsOneLine() {
        assertEquals(1, TrackGeometry.build(ride(1.3, 3, 50), proj).strokes.size)
    }

    @Test
    fun trainWithGoodReceptionIsOneLine() {
        // 時速 100km、2 秒ごと → 点の間隔は 56m
        assertEquals(1, TrackGeometry.build(ride(27.8, 2, 50), proj).strokes.size)
    }

    @Test
    fun trainWithPatchyReceptionIsStillOneLine() {
        // 時速 100km、車内で受信が悪く 12 秒ごと → 点の間隔は 333m
        val net = TrackGeometry.build(ride(27.8, 12, 20), proj)
        assertEquals(1, net.strokes.size)
        assertEquals(27.8 * 12 * 19, net.totalMeters, 50.0)
    }

    @Test
    fun longOutageIsNotBridgedWithAStraightLine() {
        // 走行中に 5 分間まったく位置が取れなかった（約 8km）→ そこはまっすぐ結ばない
        val before = ride(27.8, 12, 10)
        val after = ride(27.8, 12, 10).map {
            it.copy(lon = it.lon + 12_000 * dLon, time = it.time + 10 * 12_000L + 300_000L)
        }
        assertEquals(2, TrackGeometry.build(before + after, proj).strokes.size)
    }

    @Test
    fun impossibleJumpIsNotJoined() {
        // 1 秒で 5km 跳ぶのは GPS の誤り
        val a = ride(1.3, 3, 10)
        val b = ride(1.3, 3, 10).map { it.copy(lon = it.lon + 5_000 * dLon, time = it.time + 28_000L) }
        assertEquals(2, TrackGeometry.build(a + b, proj).strokes.size)
    }
}
