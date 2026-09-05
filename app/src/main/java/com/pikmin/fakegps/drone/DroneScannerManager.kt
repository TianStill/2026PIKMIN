package com.pikmin.fakegps.drone

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.DisplayMetrics
import android.view.WindowManager
import android.widget.Toast
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.media.RingtoneManager
import androidx.core.app.NotificationCompat
import com.pikmin.fakegps.R
import com.pikmin.fakegps.ui.MainActivity
import com.pikmin.fakegps.cv.DetectedMushroom
import com.pikmin.fakegps.cv.MushroomDetector
import com.pikmin.fakegps.cv.MushroomType
import com.pikmin.fakegps.data.model.LocationPoint
import com.pikmin.fakegps.data.repository.PreferencesRepo
import com.pikmin.fakegps.service.MockLocationService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class DroneScanStatus(
    val isScanning: Boolean = false,
    val currentIndex: Int = 0,
    val totalPoints: Int = 0,
    val currentCoordinate: LocationPoint? = null,
    val foundTarget: DetectedMushroom? = null,
    val foundLocation: LocationPoint? = null,
    val statusMessage: String = "待命"
)

/**
 * 當次巡檢已記錄之蘑菇資料 (用於防止同一顆菇重複跳出)
 */
data class DiscoveredMushroomRecord(
    val type: MushroomType,
    val latitude: Double,
    val longitude: Double,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * 無人機雷達巡航與截圖檢測管理器
 */
object DroneScannerManager {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var scanJob: Job? = null

    private val _status = MutableStateFlow(DroneScanStatus())
    val status: StateFlow<DroneScanStatus> = _status.asStateFlow()

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var backgroundThread: HandlerThread? = null

    private var latestBitmap: Bitmap? = null
    private val bitmapLock = Any()

    private var lastFrameTime = 0L

    private var screenWidth = 720
    private var screenHeight = 1280
    private var screenDensity = 320

    /**
     * 設定從 Activity 取得的 MediaProjection
     */
    fun setupMediaProjection(context: Context, resultCode: Int, data: Intent): Boolean {
        return try {
            val mpManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mpManager.getMediaProjection(resultCode, data)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() {
                        releaseVirtualDisplay()
                    }
                }, Handler(Looper.getMainLooper()))
            }

            initVirtualDisplay(context)
            val success = mediaProjection != null && virtualDisplay != null
            if (success) {
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(context, "📸 遊戲畫面雷達監控已就緒！", Toast.LENGTH_SHORT).show()
                }
            }
            success
        } catch (e: Throwable) {
            e.printStackTrace()
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context, "⚠️ 螢幕擷取權限異常: ${e.message}", Toast.LENGTH_LONG).show()
            }
            false
        }
    }

    @SuppressLint("WrongConstant")
    private fun initVirtualDisplay(context: Context) {
        try {
            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)

            val ratio = metrics.widthPixels.toFloat() / metrics.heightPixels.toFloat()
            screenWidth = 720
            screenHeight = (720 / ratio).toInt().coerceAtLeast(480)
            screenDensity = metrics.densityDpi

            releaseVirtualDisplay()

            backgroundThread = HandlerThread("ImageReaderBgThread").apply { start() }
            val bgHandler = Handler(backgroundThread!!.looper)

            imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 3)
            imageReader?.setOnImageAvailableListener({ reader ->
                try {
                    val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                    val now = System.currentTimeMillis()
                    // 限制取幀頻率 (~150ms 一幀，約 6.6 FPS)，既滿足 250ms 採樣需求，又徹底避免 60FPS 頻繁記憶體配置
                    if (now - lastFrameTime < 150L) {
                        image.close()
                        return@setOnImageAvailableListener
                    }
                    lastFrameTime = now

                    val planes = image.planes
                    val buffer = planes[0].buffer
                    val pixelStride = planes[0].pixelStride
                    val rowStride = planes[0].rowStride
                    val rowPadding = rowStride - pixelStride * screenWidth

                    val width = screenWidth + rowPadding / pixelStride
                    val height = screenHeight

                    val tempBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    val requiredCapacity = tempBitmap.byteCount
                    val remaining = buffer.remaining()

                    if (remaining >= requiredCapacity) {
                        buffer.position(0)
                        tempBitmap.copyPixelsFromBuffer(buffer)
                    } else {
                        // 解決 Android 系統底層 ImageReader 最後一行 padding 缺失 bug
                        val safeBuffer = java.nio.ByteBuffer.allocateDirect(requiredCapacity)
                        buffer.position(0)
                        safeBuffer.put(buffer)
                        safeBuffer.position(0)
                        tempBitmap.copyPixelsFromBuffer(safeBuffer)
                    }
                    image.close()

                    // 🌟 必須使用原生的像素裁剪（保留原始 RGB 色彩，防止 Canvas.drawBitmap 因遊戲畫面 Alpha=0 被透明化抹除）
                    val cleanBitmap = if (rowPadding == 0) {
                        tempBitmap
                    } else {
                        val cropped = Bitmap.createBitmap(tempBitmap, 0, 0, screenWidth, screenHeight)
                        tempBitmap.recycle()
                        cropped
                    }

                    synchronized(bitmapLock) {
                        latestBitmap?.recycle()
                        latestBitmap = cleanBitmap
                    }
                } catch (e: Throwable) {
                    android.util.Log.e("DroneScanner", "Frame acquisition error: ${e.message}", e)
                }
            }, bgHandler)

            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "DroneRadarCapture",
                screenWidth,
                screenHeight,
                screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface,
                null,
                null
            )
        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }

    private fun releaseVirtualDisplay() {
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        backgroundThread?.quitSafely()
        backgroundThread = null
        synchronized(bitmapLock) {
            latestBitmap?.recycle()
            latestBitmap = null
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null

    private fun acquireWakeLock(context: Context) {
        try {
            if (wakeLock == null) {
                val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                @Suppress("DEPRECATION")
                wakeLock = pm.newWakeLock(
                    PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "FakeGps:DroneScanWakeLock"
                )
            }
            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire(30 * 60 * 1000L) // 最長保持 30 分鐘，防呆避免忘記關閉耗電
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private var currentWaypoints: List<LocationPoint> = emptyList()
    private var currentTargetTypes: Set<MushroomType> = emptySet()
    private var currentDwellSeconds: Float = 2.2f
    private var currentStartIndex: Int = 0

    // 當次巡檢已記錄之蘑菇庫 (避免相鄰航點看見同一顆菇時重複跳出警報)
    private val foundMushroomsThisSession = mutableListOf<DiscoveredMushroomRecord>()

    /**
     * 啟動無人機螺旋巡弋掃描 (從頭開始)
     */
    fun startScan(
        context: Context,
        centerLat: Double,
        centerLng: Double,
        radiusKm: Double,
        targetTypes: Set<MushroomType>,
        dwellSeconds: Float = 2.2f,
        stepMeters: Double = 360.0
    ) {
        synchronized(foundMushroomsThisSession) {
            foundMushroomsThisSession.clear()
        }

        val waypoints = DronePathGenerator.generateSpiralWaypoints(
            centerLat = centerLat,
            centerLng = centerLng,
            radiusKm = radiusKm,
            stepMeters = stepMeters
        )

        currentWaypoints = waypoints
        currentTargetTypes = targetTypes
        currentDwellSeconds = dwellSeconds
        currentStartIndex = 0

        Toast.makeText(
            context,
            "🛸 無人機出發！請確認遊戲地圖已點擊「指南針」回正正北！",
            Toast.LENGTH_SHORT
        ).show()

        startScanInternal(context, waypoints, 0, targetTypes, dwellSeconds)
    }

    /**
     * 繼續巡弋：從上次煞車停下的下一點 (N+1) 繼續向外搜尋
     */
    fun resumeScan(context: Context) {
        if (currentWaypoints.isEmpty() || currentStartIndex >= currentWaypoints.size) {
            currentStartIndex = 0
            Toast.makeText(context, "已無剩餘巡弋點，請開啟面板重新設定半徑！", Toast.LENGTH_SHORT).show()
            return
        }

        Toast.makeText(
            context,
            "🛸 無人機繼續巡航！前往第 ${currentStartIndex + 1}/${currentWaypoints.size} 點搜尋...",
            Toast.LENGTH_SHORT
        ).show()

        startScanInternal(
            context = context,
            waypoints = currentWaypoints,
            startIndex = currentStartIndex,
            targetTypes = currentTargetTypes,
            dwellSeconds = currentDwellSeconds
        )
    }

    /**
     * 依據螢幕上蘑菇像素座標與目前航點，估算蘑菇之實際地理經緯度
     */
    private fun estimateMushroomLocation(waypoint: LocationPoint, mushroom: DetectedMushroom): LocationPoint {
        val centerPxX = screenWidth / 2.0
        val centerPxY = screenHeight * 0.58
        val metersPerPixel = 450.0 / screenWidth.coerceAtLeast(1)
        val dxMeters = (mushroom.x - centerPxX) * metersPerPixel
        val dyMeters = (centerPxY - mushroom.y) * metersPerPixel

        val metersPerLat = 111132.954
        val metersPerLng = 111132.954 * kotlin.math.cos(Math.toRadians(waypoint.latitude))

        val estLat = waypoint.latitude + (dyMeters / metersPerLat)
        val estLng = waypoint.longitude + (dxMeters / metersPerLng)
        return LocationPoint(estLat, estLng)
    }

    /**
     * 檢查此目標蘑菇是否已在當次巡航中被發現並提醒過
     * 同種類蘑菇距離 180m 內視為同一顆；不同種類則需相距 50m 內才視為同一實體
     */
    private fun isAlreadyDiscovered(estimatedLoc: LocationPoint, type: MushroomType): Boolean {
        synchronized(foundMushroomsThisSession) {
            return foundMushroomsThisSession.any { past ->
                val dist = calculateDistanceMeters(
                    estimatedLoc.latitude, estimatedLoc.longitude,
                    past.latitude, past.longitude
                )
                if (past.type == type) {
                    dist < 180.0
                } else {
                    dist < 50.0
                }
            }
        }
    }

    private fun calculateDistanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0 // 地球半徑 (公尺)
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = kotlin.math.sin(dLat / 2) * kotlin.math.sin(dLat / 2) +
                kotlin.math.cos(Math.toRadians(lat1)) * kotlin.math.cos(Math.toRadians(lat2)) *
                kotlin.math.sin(dLon / 2) * kotlin.math.sin(dLon / 2)
        val c = 2 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
        return r * c
    }

    private fun startScanInternal(
        context: Context,
        waypoints: List<LocationPoint>,
        startIndex: Int,
        targetTypes: Set<MushroomType>,
        dwellSeconds: Float
    ) {
        stopScan()
        acquireWakeLock(context)

        val prefs = PreferencesRepo(context)
        val startPoint = waypoints.getOrNull(startIndex) ?: waypoints.firstOrNull() ?: LocationPoint(0.0, 0.0)

        // 確保 Mock 定位服務已就緒啟動
        MockLocationService.start(context, startPoint)

        _status.value = DroneScanStatus(
            isScanning = true,
            currentIndex = startIndex,
            totalPoints = waypoints.size,
            currentCoordinate = startPoint,
            foundTarget = null,
            foundLocation = null,
            statusMessage = "🛸 無人機巡弋中 (${startIndex + 1}/${waypoints.size})..."
        )

        scanJob = scope.launch(Dispatchers.Default) {
            val dwellMillis = (dwellSeconds * 1000).toLong().coerceAtLeast(1400L)
            val initialDelay = if (dwellMillis <= 2000L) 850L else 1100L

            var consecutiveNullFrames = 0

            for (index in startIndex until waypoints.size) {
                if (!isActive) break

                val waypoint = waypoints[index]

                withContext(Dispatchers.Main) {
                    _status.value = _status.value.copy(
                        currentIndex = index + 1,
                        currentCoordinate = waypoint,
                        statusMessage = "📍 巡弋點 ${index + 1}/${waypoints.size} (座標: ${String.format("%.4f, %.4f", waypoint.latitude, waypoint.longitude)})"
                    )

                    // 瞬移 GPS 座標至巡弋點
                    prefs.lastLatitude = waypoint.latitude
                    prefs.lastLongitude = waypoint.longitude
                    MockLocationService.updateLocation(context, waypoint)
                }

                // 🌟 連續動態採樣窗口：克服 Pikmin Bloom 伺服器載入 3D 蘑菇延遲問題
                delay(initialDelay)

                val startTime = System.currentTimeMillis()
                var foundMushroom: Pair<DetectedMushroom, LocationPoint>? = null
                var capturedFramesAtThisPoint = 0

                // 在停留窗口內動態採樣，檢查是否有當次未發現過的新蘑菇
                while (System.currentTimeMillis() - startTime < (dwellMillis - initialDelay) && isActive) {
                    try {
                        val capturedBitmap = captureCurrentScreen()
                        if (capturedBitmap != null) {
                            capturedFramesAtThisPoint++
                            consecutiveNullFrames = 0
                            val detected = MushroomDetector.detectMushrooms(capturedBitmap, targetTypes)
                            
                            // 排除當次巡航已發現過的同一顆蘑菇
                            val newMushroom = detected.firstOrNull { m ->
                                val estLoc = estimateMushroomLocation(waypoint, m)
                                !isAlreadyDiscovered(estLoc, m.type)
                            }
                            if (newMushroom != null) {
                                val estLoc = estimateMushroomLocation(waypoint, newMushroom)
                                foundMushroom = Pair(newMushroom, estLoc)
                                break // 成功捕捉到全新目標！立刻停止採樣
                            }
                        }
                    } catch (e: Throwable) {
                        e.printStackTrace()
                    }
                    delay(250L)
                }

                if (capturedFramesAtThisPoint == 0) {
                    consecutiveNullFrames++
                    if (consecutiveNullFrames == 2) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "⚠️ 尚未取得螢幕畫面（可能權限中斷），請確認啟動無人機時有允許「立即開始錄製螢幕」！", Toast.LENGTH_LONG).show()
                        }
                    }
                }

                if (!isActive) break

                if (foundMushroom != null) {
                    val (bestTarget, estLoc) = foundMushroom
                    synchronized(foundMushroomsThisSession) {
                        foundMushroomsThisSession.add(
                            DiscoveredMushroomRecord(bestTarget.type, estLoc.latitude, estLoc.longitude)
                        )
                    }
                    currentStartIndex = index + 1 // 下次繼續時從下一點開始！
                    withContext(Dispatchers.Main) {
                        // 發現目標！
                        onTargetDiscovered(context, bestTarget, estLoc, prefs)
                    }
                    break // 發現目標立即停止巡航，鎖定座標！
                }
            }

            withContext(Dispatchers.Main) {
                if (_status.value.foundTarget == null) {
                    currentStartIndex = 0 // 巡弋結束重置起點
                    _status.value = _status.value.copy(
                        isScanning = false,
                        statusMessage = "✅ 本輪網格巡弋完畢，已無更多目標"
                    )
                    Toast.makeText(context, "✅ 無人機巡弋完畢！", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun captureCurrentScreen(): Bitmap? {
        synchronized(bitmapLock) {
            val current = latestBitmap ?: return null
            if (current.isRecycled) return null
            return try {
                current.copy(Bitmap.Config.ARGB_8888, false)
            } catch (e: Throwable) {
                null
            }
        }
    }

    private fun onTargetDiscovered(
        context: Context,
        target: DetectedMushroom,
        location: LocationPoint,
        prefs: PreferencesRepo
    ) {
        val label = MushroomType.getDisplayName(target.type)

        _status.value = _status.value.copy(
            isScanning = false,
            foundTarget = target,
            foundLocation = location,
            statusMessage = "🎯 成功發現【$label】！已自動鎖定座標！"
        )

        // 加入歷史定位紀錄
        prefs.addHistory(location.latitude, location.longitude, "🎯 發現 $label")

        // 1. 手機多段強震動提示
        try {
            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 500, 200, 500, 200, 800), -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(1000)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 2. 發送最高優先級 Heads-up 橫幅通知
        showHeadsUpNotification(context, label, location)

        // 3. 背景 Toast (若系統支援)
        try {
            Toast.makeText(
                context,
                "🎉 找到了！無人機發現【$label】！\n已將 GPS 定位鎖定在此處！",
                Toast.LENGTH_LONG
            ).show()
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun showHeadsUpNotification(context: Context, label: String, location: LocationPoint) {
        try {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channelId = "drone_target_alert_vibrate"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    channelId,
                    "無人機目標蘑菇警報 (純震動)",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "發現目標蘑菇時發送即時震動與橫幅提示"
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 500, 200, 500, 200, 800)
                    enableLights(true)
                    lightColor = android.graphics.Color.RED
                    setSound(null, null) // 🔇 關閉鈴聲，純震動提示
                }
                notificationManager.createNotificationChannel(channel)
            }

            val openAppIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                0,
                openAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val resumeIntent = Intent(context, MockLocationService::class.java).apply {
                action = MockLocationService.ACTION_RESUME_DRONE_SCAN
            }
            val resumePendingIntent = PendingIntent.getService(
                context,
                102,
                resumeIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(context, channelId)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("🎯 發現目標蘑菇！【$label】")
                .setContentText("座標已鎖定 (${String.format("%.5f, %.5f", location.latitude, location.longitude)})，無人機已自動煞車停下！")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_EVENT)
                .setSound(null) // 🔇 不響鈴
                .setVibrate(longArrayOf(0, 500, 200, 500, 200, 800))
                .setContentIntent(pendingIntent)
                .addAction(
                    android.R.drawable.ic_media_play,
                    "⏩ 繼續搜尋下一顆菇",
                    resumePendingIntent
                )
                .setAutoCancel(true)
                .build()

            notificationManager.notify(3001, notification)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        releaseWakeLock()
        _status.value = _status.value.copy(
            isScanning = false,
            statusMessage = "無人機已停止"
        )
    }

    fun release() {
        stopScan()
        releaseVirtualDisplay()
        try {
            mediaProjection?.stop()
            mediaProjection = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
