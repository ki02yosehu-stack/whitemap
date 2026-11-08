package com.whitemap.app.util

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

object Geo {
    private const val EARTH_RADIUS_M = 6_371_008.8

    /** 2点間の距離（メートル、Haversine）。 */
    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return 2 * EARTH_RADIUS_M * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }
}

/**
 * 原点まわりの局所平面座標（単位: メートル、x=東、y=南＝画面の下方向）。
 * 個人が歩き回る範囲（数百km）なら十分な精度で、地図タイルも不要。
 */
class LocalProjection(val originLat: Double, val originLon: Double) {
    private val metersPerDegLat = 111_132.954
    private val metersPerDegLon = 111_319.488 * cos(Math.toRadians(originLat))

    fun x(lon: Double): Double = (lon - originLon) * metersPerDegLon
    fun y(lat: Double): Double = -(lat - originLat) * metersPerDegLat

    fun lon(x: Double): Double = originLon + x / metersPerDegLon
    fun lat(y: Double): Double = originLat - y / metersPerDegLat
}

/** 地図上の位置（ローカル座標）。 */
data class WorldPoint(val x: Double, val y: Double)
