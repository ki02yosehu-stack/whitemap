package com.whitemap.app.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface MapDao {
    // --- tracks ---
    @Insert
    suspend fun insertTrack(track: TrackEntity): Long

    @Query("UPDATE tracks SET endTime = :endTime WHERE id = :id")
    suspend fun finishTrack(id: Long, endTime: Long)

    @Query("SELECT * FROM tracks ORDER BY startTime")
    suspend fun allTracks(): List<TrackEntity>

    @Query("SELECT COUNT(DISTINCT trackId) FROM points")
    fun observeTripCount(): Flow<Int>

    // --- points ---
    @Insert
    suspend fun insertPoint(point: PointEntity)

    @Query("SELECT * FROM points ORDER BY trackId, time, id")
    fun observePoints(): Flow<List<PointEntity>>

    @Query("SELECT * FROM points ORDER BY trackId, time, id")
    suspend fun allPoints(): List<PointEntity>

    @Query("SELECT * FROM points ORDER BY id LIMIT 1")
    suspend fun firstPoint(): PointEntity?

    // --- landmarks ---
    @Query("SELECT * FROM landmarks ORDER BY createdAt")
    fun observeLandmarks(): Flow<List<LandmarkEntity>>

    @Query("SELECT * FROM landmarks ORDER BY createdAt")
    suspend fun allLandmarks(): List<LandmarkEntity>

    @Insert
    suspend fun insertLandmark(landmark: LandmarkEntity): Long

    @Update
    suspend fun updateLandmark(landmark: LandmarkEntity)

    @Delete
    suspend fun deleteLandmark(landmark: LandmarkEntity)

    // --- reset ---
    @Query("DELETE FROM points")
    suspend fun deleteAllPoints()

    @Query("DELETE FROM tracks")
    suspend fun deleteAllTracks()

    @Query("DELETE FROM landmarks")
    suspend fun deleteAllLandmarks()

    @Transaction
    suspend fun clearAll() {
        deleteAllPoints()
        deleteAllTracks()
        deleteAllLandmarks()
    }
}
