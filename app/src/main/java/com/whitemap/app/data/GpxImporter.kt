package com.whitemap.app.data

import androidx.room.withTransaction
import com.whitemap.app.util.GpxData
import com.whitemap.app.util.GpxReader
import java.io.InputStream

/** 読みこみの結果。 */
data class ImportResult(
    val trips: Int,
    val landmarks: Int,
    /** すでに地図にあったので飛ばした数（旅と目印の合計）。 */
    val skipped: Int,
    /** 読みこんだ最初の地点（地図の基準点が未設定のときに使う）。 */
    val firstPoint: Pair<Double, Double>?,
)

/**
 * GPX ファイルの道と目印を地図に足す。いまある記録は消さない。
 * 同じファイルを 2 回読みこんでも二重にならないよう、すでにある旅・目印は飛ばす
 * （二重になると、通った回数が増えて道が濃くなってしまう）。
 */
class GpxImporter(private val database: AppDatabase) {
    private val dao = database.mapDao()

    suspend fun import(input: InputStream, now: Long = System.currentTimeMillis()): ImportResult =
        import(GpxReader.read(input), now)

    suspend fun import(data: GpxData, now: Long = System.currentTimeMillis()): ImportResult =
        database.withTransaction {
            var trips = 0
            var landmarks = 0
            var skipped = 0
            var firstPoint: Pair<Double, Double>? = null
            var fallbackTime = now

            for (track in data.tracks) {
                val first = track.first()
                val firstTime = first.time
                // 書き出すと時刻が秒単位に丸められるので、前後 1 秒の幅で探す
                if (firstTime != null &&
                    dao.countPointsNear(firstTime - 1_000, firstTime + 1_000, first.lat, first.lon) > 0
                ) {
                    skipped++
                    continue
                }
                // 時刻のない GPX（他のアプリで作った経路など）は 1 秒ごとに通ったことにする
                val times = track.map { it.time ?: fallbackTime.also { fallbackTime += 1_000 } }
                val trackId = dao.insertTrack(TrackEntity(startTime = times.first(), endTime = times.last()))
                dao.insertPoints(
                    track.mapIndexed { i, p ->
                        PointEntity(trackId = trackId, lat = p.lat, lon = p.lon, time = times[i], accuracy = 0f)
                    }
                )
                if (firstPoint == null) firstPoint = first.lat to first.lon
                trips++
            }

            for (w in data.waypoints) {
                val name = w.name.ifBlank { "目印" }
                if (dao.countLandmarksAt(w.lat, w.lon, name) > 0) {
                    skipped++
                    continue
                }
                dao.insertLandmark(LandmarkEntity(lat = w.lat, lon = w.lon, name = name, createdAt = w.time ?: now))
                if (firstPoint == null) firstPoint = w.lat to w.lon
                landmarks++
            }
            ImportResult(trips, landmarks, skipped, firstPoint)
        }
}
