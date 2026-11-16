package com.whitemap.app.location

import com.whitemap.app.location.LocationFilter.Fix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationFilterTest {
    private val lat0 = 35.6812
    private val lon0 = 139.7671
    private val dLon = 1 / (111_319.0 * kotlin.math.cos(Math.toRadians(lat0)))
    private var clock = 0L
    private val filter = LocationFilter(now = { clock })

    private fun fix(eastM: Double, timeSec: Int, accuracy: Float?, speed: Float?): Fix {
        clock = timeSec * 1000L
        return Fix(lat0, lon0 + eastM * dLon, clock, accuracy, speed)
    }

    @Test
    fun walkingKeepsOnlyAccurateFixes() {
        assertTrue(filter.accept(fix(0.0, 0, accuracy = 10f, speed = 1.3f)))
        assertFalse("70m の誤差は歩きでは捨てる", filter.accept(fix(4.0, 3, accuracy = 70f, speed = 1.3f)))
        assertTrue(filter.accept(fix(8.0, 6, accuracy = 15f, speed = 1.3f)))
    }

    @Test
    fun trainKeepsFixesWithWorseAccuracy() {
        // 時速 100km。車内で誤差が 50〜100m に悪化しても記録する
        var kept = 0
        for (i in 0 until 20) {
            if (filter.accept(fix(27.8 * 2 * i, 2 * i, accuracy = 50f + (i % 3) * 25f, speed = 27.8f))) kept++
        }
        assertEquals(20, kept)
    }

    @Test
    fun veryInaccurateFixIsDroppedEvenWhenFast() {
        assertFalse(filter.accept(fix(0.0, 0, accuracy = 800f, speed = 27.8f)))
    }

    @Test
    fun inaccurateFixWithoutSpeedIsDropped() {
        // 基地局・Wi-Fi 由来の位置（速さの情報なし）は、止まっているときの飛びの原因になるので捨てる
        assertFalse(filter.accept(fix(0.0, 0, accuracy = 70f, speed = null)))
        assertFalse(filter.accept(fix(0.0, 2, accuracy = 70f, speed = 0f)))
    }

    @Test
    fun dropsTinyMovesStaleFixesAndImpossibleJumps() {
        assertTrue(filter.accept(fix(0.0, 0, 10f, 1f)))
        assertFalse("3m 未満は動いていない扱い", filter.accept(fix(1.0, 2, 10f, 1f)))
        assertFalse("2 秒で 1km は跳びすぎ", filter.accept(fix(1_000.0, 4, 10f, 1f)))
        assertTrue("新幹線（秒速 80m）は受け入れる", filter.accept(fix(480.0, 6, 10f, 80f)))
        // 1 分以上前の古い位置
        val old = fix(600.0, 8, 10f, 1f)
        clock += 61_000
        assertFalse(filter.accept(old))
    }

    @Test
    fun resetForgetsPreviousFix() {
        assertTrue(filter.accept(fix(0.0, 10, 10f, 1f)))
        filter.reset()
        assertTrue(filter.accept(fix(0.5, 12, 10f, 1f)))
    }
}
