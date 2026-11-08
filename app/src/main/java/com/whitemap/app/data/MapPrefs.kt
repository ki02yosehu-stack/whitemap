package com.whitemap.app.data

import android.content.Context

/**
 * 地図の原点（ローカル座標系の基準点）とカメラ位置を保存する。
 * 原点は最初に位置を取得した場所で固定し、以後変えない。
 */
class MapPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("whitemap", Context.MODE_PRIVATE)

    fun origin(): Pair<Double, Double>? {
        if (!prefs.contains(KEY_ORIGIN_LAT)) return null
        return Double.fromBits(prefs.getLong(KEY_ORIGIN_LAT, 0)) to
            Double.fromBits(prefs.getLong(KEY_ORIGIN_LON, 0))
    }

    fun setOrigin(lat: Double, lon: Double) {
        prefs.edit()
            .putLong(KEY_ORIGIN_LAT, lat.toRawBits())
            .putLong(KEY_ORIGIN_LON, lon.toRawBits())
            .apply()
    }

    fun camera(): SavedCamera? {
        if (!prefs.contains(KEY_CAM_X)) return null
        return SavedCamera(
            x = Double.fromBits(prefs.getLong(KEY_CAM_X, 0)),
            y = Double.fromBits(prefs.getLong(KEY_CAM_Y, 0)),
            scale = prefs.getFloat(KEY_CAM_SCALE, 1f),
            follow = prefs.getBoolean(KEY_CAM_FOLLOW, true),
        )
    }

    fun setCamera(camera: SavedCamera) {
        prefs.edit()
            .putLong(KEY_CAM_X, camera.x.toRawBits())
            .putLong(KEY_CAM_Y, camera.y.toRawBits())
            .putFloat(KEY_CAM_SCALE, camera.scale)
            .putBoolean(KEY_CAM_FOLLOW, camera.follow)
            .apply()
    }

    private companion object {
        const val KEY_ORIGIN_LAT = "origin_lat"
        const val KEY_ORIGIN_LON = "origin_lon"
        const val KEY_CAM_X = "cam_x"
        const val KEY_CAM_Y = "cam_y"
        const val KEY_CAM_SCALE = "cam_scale"
        const val KEY_CAM_FOLLOW = "cam_follow"
    }
}

data class SavedCamera(val x: Double, val y: Double, val scale: Float, val follow: Boolean)
