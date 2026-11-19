package com.pikmin.fakegps.service

import android.annotation.SuppressLint
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.*
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.pikmin.fakegps.data.model.LocationPoint
import com.pikmin.fakegps.data.repository.PreferencesRepo
import com.pikmin.fakegps.ui.TransparentClipboardActivity
import com.pikmin.fakegps.utils.GeoUtils
import com.pikmin.fakegps.utils.PermissionHelper
import com.pikmin.fakegps.utils.RootShell
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs

/**
 * 🍄 金盆拉盆懸浮控制器服務 (PullSeedlingOverlayService)
 *
 * 浮在所有 App（包括遊戲與桌面）最上層的極簡操作膠囊：
 * 1. [📍 飛目標]：透過秒速透明跳板 100% 讀取系統最新剪貼簿座標，一鍵直接飛去
 * 2. [🛑 停飛]：一鍵停止虛擬定位
 * 3. [🟢 關GPS / ⚪ 開GPS]：直接控制開關手機系統定位，免跳轉設定頁
 * 4. [🎮 遊戲]：一鍵快速喚醒 Pikmin Bloom
 * 5. [方向鍵 ◀ ▲ ▼ ▶]：在遊戲畫面上直接微調/自由移動人物（支援單擊前進、長按連續行走）
 * 6. [步長切換 🚶 5m]：一鍵切換 3m / 5m / 10m / 20m 移動步長
 * 7. [🌱] 點擊隨時縮小為小綠苗徽章，按住可任意拖曳移動
 */
class PullSeedlingOverlayService : Service() {

    companion object {
        private val _isRunning = MutableStateFlow(false)
        val isRunning = _isRunning.asStateFlow()

        const val PIKMIN_PACKAGE = "com.nianticlabs.pikmin"

        fun start(context: Context): Boolean {
            if (!PermissionHelper.canDrawOverlays(context)) {
                PermissionHelper.openOverlaySettings(context)
                Toast.makeText(context, "請先開啟「顯示在其他應用程式上層」權限", Toast.LENGTH_LONG).show()
                return false
            }
            val intent = Intent(context, PullSeedlingOverlayService::class.java)
            context.startService(intent)
            return true
        }

        fun stop(context: Context) {
            val intent = Intent(context, PullSeedlingOverlayService::class.java)
            context.stopService(intent)
        }
    }

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private lateinit var prefs: PreferencesRepo
    private var isExpanded = true
    private val serviceScope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

    // 系統監聽器
    private var gpsReceiver: BroadcastReceiver? = null

    // UI 控制項引用
    private var btnGpsRef: TextView? = null
    private var btnStepRef: TextView? = null

    // 移動步長設定
    private var currentStepIndex = 1
    private val stepOptions = listOf(
        Pair(3.0, "🚶 3m (慢走)"),
        Pair(5.0, "🚶 5m (正常)"),
        Pair(10.0, "🏃 10m (快走)"),
        Pair(20.0, "🚴 20m (小跑)")
    )
    private val currentStepMeters: Double get() = stepOptions[currentStepIndex].first

    // 長按連動行走協程
    private var moveRepeatJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = PreferencesRepo(this)
        _isRunning.value = true
        createOverlay()
        initSystemListeners()
    }

    override fun onDestroy() {
        super.onDestroy()
        _isRunning.value = false
        serviceScope.cancel()
        moveRepeatJob?.cancel()

        gpsReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (_: Exception) {}
        }

        overlayView?.let {
            try {
                windowManager?.removeView(it)
            } catch (_: Exception) {}
        }
        overlayView = null
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    @SuppressLint("ClickableViewAccessibility")
    private fun createOverlay() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.overlayX
            y = prefs.overlayY
        }

        // 外層膠囊容器 (深色高透科技質感)
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpToPx(5), dpToPx(4), dpToPx(5), dpToPx(4))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(18).toFloat()
                setColor(Color.parseColor("#F20F172A")) // 深色高透科技藍黑
                setStroke(dpToPx(1), Color.parseColor("#10B981")) // 翡翠綠細緻邊框
            }
        }

        // 動作總容器 (垂直排列：Row 1 功能按鈕，Row 2 方向移動按鈕)
        val actionsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            visibility = View.VISIBLE
        }

        // 拖曳把手兼折疊切換徽章 (🌱)
        val badge = TextView(this).apply {
            text = "🌱"
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(dpToPx(7), dpToPx(8), dpToPx(7), dpToPx(8))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(14).toFloat()
                setColor(Color.parseColor("#2610B981"))
            }
        }

        // 拖曳與點擊切換邏輯
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isMoving = false

        badge.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isMoving = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    if (abs(dx) > touchSlop || abs(dy) > touchSlop) {
                        isMoving = true
                    }
                    if (isMoving) {
                        params.x = initialX + dx.toInt()
                        params.y = initialY + dy.toInt()
                        windowManager?.updateViewLayout(rootLayout, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isMoving) {
                        // 點擊切換 展開 / 收折
                        isExpanded = !isExpanded
                        actionsContainer.visibility = if (isExpanded) View.VISIBLE else View.GONE
                        badge.text = if (isExpanded) "🌱" else "🌱 控制器"
                        if (isExpanded) {
                            updateGpsButtonUI()
                        }
                    } else {
                        prefs.overlayX = params.x
                        prefs.overlayY = params.y
                    }
                    true
                }
                else -> false
            }
        }

        // 輔助按鈕生成函式
        fun createActionButton(title: String, textColorHex: String, bgHex: String, onClick: () -> Unit): TextView {
            return TextView(this).apply {
                text = title
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor(textColorHex))
                gravity = Gravity.CENTER
                setPadding(dpToPx(7), dpToPx(4), dpToPx(7), dpToPx(4))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(dpToPx(2), 0, dpToPx(2), 0)
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dpToPx(8).toFloat()
                    setColor(Color.parseColor(bgHex))
                }
                setOnClickListener { onClick() }
            }
        }

        // ==================== 第一排：拉盆快捷功能列 ====================
        val row1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dpToPx(3))
        }

        // 按鈕 1：[📍 飛目標] (透過秒速透明跳板 100% 讀取系統最新剪貼簿並傳送)
        val btnFly = createActionButton("📍 飛目標", "#10B981", "#2210B981") {
            performFlyToTarget()
        }

        // 按鈕 2：[🛑 停飛]
        val btnStop = createActionButton("🛑 停飛", "#EF4444", "#26EF4444") {
            MockLocationService.stop(this)
            Toast.makeText(this, "🛑 虛擬定位已停止！", Toast.LENGTH_SHORT).show()
        }

        // 按鈕 3：[🟢 關GPS / ⚪ 開GPS] (直接控制系統開關)
        val btnGps = createActionButton("🟢 關GPS", "#10B981", "#2210B981") {
            performToggleGps()
        }
        btnGpsRef = btnGps

        // 按鈕 4：[🎮 遊戲] (一鍵快速喚醒 Pikmin Bloom)
        val btnGame = createActionButton("🎮 遊戲", "#38BDF8", "#2238BDF8") {
            performLaunchGame()
        }

        // 按鈕 5：[✕ 關閉懸浮窗]
        val btnClose = createActionButton("✕", "#94A3B8", "#1E334155") {
            stopSelf()
        }

        row1.addView(btnFly)
        row1.addView(btnStop)
        row1.addView(btnGps)
        row1.addView(btnGame)
        row1.addView(btnClose)

        // ==================== 第二排：方向鍵移動控制列 ====================
        val row2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dpToPx(2), 0, 0)
        }

        // 方向鍵生成函式 (支援單擊走一步，按住不放連續行走)
        fun createDirectionButton(symbol: String, bearing: Float): TextView {
            return TextView(this).apply {
                text = symbol
                textSize = 13.5f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor("#38BDF8"))
                gravity = Gravity.CENTER
                setPadding(dpToPx(11), dpToPx(3), dpToPx(11), dpToPx(3))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(dpToPx(2), 0, dpToPx(2), 0)
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dpToPx(8).toFloat()
                    setColor(Color.parseColor("#2538BDF8"))
                }
                setupDirectionTouch(this, bearing)
            }
        }

        val btnLeft = createDirectionButton("◀", 270f)
        val btnUp = createDirectionButton("▲", 0f)
        val btnDown = createDirectionButton("▼", 180f)
        val btnRight = createDirectionButton("▶", 90f)

        // 步長切換按鈕
        val btnStep = createActionButton(stepOptions[currentStepIndex].second.substring(0, 5), "#F59E0B", "#25F59E0B") {
            currentStepIndex = (currentStepIndex + 1) % stepOptions.size
            val opt = stepOptions[currentStepIndex]
            btnStepRef?.text = opt.second.substring(0, 5)
            Toast.makeText(this, "移動步長：${opt.second}", Toast.LENGTH_SHORT).show()
        }
        btnStepRef = btnStep

        row2.addView(btnLeft)
        row2.addView(btnUp)
        row2.addView(btnDown)
        row2.addView(btnRight)
        row2.addView(btnStep)

        actionsContainer.addView(row1)
        actionsContainer.addView(row2)

        rootLayout.addView(badge)
        rootLayout.addView(actionsContainer)

        overlayView = rootLayout
        windowManager?.addView(overlayView, params)

        updateGpsButtonUI()
    }

    /**
     * 為方向鍵綁定觸控事件：單擊走一步，長按連續行走
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun setupDirectionTouch(view: View, bearing: Float) {
        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    // 1. 立即踏出第一步
                    performMove(bearing)
                    // 2. 啟動連續前進協程 (初次長按延遲 350ms，之後每 220ms 前進一次)
                    moveRepeatJob?.cancel()
                    moveRepeatJob = serviceScope.launch {
                        delay(350)
                        while (isActive) {
                            performMove(bearing)
                            delay(220)
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    moveRepeatJob?.cancel()
                    moveRepeatJob = null
                    true
                }
                else -> false
            }
        }
    }

    /**
     * 執行指定方位移動 (依據 currentStepMeters 與 bearing 推算下一個座標)
     */
    private fun performMove(bearing: Float) {
        val current = MockLocationService.currentLocation.value
            ?: if (prefs.lastPullSeedlingLat != 0.0) LocationPoint(prefs.lastPullSeedlingLat, prefs.lastPullSeedlingLng) else null

        if (current == null) {
            Toast.makeText(this, "⚠️ 請先設定或飛至目標座標", Toast.LENGTH_SHORT).show()
            return
        }

        // 使用球面航位推算精確計算下一點座標
        val next = GeoUtils.calculateNextCoordinate(
            current.latitude,
            current.longitude,
            currentStepMeters.toFloat(),
            bearing,
            1.0
        )

        val newPoint = LocationPoint(next.first, next.second)
        prefs.lastPullSeedlingLat = newPoint.latitude
        prefs.lastPullSeedlingLng = newPoint.longitude

        if (MockLocationService.isRunning.value) {
            MockLocationService.updateLocation(this, newPoint)
        } else {
            MockLocationService.start(this, newPoint)
        }
    }

    /**
     * 初始化 GPS 狀態廣播監聽
     */
    private fun initSystemListeners() {
        gpsReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == LocationManager.PROVIDERS_CHANGED_ACTION) {
                    updateGpsButtonUI()
                }
            }
        }
        val filter = IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION)
        ContextCompat.registerReceiver(this, gpsReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
    }

    /**
     * 執行傳送至金盆座標：
     * 透過 TransparentClipboardActivity 獲得真實焦點，保證 100% 讀到 Android 剪貼簿最新座標並即刻傳送
     */
    private fun performFlyToTarget() {
        val intent = Intent(this, TransparentClipboardActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
            addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
        }
        startActivity(intent)
    }

    /**
     * 執行開關手機 GPS 定位（直接控制，避免跳轉設定頁）
     */
    private fun performToggleGps() {
        val currentGps = RootShell.isGpsEnabled(this)
        val targetGps = !currentGps

        serviceScope.launch {
            // 嘗試透過 Root 或 Secure Settings 直接開關
            val success = withContext(Dispatchers.IO) {
                RootShell.setGpsEnabled(this@PullSeedlingOverlayService, targetGps)
            }

            if (success) {
                updateGpsButtonUI()
                val msg = if (targetGps) "🟢 GPS 已開啟！" else "⚪ GPS 已關閉！"
                Toast.makeText(this@PullSeedlingOverlayService, msg, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this@PullSeedlingOverlayService, "正在開啟系統定位開關...", Toast.LENGTH_SHORT).show()
                try {
                    val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(this@PullSeedlingOverlayService, "無法開啟設定：${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * 執行啟動 Pikmin Bloom 遊戲
     */
    private fun performLaunchGame() {
        val started = RootShell.launchPackage(this, PIKMIN_PACKAGE)
        if (started) {
            Toast.makeText(this, "🎮 正在啟動 Pikmin Bloom...", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "未安裝 Pikmin Bloom 遊戲", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 更新 GPS 開關按鈕 UI
     */
    private fun updateGpsButtonUI() {
        val btn = btnGpsRef ?: return
        val isGpsEnabled = RootShell.isGpsEnabled(this)
        if (isGpsEnabled) {
            btn.text = "🟢 關GPS"
            btn.setTextColor(Color.parseColor("#10B981"))
            (btn.background as? GradientDrawable)?.setColor(Color.parseColor("#2210B981"))
        } else {
            btn.text = "⚪ 開GPS"
            btn.setTextColor(Color.parseColor("#F59E0B"))
            (btn.background as? GradientDrawable)?.setColor(Color.parseColor("#26F59E0B"))
        }
    }
}
