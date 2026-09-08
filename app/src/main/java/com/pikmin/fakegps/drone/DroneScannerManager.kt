package com.pikmin.fakegps.drone

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.MainThread
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.DisplayMetrics
import android.view.WindowManager
import android.widget.Toast
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import androidx.core.app.NotificationCompat
import com.pikmin.fakegps.R
import com.pikmin.fakegps.ui.MainActivity
import com.pikmin.fakegps.cv.DetectedMushroom
import com.pikmin.fakegps.cv.MushroomDetector
import com.pikmin.fakegps.cv.MushroomType
import com.pikmin.fakegps.data.model.LocationPoint
import com.pikmin.fakegps.data.model.DiscoveredMushroomPoint
import com.pikmin.fakegps.data.repository.PreferencesRepo
import com.pikmin.fakegps.service.MockLocationService
import com.pikmin.fakegps.utils.GeoUtils
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ScanPhase { IDLE, WAITING_FOR_FRAME, SCANNING, FOUND, PAUSED, COMPLETED, ERROR }

data class DroneScanStatus(
    val phase: ScanPhase = ScanPhase.IDLE,
    val isScanning: Boolean = false,
    val currentIndex: Int = 0,
    val totalPoints: Int = 0,
    val currentCoordinate: LocationPoint? = null,
    val foundTarget: DetectedMushroom? = null,
    val foundLocation: LocationPoint? = null,
    val estimatedLocation: LocationPoint? = null,
    val statusMessage: String = "待命"
)

/**
 * 無人機巡航速度與精度預設配置 (統一資料模型，解決 Shotgun Surgery 代碼氣味)
 */
data class DroneCruiseProfile(
    val modeId: Int,
    val title: String,
    val subtitle: String,
    val stepMeters: Double,
    val dwellSeconds: Float
) {
    companion object {
        val FAST = DroneCruiseProfile(0, "⚡ 快速巡航", "350m / 2.2s", 350.0, 2.2f)
        val STANDARD = DroneCruiseProfile(1, "🚀 推薦標準", "300m / 2.8s", 300.0, 2.8f)
        val PRECISE = DroneCruiseProfile(2, "🎯 精細搜尋", "240m / 3.5s", 240.0, 3.5f)

        val ALL = listOf(FAST, STANDARD, PRECISE)

        fun getById(id: Int): DroneCruiseProfile = ALL.find { it.modeId == id } ?: STANDARD
    }
}

/**
 * 無人機雷達巡航與截圖檢測管理器
 */
@MainThread
object DroneScannerManager {

    // 巡航步距最高 360m，加上最遠視角的像素座標估算誤差；750m 可涵蓋相鄰航點看到同一顆菇。
    internal const val PREVIOUSLY_FOUND_RADIUS_METERS = 750.0

    internal fun wasPreviouslyFound(
        type: MushroomType,
        estimatedLocation: LocationPoint,
        discovered: List<DiscoveredMushroomPoint>
    ): Boolean = discovered.any {
        it.typeName == type.name &&
            GeoUtils.calculateDistanceMeters(
                it.latitude,
                it.longitude,
                estimatedLocation.latitude,
                estimatedLocation.longitude
            ) <= PREVIOUSLY_FOUND_RADIUS_METERS
    }

    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private var scanJob: Job? = null
    private val analysisTrace = java.util.ArrayDeque<String>()
    fun diagnosticSummary(): String = "${_status.value}\n" + analysisTrace.joinToString("\n")

    private val _status = MutableStateFlow(DroneScanStatus())
    val status: StateFlow<DroneScanStatus> = _status.asStateFlow()

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var backgroundThread: HandlerThread? = null

    private var latestBitmap: Bitmap? = null
    private val bitmapLock = Any()

    private var lastFrameTime = 0L
    private var frameTime = 0L
    private var captureGeneration = 0L
    private var projectionCallback: MediaProjection.Callback? = null
    val hasRemainingPoints: Boolean get() = currentWaypoints.isNotEmpty() && currentStartIndex < currentWaypoints.size

    private var screenWidth = 720
    private var screenHeight = 1280
    private var screenDensity = 320

    /**
     * 設定從 Activity 取得的 MediaProjection
     */
    fun setupMediaProjection(context: Context, resultCode: Int, data: Intent): Boolean {
        releaseCapture()
        if (!MockLocationService.enableMediaProjection()) return false
        return try {
            val mpManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val projection = mpManager.getMediaProjection(resultCode, data)
                ?: error("未取得擷取授權")
            mediaProjection = projection
            projectionCallback = object : MediaProjection.Callback() {
                override fun onStop() {
                    if (mediaProjection === projection) fail("螢幕擷取已中斷，請重新授權後續航")
                }
                override fun onCapturedContentResize(width: Int, height: Int) {
                    // Fixed geometry cannot be reused after rotation or app-window resizing.
                    val ratio = width.toDouble() / height.coerceAtLeast(1)
                    if (mediaProjection === projection && kotlin.math.abs(ratio - screenWidth.toDouble() / screenHeight) > 0.05) {
                        fail("擷取畫面比例已改變，請回到直向全螢幕並重新授權")
                    }
                }
            }
            projection.registerCallback(projectionCallback!!, Handler(Looper.getMainLooper()))
            initVirtualDisplay(context.applicationContext)
            check(virtualDisplay != null) { "無法建立螢幕擷取" }
            true
        } catch (e: Exception) {
            fail("螢幕擷取失敗：${e.localizedMessage}")
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
            val captureToken = captureGeneration
            imageReader?.setOnImageAvailableListener({ reader ->
                var image: android.media.Image? = null
                var ownedBitmap: Bitmap? = null
                try {
                    image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                    val now = SystemClock.elapsedRealtime()
                    // 限制取幀頻率 (~150ms 一幀，約 6.6 FPS)，既滿足 250ms 採樣需求，又徹底避免 60FPS 頻繁記憶體配置
                    if (now - lastFrameTime < 150L) {
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
                    ownedBitmap = tempBitmap
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

                    // 🌟 必須使用原生的像素裁剪（保留原始 RGB 色彩，防止 Canvas.drawBitmap 因遊戲畫面 Alpha=0 被透明化抹除）
                    val cleanBitmap = if (rowPadding == 0) {
                        tempBitmap
                    } else {
                        val cropped = Bitmap.createBitmap(tempBitmap, 0, 0, screenWidth, screenHeight)
                        tempBitmap.recycle()
                        cropped
                    }

                    ownedBitmap = cleanBitmap
                    synchronized(bitmapLock) {
                        if (captureToken == captureGeneration) {
                            latestBitmap?.recycle()
                            latestBitmap = cleanBitmap
                            frameTime = now
                            ownedBitmap = null
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("DroneScanner", "Frame acquisition error", e)
                } finally {
                    image?.close()
                    ownedBitmap?.let { if (!it.isRecycled) it.recycle() }
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
        } catch (e: Exception) {
            throw IllegalStateException("無法初始化擷取畫面", e)
        }
    }

    private fun releaseVirtualDisplay() {
        synchronized(bitmapLock) { captureGeneration++; frameTime = 0L }
        lastFrameTime = 0L
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
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK,
                    "FakeGps:DroneScanWakeLock"
                ).apply { setReferenceCounted(false) }
            }
            // Renew a bounded lease while scanning, including when the game is foreground.
            wakeLock?.acquire(120_000L)
        } catch (e: Exception) {
            throw IllegalStateException("無法保持螢幕亮起，請檢查省電設定", e)
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
    private var currentDwellSeconds: Float = 2.8f
    private var currentStartIndex: Int = 0

    /**
     * 啟動無人機螺旋巡弋掃描 (從頭開始)
     */
    fun startScan(
        context: Context,
        centerLat: Double,
        centerLng: Double,
        radiusKm: Double,
        targetTypes: Set<MushroomType>,
        dwellSeconds: Float = 2.8f,
        stepMeters: Double = 300.0
    ) {
        if (targetTypes.isEmpty()) { fail("請至少選擇一種目標"); return }

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

    private fun startScanInternal(
        context: Context,
        waypoints: List<LocationPoint>,
        startIndex: Int,
        targetTypes: Set<MushroomType>,
        dwellSeconds: Float
    ) {
        if (scanJob?.isActive == true) return
        if (mediaProjection == null || virtualDisplay == null || !MockLocationService.isRunning.value) {
            fail("定位或畫面尚未就緒，請重新授權後開始")
            return
        }
        val appContext = context.applicationContext
        val prefs = PreferencesRepo(appContext)
        val discoveredMushrooms = prefs.getDiscoveredMushrooms().toMutableList()
        val captureToken = captureGeneration
        analysisTrace.clear()
        _status.value = DroneScanStatus(phase = ScanPhase.WAITING_FOR_FRAME, isScanning = true,
            totalPoints = waypoints.size, currentIndex = startIndex,
            statusMessage = "請切換至遊戲地圖，3 秒後開始；請保持正北與固定縮放")
        openPikminBloom(context)
        scanJob = scope.launch {
            var wakeRenewal: Job? = null
            try {
                acquireWakeLock(appContext)
                wakeRenewal = launch {
                    while (isActive) {
                        delay(60_000L)
                        try { acquireWakeLock(appContext) }
                        catch (e: Exception) { fail(e.localizedMessage ?: "螢幕保亮失敗"); break }
                    }
                }
                delay(3000)
                val readyAfter = SystemClock.elapsedRealtime()
                withTimeout(5000) {
                    while (true) {
                        val frame = captureCurrentScreen(readyAfter)
                        if (frame != null) {
                            val blank = frame.isBlank
                            frame.bitmap.recycle()
                            check(!blank) {
                                "裝置將遊戲畫面保護為黑色；請在開發人員選項暫時開啟「停用螢幕分享保護」後重新授權"
                            }
                            break
                        }
                        delay(100)
                    }
                }
                for (index in startIndex until waypoints.size) {
                    ensureActive()
                    currentStartIndex = index // On interruption, retry the unanalysed waypoint.
                    val waypoint = waypoints[index]
                    check(MockLocationService.updateLocation(appContext, waypoint)) { "定位已停止" }
                    _status.value = _status.value.copy(phase = ScanPhase.SCANNING, currentIndex = index + 1,
                        currentCoordinate = waypoint, statusMessage = "正在分析航點 ${index + 1}/${waypoints.size}")
                    delay(1200)
                    val minimumFrameTime = SystemClock.elapsedRealtime()
                    val deadline = minimumFrameTime + (dwellSeconds * 1000).toLong().coerceAtLeast(1400)
                    var lastAnalysed = minimumFrameTime
                    var analysedFrames = 0
                    var blankFrames = 0
                    val confirmation = FrameConfirmation()
                    var found: DetectedMushroom? = null
                    // A candidate arriving near the deadline gets time for three-frame confirmation.
                    // The extension is bounded so unrelated flickering colors cannot stall a route.
                    while (SystemClock.elapsedRealtime() < deadline ||
                        ((confirmation.hasPendingCandidate || analysedFrames < 3) &&
                            SystemClock.elapsedRealtime() < deadline + 3000L)) {
                        ensureActive()
                        val frame = captureCurrentScreen(lastAnalysed)
                        if (frame != null) {
                            lastAnalysed = frame.time
                            if (frame.isBlank) {
                                frame.bitmap.recycle()
                                blankFrames++
                                check(blankFrames < 3) {
                                    "裝置將遊戲畫面保護為黑色；請在開發人員選項暫時開啟「停用螢幕分享保護」後重新授權"
                                }
                                delay(250)
                                continue
                            }
                            blankFrames = 0
                            val detected = try {
                                withContext(Dispatchers.Default) { MushroomDetector.detectMushrooms(frame.bitmap, targetTypes) }
                            } finally { frame.bitmap.recycle() }
                            val newCandidates = detected.filterNot { candidate ->
                                wasPreviouslyFound(
                                    candidate.type,
                                    estimateMushroomLocation(waypoint, candidate),
                                    discoveredMushrooms
                                )
                            }
                            analysedFrames++
                            found = confirmation.observe(frame.time, newCandidates)
                            val trace = "waypoint=${index + 1} frames=$analysedFrames " +
                                "candidates=${detected.map { "${it.type}@(${it.x},${it.y})/r${it.radius}/p${"%.2f".format(java.util.Locale.US, it.confidence)}" }} " +
                                "new=${newCandidates.map { it.type }} confirmed=${found?.type}"
                            if (analysisTrace.size >= 60) analysisTrace.removeFirst()
                            analysisTrace.addLast(trace)
                            android.util.Log.d("DroneScanner", trace)
                            if (found != null) break
                        }
                        delay(250)
                    }
                    check(analysedFrames >= 3) { "未取得足夠的新畫面，搜尋已暫停，請確認遊戲畫面與擷取權限" }
                    if (found != null) {
                        currentStartIndex = index + 1
                        onTargetDiscovered(appContext, found, waypoint, prefs, discoveredMushrooms)
                        return@launch
                    }
                    currentStartIndex = index + 1
                }
                _status.value = _status.value.copy(phase = ScanPhase.COMPLETED, isScanning = false,
                    statusMessage = "本輪分析完成，未找到更多符合條件的候選目標")
            } catch (e: TimeoutCancellationException) {
                _status.value = _status.value.copy(phase = ScanPhase.ERROR, isScanning = false,
                    statusMessage = "沒有新的遊戲畫面，請重新授權後續航")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _status.value = _status.value.copy(phase = ScanPhase.ERROR, isScanning = false,
                    statusMessage = e.localizedMessage ?: "巡航失敗")
                Toast.makeText(appContext, _status.value.statusMessage, Toast.LENGTH_LONG).show()
            } finally {
                wakeRenewal?.cancel()
                if (captureGeneration == captureToken) {
                    releaseWakeLock()
                    releaseCapture()
                }
            }
        }
    }

    private fun openPikminBloom(context: Context) {
        val launchIntent = context.packageManager.getLaunchIntentForPackage("com.nianticlabs.pikmin")
            ?: return
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(launchIntent) }
            .onFailure { android.util.Log.w("DroneScanner", "Unable to open PIKMIN", it) }
    }

    private data class CapturedFrame(val bitmap: Bitmap, val time: Long, val isBlank: Boolean)
    private fun captureCurrentScreen(after: Long): CapturedFrame? = synchronized(bitmapLock) {
        val current = latestBitmap ?: return@synchronized null
        if (current.isRecycled || frameTime <= after || SystemClock.elapsedRealtime() - frameTime > 1000) return@synchronized null
        CapturedFrame(current.copy(Bitmap.Config.ARGB_8888, false), frameTime, isBlankCapture(current))
    }

    internal fun isBlankCapture(bitmap: Bitmap): Boolean {
        val startX = bitmap.width / 20
        val endX = bitmap.width * 19 / 20
        val startY = bitmap.height / 10
        val endY = bitmap.height * 9 / 10
        val stepX = ((endX - startX) / 40).coerceAtLeast(1)
        val stepY = ((endY - startY) / 60).coerceAtLeast(1)
        var samples = 0
        var visible = 0
        var y = startY
        while (y < endY) {
            var x = startX
            while (x < endX) {
                val pixel = bitmap.getPixel(x, y)
                if (android.graphics.Color.red(pixel) > 24 ||
                    android.graphics.Color.green(pixel) > 24 ||
                    android.graphics.Color.blue(pixel) > 24) visible++
                samples++
                x += stepX
            }
            y += stepY
        }
        return samples > 0 && visible * 100 < samples
    }

    fun fail(message: String) {
        stopScan()
        _status.value = _status.value.copy(phase = ScanPhase.ERROR, foundTarget = null, foundLocation = null,
            estimatedLocation = null, statusMessage = message)
    }

    private fun onTargetDiscovered(
        context: Context,
        target: DetectedMushroom,
        location: LocationPoint,
        prefs: PreferencesRepo,
        discoveredMushrooms: MutableList<DiscoveredMushroomPoint>
    ) {
        val label = MushroomType.getDisplayName(target.type)
        val estimatedLocation = estimateMushroomLocation(location, target)
        check(MockLocationService.updateLocation(context, location)) { "目標已辨識，但定位已停止，無法保持觀測航點" }

        val discovered = DiscoveredMushroomPoint(
            typeName = target.type.name,
            latitude = estimatedLocation.latitude,
            longitude = estimatedLocation.longitude
        )
        prefs.addDiscoveredMushroom(discovered)
        discoveredMushrooms.add(0, discovered)

        _status.value = _status.value.copy(
            phase = ScanPhase.FOUND,
            isScanning = false,
            foundTarget = target,
            foundLocation = location,
            estimatedLocation = estimatedLocation,
            statusMessage = "發現候選【$label】，已停在觀測航點；請在遊戲內確認種類與大小"
        )

        // Keep the observation waypoint. An uncalibrated pixel estimate must not trigger a teleport.
        prefs.lastLatitude = location.latitude
        prefs.lastLongitude = location.longitude
        prefs.addHistory(location.latitude, location.longitude, "候選觀測點 $label")

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
                "🎉 找到了！無人機發現【$label】！\n已停在觀測航點，請確認目標！",
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
                action = "com.pikmin.fakegps.OPEN_DRONE"
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                3001,
                openAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notification = NotificationCompat.Builder(context, channelId)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("🎯 發現目標蘑菇！【$label】")
                .setContentText("停在觀測航點 (${String.format(java.util.Locale.US, "%.5f, %.5f", location.latitude, location.longitude)})，無人機已自動煞車停下！")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_EVENT)
                .setSound(null) // 🔇 不響鈴
                .setVibrate(longArrayOf(0, 500, 200, 500, 200, 800))
                .setContentIntent(pendingIntent)
                .addAction(
                    android.R.drawable.ic_media_play,
                    "開啟面板並重新授權續航",
                    pendingIntent
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
        releaseCapture()
        _status.value = _status.value.copy(phase = ScanPhase.PAUSED, isScanning = false,
            statusMessage = "巡航已停止，定位保持在目前位置；續航需重新授權畫面")
    }

    private fun releaseCapture() {
        val projection = mediaProjection
        mediaProjection = null
        projectionCallback?.let { callback -> runCatching { projection?.unregisterCallback(callback) } }
        projectionCallback = null
        releaseVirtualDisplay()
        runCatching { projection?.stop() }
        MockLocationService.disableMediaProjection()
    }

    fun release() {
        stopScan()
        currentWaypoints = emptyList()
        currentStartIndex = 0
        _status.value = DroneScanStatus()
    }
}
