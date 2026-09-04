package com.pikmin.fakegps.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.pikmin.fakegps.BuildConfig
import com.pikmin.fakegps.FakeGpsApplication
import com.pikmin.fakegps.R
import com.pikmin.fakegps.data.model.LocationPoint
import com.pikmin.fakegps.ui.MainActivity
import com.pikmin.fakegps.update.AppReleaseInfo
import com.pikmin.fakegps.update.AppUpdateManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

class MockLocationService : Service() {

    companion object {
        const val ACTION_START = "com.pikmin.fakegps.ACTION_START"
        const val ACTION_STOP = "com.pikmin.fakegps.ACTION_STOP"
        const val ACTION_UPDATE = "com.pikmin.fakegps.ACTION_UPDATE"
        const val ACTION_ENABLE_MEDIA_PROJECTION = "com.pikmin.fakegps.ACTION_ENABLE_MEDIA_PROJECTION"
        const val ACTION_RESUME_DRONE_SCAN = "com.pikmin.fakegps.ACTION_RESUME_DRONE_SCAN"
        const val ACTION_INSTALL_UPDATE = "com.pikmin.fakegps.ACTION_INSTALL_UPDATE"

        const val EXTRA_LAT = "extra_lat"
        const val EXTRA_LNG = "extra_lng"
        const val EXTRA_BEARING = "extra_bearing"
        const val EXTRA_SPEED = "extra_speed"

        private const val NOTIFICATION_ID = 1001
        private const val UPDATE_NOTIFICATION_ID = 2002

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        private val _currentLocation = MutableStateFlow<LocationPoint?>(null)
        val currentLocation: StateFlow<LocationPoint?> = _currentLocation.asStateFlow()

        var engineInstance: MockLocationEngine? = null

        fun start(context: Context, point: LocationPoint) {
            val intent = Intent(context, MockLocationService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_LAT, point.latitude)
                putExtra(EXTRA_LNG, point.longitude)
                putExtra(EXTRA_BEARING, point.bearing)
                putExtra(EXTRA_SPEED, point.speed)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun enableMediaProjection(context: Context) {
            val intent = Intent(context, MockLocationService::class.java).apply {
                action = ACTION_ENABLE_MEDIA_PROJECTION
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun updateLocation(context: Context, point: LocationPoint) {
            val intent = Intent(context, MockLocationService::class.java).apply {
                action = ACTION_UPDATE
                putExtra(EXTRA_LAT, point.latitude)
                putExtra(EXTRA_LNG, point.longitude)
                putExtra(EXTRA_BEARING, point.bearing)
                putExtra(EXTRA_SPEED, point.speed)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, MockLocationService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    private lateinit var engine: MockLocationEngine
    private val scope = CoroutineScope(Dispatchers.Main)
    private var clipboardListener: android.content.ClipboardManager.OnPrimaryClipChangedListener? = null

    override fun onCreate() {
        super.onCreate()
        engine = MockLocationEngine(this)
        engineInstance = engine

        scope.launch {
            engine.currentLocation.collect { point ->
                _currentLocation.value = point
            }
        }

        setupBackgroundClipboardListener()
    }

    private fun setupBackgroundClipboardListener() {
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
            clipboardListener = android.content.ClipboardManager.OnPrimaryClipChangedListener {
                val prefs = com.pikmin.fakegps.data.repository.PreferencesRepo(this)
                if (!prefs.isAutoClipboardEnabled) return@OnPrimaryClipChangedListener

                try {
                    val clipData = clipboard?.primaryClip
                    if (clipData != null && clipData.itemCount > 0) {
                        val text = clipData.getItemAt(0).text?.toString() ?: ""
                        if (text.isNotBlank() && text != prefs.lastProcessedClipboard) {
                            val extracted = com.pikmin.fakegps.utils.GeoUtils.extractCoordinatesFromText(text)
                            if (extracted != null) {
                                prefs.lastProcessedClipboard = text
                                val newPoint = LocationPoint(
                                    latitude = extracted.latitude,
                                    longitude = extracted.longitude,
                                    bearing = _currentLocation.value?.bearing ?: 0f,
                                    speed = _currentLocation.value?.speed ?: 0f
                                )
                                engine.updateLocation(newPoint)
                                prefs.lastLatitude = extracted.latitude
                                prefs.lastLongitude = extracted.longitude

                                val noteInfo = if (extracted.note.isNotBlank()) " (${extracted.note})" else ""
                                android.os.Handler(android.os.Looper.getMainLooper()).post {
                                    android.widget.Toast.makeText(
                                        this,
                                        "📍 背景已捕捉新座標：${String.format("%.5f, %.5f", extracted.latitude, extracted.longitude)}$noteInfo，已直接定位！",
                                        android.widget.Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            clipboard?.addPrimaryClipChangedListener(clipboardListener)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 在背景輕量檢查 GitHub 是否有新版本發布
        scope.launch {
            try {
                val release = AppUpdateManager.checkForUpdates(BuildConfig.VERSION_NAME, silentCheck = true)
                if (release != null) {
                    showUpdateNotification(release)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val lat = intent.getDoubleExtra(EXTRA_LAT, 0.0)
                val lng = intent.getDoubleExtra(EXTRA_LNG, 0.0)
                val bearing = intent.getFloatExtra(EXTRA_BEARING, 0f)
                val speed = intent.getFloatExtra(EXTRA_SPEED, 0f)

                val point = LocationPoint(
                    latitude = lat,
                    longitude = lng,
                    bearing = bearing,
                    speed = speed
                )
                startForegroundWithNotification(point)
                try {
                    engine.startMocking(point)
                    _isRunning.value = true
                } catch (e: Exception) {
                    stopSelf()
                }
            }

            ACTION_ENABLE_MEDIA_PROJECTION -> {
                val point = _currentLocation.value ?: LocationPoint(0.0, 0.0)
                val notification = buildNotification(point)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                    )
                }
            }

            ACTION_UPDATE -> {
                val lat = intent.getDoubleExtra(EXTRA_LAT, 0.0)
                val lng = intent.getDoubleExtra(EXTRA_LNG, 0.0)
                val bearing = intent.getFloatExtra(EXTRA_BEARING, 0f)
                val speed = intent.getFloatExtra(EXTRA_SPEED, 0f)

                val point = LocationPoint(
                    latitude = lat,
                    longitude = lng,
                    bearing = bearing,
                    speed = speed
                )
                if (!engine.isMocking.value) {
                    startForegroundWithNotification(point)
                    try {
                        engine.startMocking(point)
                        _isRunning.value = true
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                } else {
                    engine.updateLocation(point)
                }
            }

            ACTION_STOP -> {
                engine.stopMocking()
                _isRunning.value = false
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }

            ACTION_RESUME_DRONE_SCAN -> {
                com.pikmin.fakegps.drone.DroneScannerManager.resumeScan(this)
            }

            ACTION_INSTALL_UPDATE -> {
                val release = AppUpdateManager.latestReleaseInfo
                if (release != null) {
                    scope.launch {
                        showDownloadProgressNotification(0)
                        val apkFile = AppUpdateManager.downloadApk(this@MockLocationService, release)
                        if (apkFile != null) {
                            showInstallReadyNotification(apkFile)
                            AppUpdateManager.installApk(this@MockLocationService, apkFile)
                        } else {
                            clearUpdateNotification()
                        }
                    }
                }
            }
        }
        return START_STICKY
    }

    private fun startForegroundWithNotification(point: LocationPoint) {
        val notification = buildNotification(point)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(point: LocationPoint): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, MockLocationService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, FakeGpsApplication.CHANNEL_ID)
            .setContentTitle(getString(R.string.mock_running_title))
            .setContentText(String.format("緯度: %.5f, 經度: %.5f", point.latitude, point.longitude))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .addAction(
                android.R.drawable.ic_media_pause,
                getString(R.string.btn_stop),
                stopPendingIntent
            )
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        clearUpdateNotification()
        clipboardListener?.let {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
            clipboard?.removePrimaryClipChangedListener(it)
        }
        engine.stopMocking()
        _isRunning.value = false
        engineInstance = null
    }

    private fun showUpdateNotification(release: AppReleaseInfo) {
        try {
            val installIntent = Intent(this, MockLocationService::class.java).apply {
                action = ACTION_INSTALL_UPDATE
            }
            val pendingIntent = PendingIntent.getService(
                this,
                101,
                installIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(this, FakeGpsApplication.CHANNEL_ID)
                .setContentTitle("🔔 發現新版本 ${release.tagName}")
                .setContentText("點擊直接在遊戲中下載並安裝更新")
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .addAction(
                    android.R.drawable.stat_sys_download,
                    "立即下載更新",
                    pendingIntent
                )
                .build()

            NotificationManagerCompat.from(this).notify(UPDATE_NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun showDownloadProgressNotification(progress: Int) {
        try {
            val notification = NotificationCompat.Builder(this, FakeGpsApplication.CHANNEL_ID)
                .setContentTitle("正在下載更新檔...")
                .setContentText("下載進度: $progress%")
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setProgress(100, progress, false)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()

            NotificationManagerCompat.from(this).notify(UPDATE_NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun showInstallReadyNotification(apkFile: File) {
        try {
            val installIntent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                this,
                102,
                installIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(this, FakeGpsApplication.CHANNEL_ID)
                .setContentTitle("✅ 更新檔下載完成")
                .setContentText("點擊開始安裝更新")
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()

            NotificationManagerCompat.from(this).notify(UPDATE_NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun clearUpdateNotification() {
        try {
            NotificationManagerCompat.from(this).cancel(UPDATE_NOTIFICATION_ID)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
