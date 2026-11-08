package com.whitemap.app.location

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.whitemap.app.MainActivity
import com.whitemap.app.R
import com.whitemap.app.WhiteMapApp
import com.whitemap.app.data.PointEntity
import com.whitemap.app.data.TrackEntity
import com.whitemap.app.util.Geo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 画面を消していても、通った道を記録し続けるフォアグラウンドサービス。
 */
class TrackingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeLock = Mutex()
    private var stopUpdates: (() -> Unit)? = null
    private var trackId: Long? = null
    private var lastAccepted: Location? = null

    private val dao by lazy { (application as WhiteMapApp).database.mapDao() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopTracking()
            return START_NOT_STICKY
        }
        if (_isRecording.value) return START_STICKY
        if (!hasLocationPermission()) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            startInForeground()
        } catch (e: Exception) {
            // 位置情報の権限がない、バックグラウンドから起動できない等
            stopSelf()
            return START_NOT_STICKY
        }
        startTracking()
        return START_STICKY
    }

    private fun startInForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            )
        )
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, TrackingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setContentIntent(openApp)
            .addAction(0, getString(R.string.notification_stop), stop)
            .setOngoing(true)
            .setSilent(true)
            .build()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        } else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    private fun startTracking() {
        _isRecording.value = true
        lastAccepted = null
        scope.launch {
            val id = dao.insertTrack(TrackEntity(startTime = System.currentTimeMillis()))
            trackId = id
            launch(Dispatchers.Main) {
                if (!_isRecording.value) return@launch
                stopUpdates = requestLocationUpdates(
                    intervalMillis = 2_000,
                    minDistanceMeters = 3f,
                    onLocation = ::onLocation,
                )
            }
        }
    }

    private fun onLocation(location: Location) {
        CurrentLocation.update(location)
        val id = trackId ?: return
        if (!accept(location)) return
        lastAccepted = location
        val point = PointEntity(
            trackId = id,
            lat = location.latitude,
            lon = location.longitude,
            time = location.time,
            accuracy = location.accuracy,
        )
        scope.launch { writeLock.withLock { dao.insertPoint(point) } }
    }

    /** 精度の悪い点・ありえない跳びを捨てる。 */
    private fun accept(location: Location): Boolean {
        if (location.hasAccuracy() && location.accuracy > MAX_ACCURACY_M) return false
        // 起動直後のキャッシュされた古い位置は使わない
        if (System.currentTimeMillis() - location.time > 60_000) return false
        val last = lastAccepted ?: return true
        if (location.time <= last.time) return false
        val d = Geo.distanceMeters(last.latitude, last.longitude, location.latitude, location.longitude)
        if (d < MIN_STEP_M) return false
        val speed = d / ((location.time - last.time) / 1000.0)
        return speed < MAX_SPEED_MPS
    }

    private fun stopTracking() {
        stopUpdates?.invoke()
        stopUpdates = null
        _isRecording.value = false
        val id = trackId
        trackId = null
        if (id != null) {
            scope.launch { writeLock.withLock { dao.finishTrack(id, System.currentTimeMillis()) } }
        }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopUpdates?.invoke()
        _isRecording.value = false
        trackId?.let { id ->
            runBlocking(Dispatchers.IO) { runCatching { dao.finishTrack(id, System.currentTimeMillis()) } }
        }
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "tracking"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "com.whitemap.app.STOP"

        private const val MAX_ACCURACY_M = 40f
        private const val MIN_STEP_M = 3.0
        private const val MAX_SPEED_MPS = 120.0 // これ以上速い移動は GPS の誤差とみなす

        private val _isRecording = MutableStateFlow(false)
        val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, TrackingService::class.java))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, TrackingService::class.java).setAction(ACTION_STOP))
        }
    }
}
