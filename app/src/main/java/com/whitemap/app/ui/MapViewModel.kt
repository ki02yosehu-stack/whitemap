package com.whitemap.app.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.whitemap.app.WhiteMapApp
import com.whitemap.app.data.LandmarkEntity
import com.whitemap.app.data.SavedCamera
import com.whitemap.app.location.CurrentLocation
import com.whitemap.app.location.TrackingService
import com.whitemap.app.util.GpxWriter
import com.whitemap.app.util.LocalProjection
import com.whitemap.app.util.RoadNetwork
import com.whitemap.app.util.TrackGeometry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MapViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as WhiteMapApp
    private val dao = app.database.mapDao()
    private val prefs = app.prefs

    private val _projection = MutableStateFlow(prefs.origin()?.let { LocalProjection(it.first, it.second) })
    /** 地図の座標系。最初の位置が分かるまでは null（何も描けない）。 */
    val projection: StateFlow<LocalProjection?> = _projection.asStateFlow()

    val roads: StateFlow<RoadNetwork> =
        combine(dao.observePoints(), _projection.filterNotNull()) { points, proj ->
            TrackGeometry.build(points, proj)
        }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RoadNetwork.EMPTY)

    val landmarks: StateFlow<List<LandmarkEntity>> =
        dao.observeLandmarks().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val tripCount: StateFlow<Int> =
        dao.observeTripCount().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val isRecording: StateFlow<Boolean> = TrackingService.isRecording
    val currentLocation = CurrentLocation.location

    val savedCamera: SavedCamera? = prefs.camera()

    init {
        // 原点が未設定なら、記録済みの最初の点か、最初に取れた現在地を原点にする
        if (_projection.value == null) {
            viewModelScope.launch {
                val first = dao.firstPoint()
                if (first != null) {
                    setOrigin(first.lat, first.lon)
                } else {
                    val loc = CurrentLocation.location.filterNotNull().first()
                    setOrigin(loc.latitude, loc.longitude)
                }
            }
        }
    }

    private fun setOrigin(lat: Double, lon: Double) {
        if (_projection.value != null) return
        prefs.setOrigin(lat, lon)
        _projection.value = LocalProjection(lat, lon)
    }

    fun saveCamera(camera: SavedCamera) = prefs.setCamera(camera)

    fun addLandmark(lat: Double, lon: Double, name: String) {
        viewModelScope.launch {
            dao.insertLandmark(
                LandmarkEntity(lat = lat, lon = lon, name = name.trim(), createdAt = System.currentTimeMillis())
            )
        }
    }

    fun renameLandmark(landmark: LandmarkEntity, name: String) {
        viewModelScope.launch { dao.updateLandmark(landmark.copy(name = name.trim())) }
    }

    fun deleteLandmark(landmark: LandmarkEntity) {
        viewModelScope.launch { dao.deleteLandmark(landmark) }
    }

    fun clearAll() {
        viewModelScope.launch { dao.clearAll() }
    }

    /** GPX ファイルとして書き出す。成功したら true。 */
    suspend fun exportGpx(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val points = dao.allPoints()
            val landmarks = dao.allLandmarks()
            app.contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use { w ->
                GpxWriter.write(w, points, landmarks)
            } ?: error("cannot open $uri")
        }.isSuccess
    }
}
