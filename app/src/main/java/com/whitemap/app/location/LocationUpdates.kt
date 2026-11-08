package com.whitemap.app.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.location.LocationListenerCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.location.LocationRequestCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 画面と記録サービスで共有する「いまの現在地」。 */
object CurrentLocation {
    private val _location = MutableStateFlow<Location?>(null)
    val location: StateFlow<Location?> = _location.asStateFlow()

    fun update(location: Location) {
        val old = _location.value
        if (old == null || location.time >= old.time) _location.value = location
    }
}

fun Context.hasLocationPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED

/**
 * Google Play 開発者サービスに依存せず、端末の LocationManager から位置を受け取る。
 * 戻り値を呼ぶと購読を解除する。
 */
@SuppressLint("MissingPermission")
fun Context.requestLocationUpdates(
    intervalMillis: Long,
    minDistanceMeters: Float,
    onLocation: (Location) -> Unit,
): () -> Unit {
    if (!hasLocationPermission()) return {}
    val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
    val listener = LocationListenerCompat { onLocation(it) }
    val request = LocationRequestCompat.Builder(intervalMillis)
        .setQuality(LocationRequestCompat.QUALITY_HIGH_ACCURACY)
        .setMinUpdateDistanceMeters(minDistanceMeters)
        .build()
    val executor = ContextCompat.getMainExecutor(this)

    val providers = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            lm.hasProvider(LocationManager.FUSED_PROVIDER)
        ) {
            add(LocationManager.FUSED_PROVIDER)
        } else {
            if (lm.allProviders.contains(LocationManager.GPS_PROVIDER)) add(LocationManager.GPS_PROVIDER)
            if (lm.allProviders.contains(LocationManager.NETWORK_PROVIDER)) add(LocationManager.NETWORK_PROVIDER)
        }
    }
    for (provider in providers) {
        runCatching {
            lm.getLastKnownLocation(provider)?.let(onLocation)
            LocationManagerCompat.requestLocationUpdates(lm, provider, request, executor, listener)
        }
    }
    return { runCatching { LocationManagerCompat.removeUpdates(lm, listener) } }
}
