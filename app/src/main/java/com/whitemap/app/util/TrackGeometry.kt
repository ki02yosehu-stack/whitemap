package com.whitemap.app.util

import com.whitemap.app.data.PointEntity
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot

/**
 * 描画用の 1 本の線。
 * @property level その道を通った回数（1 = 1回だけ）。多いほど濃く描く。
 * @property coords 座標列 [x0, y0, x1, y1, ...]（ローカル座標、メートル）。
 */
class RoadStroke(val level: Int, val coords: FloatArray)

/** 描画用に組み立てた「道」のデータ。 */
data class RoadNetwork(
    val strokes: List<RoadStroke>,
    /** 記録した道の総延長（メートル）。 */
    val totalMeters: Double,
) {
    val isEmpty: Boolean get() = strokes.isEmpty()

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
     * 「同じ場所」とみなすマス目の大きさ（メートル）。
     * 通った点のまわり 3×3 マスに印をつけるので、GPS のずれが ±10m 前後あっても同じ道として数えられる。
     */
    const val CELL_M = 8.0

    /** 同じ旅の中で、いったん離れてからこの時間以上たって戻ってきたら「もう一度通った」と数える。 */
    const val REVISIT_GAP_MS = 90_000L

    /**
     * points は trackId → time の順に並んでいること。
     * 旅（track）が変わるか、点の間隔が大きく空いたところで線を区切り、
     * 各区間に「その場所を何回通ったか」を付ける。
     */
    fun build(points: List<PointEntity>, projection: LocalProjection): RoadNetwork {
        if (points.isEmpty()) return RoadNetwork.EMPTY

        // 1) 連続した区間（run）に分け、近すぎる点を間引く
        val runs = ArrayList<List<PointEntity>>()
        var current = ArrayList<PointEntity>()
        var total = 0.0
        var prev: PointEntity? = null
        for (p in points) {
            val last = prev
            if (last != null) {
                val d = Geo.distanceMeters(last.lat, last.lon, p.lat, p.lon)
                if (last.trackId != p.trackId || d > MAX_JOIN_DISTANCE_M) {
                    runs.add(current)
                    current = ArrayList()
                } else if (d < MIN_STEP_M) {
                    continue
                } else {
                    total += d
                }
            }
            current.add(p)
            prev = p
        }
        runs.add(current)

        val projected = runs.map { run ->
            DoubleArray(run.size * 2).also { a ->
                run.forEachIndexed { i, p ->
                    a[i * 2] = projection.x(p.lon)
                    a[i * 2 + 1] = projection.y(p.lat)
                }
            }
        }

        // 2) 何回通ったかを「マス目 × 道の向き」ごとに数える
        val visits = VisitCounter()
        val bins = projected.map { xy -> IntArray(xy.size / 2) { orientationBin(xy, it) } }
        runs.forEachIndexed { r, run ->
            val xy = projected[r]
            val bin = bins[r]
            val track = run[0].trackId
            visits.touch(xy[0], xy[1], track, run[0].time, bin[0])
            for (i in 1 until run.size) {
                // 点の間を細かく区切って、飛ばされるマスが出ないようにする
                val x0 = xy[i * 2 - 2]
                val y0 = xy[i * 2 - 1]
                val x1 = xy[i * 2]
                val y1 = xy[i * 2 + 1]
                val t0 = run[i - 1].time
                val t1 = run[i].time
                val steps = ceil(hypot(x1 - x0, y1 - y0) / (CELL_M / 2)).toInt().coerceAtLeast(1)
                for (k in 1..steps) {
                    val f = k.toDouble() / steps
                    visits.touch(
                        x0 + (x1 - x0) * f, y0 + (y1 - y0) * f,
                        track, t0 + ((t1 - t0) * f).toLong(), bin[i],
                    )
                }
            }
        }

        // 3) 何度も通った道は、毎回の GPS のずれを平均して 1 本の線にまとめ、
        //    通った回数が同じところごとに線をまとめる
        val strokes = ArrayList<RoadStroke>()
        projected.forEachIndexed { r, xy ->
            val n = xy.size / 2
            val bin = bins[r]
            val levels = IntArray(n) { visits.count(xy[it * 2], xy[it * 2 + 1], bin[it]).coerceAtLeast(1) }
            val shown = FloatArray(n * 2)
            for (i in 0 until n) {
                val x = xy[i * 2]
                val y = xy[i * 2 + 1]
                if (levels[i] >= 2 && n >= 2) {
                    // 進行方向はそのまま、横方向のずれだけを平均に寄せる
                    val (dx, dy) = direction(xy, i)
                    val (mx, my) = visits.mean(x, y, bin[i])
                    val off = (mx - x) * -dy + (my - y) * dx
                    shown[i * 2] = (x - dy * off).toFloat()
                    shown[i * 2 + 1] = (y + dx * off).toFloat()
                } else {
                    shown[i * 2] = x.toFloat()
                    shown[i * 2 + 1] = y.toFloat()
                }
            }
            if (n == 1) {
                // 単独の点も小さな丸として残す
                strokes.add(RoadStroke(levels[0], floatArrayOf(shown[0], shown[1], shown[0], shown[1])))
                return@forEachIndexed
            }
            val line = FloatList()
            var lineLevel = -1
            for (i in 1 until n) {
                val level = minOf(levels[i - 1], levels[i])
                if (level != lineLevel) {
                    if (line.size >= 4) strokes.add(RoadStroke(lineLevel, line.toArray()))
                    line.clear()
                    line.add(shown[i * 2 - 2], shown[i * 2 - 1])
                    lineLevel = level
                }
                line.add(shown[i * 2], shown[i * 2 + 1])
            }
            if (line.size >= 4) strokes.add(RoadStroke(lineLevel, line.toArray()))
        }
        // 薄い線を先に、濃い線を後から描けるよう並べておく
        strokes.sortBy { it.level }
        return RoadNetwork(strokes, total)
    }

    /** 道の向きを区別する数（180° を [ORIENTATION_BINS] 等分。行きと帰りは同じ向き扱い）。 */
    const val ORIENTATION_BINS = 8

    /** i 番目の点での進行方向（単位ベクトル）。前後の点を結んだ向き。 */
    private fun direction(xy: DoubleArray, i: Int): Pair<Double, Double> {
        val n = xy.size / 2
        val a = maxOf(i - 1, 0)
        val b = minOf(i + 1, n - 1)
        val dx = xy[b * 2] - xy[a * 2]
        val dy = xy[b * 2 + 1] - xy[a * 2 + 1]
        val len = hypot(dx, dy)
        return if (len == 0.0) 1.0 to 0.0 else dx / len to dy / len
    }

    private fun orientationBin(xy: DoubleArray, i: Int): Int {
        val (dx, dy) = direction(xy, i)
        var angle = atan2(dy, dx)
        if (angle < 0) angle += PI
        return (angle / PI * ORIENTATION_BINS).toInt().coerceIn(0, ORIENTATION_BINS - 1)
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

/**
 * 「マス目 × 道の向き」ごとに、何回通ったかを数える。
 *
 * 通った点のまわり 3×3 マス・前後の向きにも印をつけるので、GPS のずれや少しの角度の違いは同じ道として扱う。
 * 向きを見るので、交差点で横切っただけの道は濃くならない。
 *
 * 新しく 1 回と数えるのは次のとき:
 * - 別の旅で来た
 * - 同じ旅でも、直前の地点ではそこにいなかった（＝いったん離れた）うえに、前回から [TrackGeometry.REVISIT_GAP_MS] 以上たっている
 *
 * そのため、立ち止まっていたり GPS が少しふらついたりしても回数は増えず、
 * 行って戻ってきた道や、別の日にまた歩いた道は濃くなる。
 */
private class VisitCounter {
    private class Cell {
        var count = 0
        var lastTrack = Long.MIN_VALUE
        var lastTime = 0L
        var lastSample = -1L
        // そのマス・その向きで実際に通った位置の合計（平均の線を出すため）
        var sumX = 0.0
        var sumY = 0.0
        var samples = 0
    }

    private val cells = HashMap<Long, Cell>()
    private var sample = 0L

    fun touch(x: Double, y: Double, track: Long, time: Long, bin: Int) {
        sample++
        val cx = cellIndex(x)
        val cy = cellIndex(y)
        for (dx in -1..1) for (dy in -1..1) for (db in -1..1) {
            val cell = cells.getOrPut(key(cx + dx, cy + dy, bin + db)) { Cell() }
            val isNewVisit = cell.count == 0 ||
                cell.lastTrack != track ||
                (cell.lastSample != sample - 1 && time - cell.lastTime >= TrackGeometry.REVISIT_GAP_MS)
            if (isNewVisit) cell.count++
            cell.lastTrack = track
            cell.lastTime = time
            cell.lastSample = sample
        }
        cells.getValue(key(cx, cy, bin)).apply {
            sumX += x
            sumY += y
            samples++
        }
    }

    fun count(x: Double, y: Double, bin: Int): Int = cells[key(cellIndex(x), cellIndex(y), bin)]?.count ?: 0

    /** まわりで同じ向きに通った位置の平均。 */
    fun mean(x: Double, y: Double, bin: Int): Pair<Double, Double> {
        val cx = cellIndex(x)
        val cy = cellIndex(y)
        var sx = 0.0
        var sy = 0.0
        var n = 0
        for (dx in -1..1) for (dy in -1..1) for (db in -1..1) {
            val cell = cells[key(cx + dx, cy + dy, bin + db)] ?: continue
            sx += cell.sumX
            sy += cell.sumY
            n += cell.samples
        }
        return if (n == 0) x to y else sx / n to sy / n
    }

    private fun cellIndex(v: Double): Int = floor(v / TrackGeometry.CELL_M).toInt()

    /** マス目の x, y（各 24bit）と向き（3bit）を 1 つの Long にまとめる。 */
    private fun key(cx: Int, cy: Int, bin: Int): Long {
        val b = Math.floorMod(bin, TrackGeometry.ORIENTATION_BINS).toLong()
        return ((cx.toLong() and 0xFFFFFF) shl 27) or ((cy.toLong() and 0xFFFFFF) shl 3) or b
    }
}

/** ボクシングを避ける簡易 FloatArray ビルダー。 */
private class FloatList {
    private var data = FloatArray(256)
    var size = 0
        private set

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
