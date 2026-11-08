package com.whitemap.app.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** 1回の「旅」（記録開始〜停止）。 */
@Entity(tableName = "tracks")
data class TrackEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startTime: Long,
    val endTime: Long? = null,
)

/** 実際に通った地点。これがつながって道になる。 */
@Entity(tableName = "points", indices = [Index("trackId")])
data class PointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val trackId: Long,
    val lat: Double,
    val lon: Double,
    val time: Long,
    val accuracy: Float,
)

/** 自分で書き込む目印（お気に入りの店、景色のいい場所など）。 */
@Entity(tableName = "landmarks")
data class LandmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val lat: Double,
    val lon: Double,
    val name: String,
    val createdAt: Long,
)
