package com.whitemap.app.util

import com.whitemap.app.data.PointEntity

/** 描画用に組み立てた「道」のデータ。 */
data class RoadNetwork(
    /** 連続した線ごとの座標列 [x0, y0, x1, y1, ...]（ローカル座標、メートル）。 */
    val segments: List<FloatArray>,
    /** 記録した道の総延長（メートル）。 */
    val totalMeters: Double,
) {
    companion object {
        val EMPTY = RoadNetwork(emptyList(), 0.0)
    }
}

object TrackGeometry {
    /** これ以上離れた点同士は線でつながない（GPSが途切れた、など）。 */
    const val MAX_JOIN_DISTANCE_M = 300.0

    /** これより近い点は描画上まとめる。 */
    const val MIN_STEP_M = 1.0

    /**
     * points は trackId → time の順に並んでいること。
     * 旅（track）が変わるか、点の間隔が大きく空いたところで線を区切る。
     */
    fun build(points: List<PointEntity>, projection: LocalProjection): RoadNetwork {
        if (points.isEmpty()) return RoadNetwork.EMPTY
        val segments = ArrayList<FloatArray>()
        var total = 0.0
        val current = FloatList()
        var prev: PointEntity? = null

        fun flush() {
            if (current.size >= 4) segments.add(current.toArray())
            else if (current.size == 2) {
                // 単独の点も小さな丸として残す
                segments.add(floatArrayOf(current[0], current[1], current[0], current[1]))
            }
            current.clear()
        }

        for (p in points) {
            val last = prev
            if (last != null) {
                val d = Geo.distanceMeters(last.lat, last.lon, p.lat, p.lon)
                if (last.trackId != p.trackId || d > MAX_JOIN_DISTANCE_M) {
                    flush()
                } else if (d < MIN_STEP_M) {
                    continue
                } else {
                    total += d
                }
            }
            current.add(projection.x(p.lon).toFloat(), projection.y(p.lat).toFloat())
            prev = p
        }
        flush()
        return RoadNetwork(segments, total)
    }

    /** 縮尺バー用に 1, 2, 5 × 10^n のきりのいい距離を選ぶ。 */
    fun niceDistance(maxMeters: Double): Double {
        if (maxMeters <= 0) return 1.0
        var base = 1.0
        while (base * 10 <= maxMeters) base *= 10
        while (base > maxMeters) base /= 10
        return when {
            base * 5 <= maxMeters -> base * 5
            base * 2 <= maxMeters -> base * 2
            else -> base
        }
    }

    fun formatDistance(meters: Double): String = when {
        meters >= 10_000 -> "%.0f km".format(meters / 1000)
        meters >= 1_000 -> "%.1f km".format(meters / 1000)
        else -> "%.0f m".format(meters)
    }
}

/** ボクシングを避ける簡易 FloatArray ビルダー。 */
private class FloatList {
    private var data = FloatArray(256)
    var size = 0
        private set

    operator fun get(i: Int) = data[i]

    fun add(a: Float, b: Float) {
        if (size + 2 > data.size) data = data.copyOf(data.size * 2)
        data[size++] = a
        data[size++] = b
    }

    fun clear() {
        size = 0
    }

    fun toArray(): FloatArray = data.copyOf(size)
}
