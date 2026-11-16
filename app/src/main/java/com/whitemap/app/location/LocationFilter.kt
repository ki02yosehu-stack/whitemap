package com.whitemap.app.location

import com.whitemap.app.util.Geo

/**
 * 記録する位置を選ぶ。精度の悪い点・ありえない跳びを捨てる。
 * （Android の Location に依存しないので、そのままテストできる）
 */
class LocationFilter(private val now: () -> Long = System::currentTimeMillis) {

    /** @param accuracy 誤差の半径（m）。不明なら null。 @param speed 端末が測った速さ（m/s）。不明なら null。 */
    data class Fix(val lat: Double, val lon: Double, val time: Long, val accuracy: Float?, val speed: Float?)

    private var last: Fix? = null

    fun reset() {
        last = null
    }

    /** 記録してよい位置なら true（その位置を「前回の位置」として覚える）。 */
    fun accept(fix: Fix): Boolean {
        if (fix.accuracy != null && fix.accuracy > maxAccuracy(fix)) return false
        // 起動直後のキャッシュされた古い位置は使わない
        if (now() - fix.time > MAX_AGE_MS) return false
        val prev = last
        if (prev != null) {
            if (fix.time <= prev.time) return false
            val d = Geo.distanceMeters(prev.lat, prev.lon, fix.lat, fix.lon)
            if (d < MIN_STEP_M) return false
            if (d / ((fix.time - prev.time) / 1000.0) >= Geo.MAX_PLAUSIBLE_SPEED_MPS) return false
        }
        last = fix
        return true
    }

    /**
     * 許す誤差の大きさ。
     * 電車や車の中では GPS の電波が弱く誤差が大きくなりがちだが、速く動いていれば
     * 多少の誤差があっても道の形は十分わかるので、ゆるめに受け入れる。
     * 止まっている・歩いているときは、誤差の大きい点を入れると線がぐちゃぐちゃになるので厳しくする。
     */
    private fun maxAccuracy(fix: Fix): Float =
        if (fix.speed != null && fix.speed >= FAST_SPEED_MPS) MAX_ACCURACY_FAST_M else MAX_ACCURACY_M

    companion object {
        const val MAX_ACCURACY_M = 40f
        const val MAX_ACCURACY_FAST_M = 120f
        /** これ以上の速さ（時速 18km）なら「乗り物で移動中」とみなす。 */
        const val FAST_SPEED_MPS = 5f
        const val MIN_STEP_M = 3.0
        const val MAX_AGE_MS = 60_000L
    }
}
