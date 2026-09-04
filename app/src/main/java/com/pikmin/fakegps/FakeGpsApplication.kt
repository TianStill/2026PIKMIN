package com.pikmin.fakegps

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import org.osmdroid.config.Configuration

class FakeGpsApplication : Application() {

    companion object {
        const val CHANNEL_ID = "mock_location_service_channel"
        lateinit var instance: FakeGpsApplication
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        // 1. 初始化 OpenStreetMap (osmdroid) 設定：大幅擴增記憶體快取與並行讀取執行緒，實現秒速滑動與縮放
        val basePath = java.io.File(cacheDir, "osmdroid")
        val tileCache = java.io.File(basePath, "tiles")
        Configuration.getInstance().osmdroidBasePath = basePath
        Configuration.getInstance().osmdroidTileCache = tileCache
        Configuration.getInstance().tileDownloadThreads = 12.toShort()
        Configuration.getInstance().tileFileSystemThreads = 12.toShort()
        Configuration.getInstance().cacheMapTileCount = 300.toShort()
        Configuration.getInstance().cacheMapTileOvershoot = 30.toShort()
        Configuration.getInstance().tileDownloadMaxQueueSize = 150.toShort()
        Configuration.getInstance().expirationExtendedDuration = 1000L * 60 * 60 * 24 * 14 // 快取 14 天
        Configuration.getInstance().load(
            this,
            getSharedPreferences("osmdroid_prefs", Context.MODE_PRIVATE)
        )
        Configuration.getInstance().userAgentValue = packageName

        // 2. 建立前台常駐服務通知管道 (Notification Channel)
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = getString(R.string.mock_service_channel_name)
            val descriptionText = getString(R.string.mock_service_channel_desc)
            val importance = NotificationManager.IMPORTANCE_LOW // 靜音通知，避免頻繁發出提示音
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
                setShowBadge(false)
            }
            val notificationManager: NotificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
}
