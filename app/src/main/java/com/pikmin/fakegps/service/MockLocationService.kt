package com.pikmin.fakegps.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.annotation.MainThread
import androidx.core.app.NotificationCompat
import com.pikmin.fakegps.FakeGpsApplication
import com.pikmin.fakegps.R
import com.pikmin.fakegps.data.model.LocationPoint
import com.pikmin.fakegps.data.repository.PreferencesRepo
import com.pikmin.fakegps.drone.DroneScannerManager
import com.pikmin.fakegps.ui.MainActivity
import com.pikmin.fakegps.utils.PermissionHelper
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.Locale

class MockLocationService : Service() {
    companion object {
        const val ACTION_START = "com.pikmin.fakegps.ACTION_START"
        const val ACTION_STOP = "com.pikmin.fakegps.ACTION_STOP"
        private const val NOTIFICATION_ID = 1001
        private val gate = SessionGate()
        val sessionToken: Long? get() = gate.currentToken
        private var instance: MockLocationService? = null
        private val _isRunning = MutableStateFlow(false)
        val isRunning = _isRunning.asStateFlow()
        private val _currentLocation = MutableStateFlow<LocationPoint?>(null)
        val currentLocation = _currentLocation.asStateFlow()
        private val _error = MutableStateFlow<String?>(null)
        val error = _error.asStateFlow()
        val engineInstance: MockLocationEngine? get() = instance?.engine

        @MainThread
        fun start(context: Context, point: LocationPoint): Boolean {
            if (!PermissionHelper.hasLocationPermission(context) || !PermissionHelper.isMockLocationApp(context)) {
                _error.value = "請授予定位權限，並在開發人員選項選取本 APP 為模擬位置程式"
                return false
            }
            _error.value = null
            val token = gate.begin()
            return try {
                context.startForegroundService(Intent(context, MockLocationService::class.java).apply {
                    action = ACTION_START
                    putExtra("generation", token)
                    putExtra("lat", point.latitude)
                    putExtra("lng", point.longitude)
                })
                true
            } catch (e: Exception) {
                gate.stop()
                _error.value = "無法啟動定位：${e.localizedMessage}"
                false
            }
        }

        /** Updates never start a service. Callers and engine run on the main thread. */
        @MainThread
        fun updateLocation(context: Context, point: LocationPoint): Boolean {
            val service = instance ?: return false
            if (!_isRunning.value) return false
            return service.applyLocation(point)
        }

        @MainThread
        fun stop(context: Context) {
            gate.stop()
            DroneScannerManager.release()
            instance?.stopSession()
            context.getSharedPreferences("mock_session", Context.MODE_PRIVATE).edit().clear().apply()
            context.stopService(Intent(context, MockLocationService::class.java))
        }

        @MainThread
        fun enableMediaProjection(): Boolean {
            val service = instance ?: return false
            val point = _currentLocation.value ?: return false
            if (!_isRunning.value) return false
            return try {
                service.projectionActive = true
                service.promote(point)
                true
            } catch (e: Exception) {
                service.projectionActive = false
                _error.value = "無法啟動螢幕擷取：${e.localizedMessage}"
                false
            }
        }

        @MainThread
        fun disableMediaProjection() {
            val service = instance ?: return
            if (!service.projectionActive) return
            service.projectionActive = false
            val point = _currentLocation.value ?: return
            if (_isRunning.value) runCatching { service.promote(point) }
        }
    }

    private lateinit var engine: MockLocationEngine
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var projectionActive = false
    private val session by lazy { getSharedPreferences("mock_session", MODE_PRIVATE) }

    override fun onCreate() {
        super.onCreate()
        instance = this
        engine = MockLocationEngine(this).apply { enableJitter = PreferencesRepo(this@MockLocationService).enableJitter }
        scope.launch {
            com.pikmin.fakegps.update.AppUpdateManager.checkForUpdates(com.pikmin.fakegps.BuildConfig.VERSION_NAME, true)?.let {
                com.pikmin.fakegps.update.AppUpdateManager.announceUpdate(this@MockLocationService, it)
            }
        }
        scope.launch { engine.currentLocation.collect { _currentLocation.value = it } }
        scope.launch {
            engine.error.collect { message ->
                if (message != null) {
                    _error.value = message
                    stopSession()
                    stopSelf()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                if (!gate.accepts(intent.getLongExtra("generation", -1))) {
                    if (!_isRunning.value) stopSelfResult(startId)
                    return START_NOT_STICKY
                }
                startSession(LocationPoint(intent.getDoubleExtra("lat", Double.NaN), intent.getDoubleExtra("lng", Double.NaN)))
            }
            ACTION_STOP -> { gate.stop(); stopSession(); stopSelf() }
            null -> {
                if (session.getBoolean("active", false)) {
                    gate.begin()
                    startSession(LocationPoint(
                        session.getString("lat", null)?.toDoubleOrNull() ?: Double.NaN,
                        session.getString("lng", null)?.toDoubleOrNull() ?: Double.NaN
                    ))
                } else stopSelf()
            }
        }
        return if (_isRunning.value) START_STICKY else START_NOT_STICKY
    }

    private fun startSession(point: LocationPoint) {
        try {
            promote(point)
            engine.enableJitter = PreferencesRepo(this).enableJitter
            engine.startMocking(point)
            _isRunning.value = true
            _currentLocation.value = point
            saveSession(point)
        } catch (e: Exception) {
            _error.value = "定位啟動失敗：${e.localizedMessage}"
            stopSession()
            stopSelf()
        }
    }

    private fun applyLocation(point: LocationPoint): Boolean = try {
        engine.updateLocation(point)
        _currentLocation.value = point
        saveSession(point)
        promote(point)
        true
    } catch (e: Exception) {
        _error.value = "定位更新失敗：${e.localizedMessage}"
        stopSession()
        stopSelf()
        false
    }

    private fun saveSession(point: LocationPoint) {
        session.edit().putBoolean("active", true).putString("lat", point.latitude.toString())
            .putString("lng", point.longitude.toString()).apply()
        PreferencesRepo(this).saveLocation(point.latitude, point.longitude)
    }

    private fun stopSession() {
        _isRunning.value = false
        projectionActive = false
        DroneScannerManager.release()
        engine.stopMocking()
        _currentLocation.value = null
        session.edit().clear().apply()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun promote(point: LocationPoint) {
        val notification = buildNotification(point)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or
                (if (projectionActive) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0)
            startForeground(NOTIFICATION_ID, notification, type)
        } else startForeground(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(point: LocationPoint): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, MockLocationService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, FakeGpsApplication.CHANNEL_ID)
            .setContentTitle(getString(R.string.mock_running_title))
            .setContentText(String.format(Locale.US, "緯度: %.5f, 經度: %.5f", point.latitude, point.longitude))
            .setSmallIcon(R.drawable.ic_launcher_foreground).setContentIntent(open)
            .addAction(android.R.drawable.ic_media_pause, "停止全部", stop).setOngoing(true).build()
    }

    override fun onDestroy() {
        _isRunning.value = false
        projectionActive = false
        DroneScannerManager.release()
        engine.release()
        scope.cancel()
        _currentLocation.value = null
        instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun dump(fd: java.io.FileDescriptor, writer: java.io.PrintWriter, args: Array<out String>?) {
        writer.println(DroneScannerManager.diagnosticSummary())
    }
}
