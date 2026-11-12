package com.pikmin.fakegps.service

import android.annotation.SuppressLint
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ClipboardManager
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
import com.pikmin.fakegps.utils.GeoUtils
import com.pikmin.fakegps.utils.PermissionHelper
import com.pikmin.fakegps.utils.RootShell
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import kotlin.math.abs

/**
 * 🍄 金盆拉盆懸浮控制器服務 (PullSeedlingOverlayService)
 *
 * 浮在所有 App（包括遊戲與桌面）最上層的極簡操作膠囊：
 * 1. [📍 飛目標]：自動監聽剪貼簿！一複製座標立即自動填入，一鍵直接飛去
 * 2. [🛑 停飛]：一鍵停止虛擬定位
 * 3. [🟢 關GPS / ⚪ 開GPS]：直接控制開關手機定位，無需跳轉設定頁面
 * 4. [🛑 關遊戲 / 🎮 開遊戲]：一鍵強制關閉（免手動滑掉）或重新啟動 Pikmin Bloom
 * 5. [🌱] 點擊隨時縮小為小綠苗徽章，按住可任意拖曳移動
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
    private var clipboardManager: ClipboardManager? = null
    private var clipListener: ClipboardManager.OnPrimaryClipChangedListener? = null
    private var gpsReceiver: BroadcastReceiver? = null

    // UI 控制項引用
    private var btnFlyRef: TextView? = null
    private var btnGpsRef: TextView? = null
    private var btnGameRef: TextView? = null

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

        // 登出監聽器
        clipListener?.let { clipboardManager?.removePrimaryClipChangedListener(it) }
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

        // 外層膠囊容器 (深色高透毛玻璃質感)
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpToPx(6), dpToPx(5), dpToPx(6), dpToPx(5))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(24).toFloat()
                setColor(Color.parseColor("#EE0F172A")) // 深色半透明科技藍黑
                setStroke(dpToPx(1), Color.parseColor("#10B981")) // 翡翠綠邊框
            }
        }

        // 動作按鈕容器 (展開時顯示)
        val actionsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.VISIBLE
        }

        // 拖曳把手兼折疊切換徽章 (🌱)
        val badge = TextView(this).apply {
            text = "🌱"
            textSize = 17f
            gravity = Gravity.CENTER
            setPadding(dpToPx(8), dpToPx(5), dpToPx(8), dpToPx(5))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(18).toFloat()
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
                        badge.text = if (isExpanded) "🌱" else "🌱 拉盆"
                        if (isExpanded) {
                            refreshAllButtonStates()
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
                textSize = 11.5f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor(textColorHex))
                gravity = Gravity.CENTER
                setPadding(dpToPx(8), dpToPx(6), dpToPx(8), dpToPx(6))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(dpToPx(3), 0, dpToPx(3), 0)
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dpToPx(12).toFloat()
                    setColor(Color.parseColor(bgHex))
                }
                setOnClickListener { onClick() }
            }
        }

        // 按鈕 1：[📍 飛目標] (自動讀取最新剪貼簿或記憶座標)
        val btnFly = createActionButton("📍 飛目標", "#10B981", "#2210B981") {
            performFlyToTarget()
        }
        btnFlyRef = btnFly

        // 按鈕 2：[🛑 停飛]
        val btnStop = createActionButton("🛑 停飛", "#EF4444", "#26EF4444") {
            MockLocationService.stop(this)
            Toast.makeText(this, "🛑 虛擬定位已停止！", Toast.LENGTH_SHORT).show()
        }

        // 按鈕 3：[🟢 關GPS / ⚪ 開GPS] (直接控制，不用跳轉設定頁)
        val btnGps = createActionButton("🟢 關GPS", "#10B981", "#2210B981") {
            performToggleGps()
        }
        btnGpsRef = btnGps

        // 按鈕 4：[🛑 關遊戲 / 🎮 開遊戲] (遊戲開關一鍵完成)
        val btnGame = createActionButton("🎮 遊戲", "#38BDF8", "#2238BDF8") {
            performToggleGame()
        }
        btnGameRef = btnGame

        // 按鈕 5：[✕ 關閉懸浮窗]
        val btnClose = createActionButton("✕", "#94A3B8", "#1E334155") {
            stopSelf()
        }

        actionsContainer.addView(btnFly)
        actionsContainer.addView(btnStop)
        actionsContainer.addView(btnGps)
        actionsContainer.addView(btnGame)
        actionsContainer.addView(btnClose)

        rootLayout.addView(badge)
        rootLayout.addView(actionsContainer)

        overlayView = rootLayout
        windowManager?.addView(overlayView, params)

        refreshAllButtonStates()
    }

    /**
     * 初始化系統監聽（剪貼簿自動辨識 + GPS 狀態變動監聽）
     */
    private fun initSystemListeners() {
        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager

        // 1. 剪貼簿即時監聽：一複製座標就自動填入，並更新偏好設定
        clipListener = ClipboardManager.OnPrimaryClipChangedListener {
            checkAndApplyClipboardCoordinate(showToast = true)
        }
        clipboardManager?.addPrimaryClipChangedListener(clipListener)

        // 啟動時先檢查一次剪貼簿
        checkAndApplyClipboardCoordinate(showToast = false)

        // 2. GPS 狀態變更廣播監聽
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
     * 自動檢查剪貼簿並填入新座標
     */
    private fun checkAndApplyClipboardCoordinate(showToast: Boolean) {
        val clip = clipboardManager?.primaryClip
        if (clip != null && clip.itemCount > 0) {
            val text = clip.getItemAt(0)?.text?.toString() ?: ""
            val extracted = GeoUtils.extractCoordinatesFromText(text)
            if (extracted != null) {
                val isNew = abs(extracted.latitude - prefs.lastPullSeedlingLat) > 0.00001 ||
                        abs(extracted.longitude - prefs.lastPullSeedlingLng) > 0.00001
                prefs.lastPullSeedlingLat = extracted.latitude
                prefs.lastPullSeedlingLng = extracted.longitude

                if (showToast && isNew) {
                    Toast.makeText(
                        this,
                        "🌱 已自動填入複製座標：${String.format(Locale.US, "%.5f, %.5f", extracted.latitude, extracted.longitude)}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    /**
     * 執行傳送至目標金盆座標
     */
    private fun performFlyToTarget() {
        // 優先再次讀取最新剪貼簿
        checkAndApplyClipboardCoordinate(showToast = false)

        val targetLat = prefs.lastPullSeedlingLat
        val targetLng = prefs.lastPullSeedlingLng

        if (targetLat == 0.0 && targetLng == 0.0) {
            Toast.makeText(this, "⚠️ 尚未偵測到座標，請先複製金盆座標！", Toast.LENGTH_SHORT).show()
            return
        }

        MockLocationService.start(this, LocationPoint(targetLat, targetLng))
        Toast.makeText(
            this,
            "🌱 已飛至目標座標：${String.format(Locale.US, "%.5f, %.5f", targetLat, targetLng)}",
            Toast.LENGTH_SHORT
        ).show()
    }

    /**
     * 執行開關手機 GPS 定位（直接控制，避免跳轉設定頁）
     */
    private fun performToggleGps() {
        val currentGps = RootShell.isGpsEnabled(this)
        val targetGps = !currentGps

        serviceScope.launch {
            // 優先嘗試背景直接切換（Root 或 Secure Settings）
            val success = withContext(Dispatchers.IO) {
                RootShell.setGpsEnabled(this@PullSeedlingOverlayService, targetGps)
            }

            if (success) {
                updateGpsButtonUI()
                val msg = if (targetGps) "🟢 GPS 已開啟！" else "⚪ GPS 已關閉！"
                Toast.makeText(this@PullSeedlingOverlayService, msg, Toast.LENGTH_SHORT).show()
            } else {
                // 若無 Root / Secure Settings 權限，安全 Fallback 開啟系統設定頁
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
     * 執行開關 Pikmin Bloom 遊戲（一鍵完全關閉或啟動）
     */
    private fun performToggleGame() {
        serviceScope.launch {
            val isRunning = withContext(Dispatchers.IO) {
                RootShell.isPackageRunning(this@PullSeedlingOverlayService, PIKMIN_PACKAGE)
            }

            if (isRunning) {
                // 遊戲正在執行中 -> 執行「一鍵關閉遊戲（相當於滑掉程式）」
                val stopped = withContext(Dispatchers.IO) {
                    RootShell.forceStopPackage(this@PullSeedlingOverlayService, PIKMIN_PACKAGE)
                }
                if (stopped) {
                    updateGameButtonUI(false)
                    Toast.makeText(this@PullSeedlingOverlayService, "🛑 Pikmin Bloom 已完全關閉！", Toast.LENGTH_SHORT).show()
                } else {
                    // 無 Root 時引導至應用程式資訊點擊強制停止
                    Toast.makeText(this@PullSeedlingOverlayService, "請點擊「強行停止」以關閉遊戲", Toast.LENGTH_SHORT).show()
                    RootShell.openAppSettings(this@PullSeedlingOverlayService, PIKMIN_PACKAGE)
                }
            } else {
                // 遊戲未執行 -> 啟動遊戲
                val started = RootShell.launchPackage(this@PullSeedlingOverlayService, PIKMIN_PACKAGE)
                if (started) {
                    updateGameButtonUI(true)
                    Toast.makeText(this@PullSeedlingOverlayService, "🎮 正在啟動 Pikmin Bloom...", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@PullSeedlingOverlayService, "未安裝 Pikmin Bloom 遊戲", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * 刷新所有按鈕的即時狀態
     */
    private fun refreshAllButtonStates() {
        updateGpsButtonUI()
        serviceScope.launch {
            val isRunning = withContext(Dispatchers.IO) {
                RootShell.isPackageRunning(this@PullSeedlingOverlayService, PIKMIN_PACKAGE)
            }
            updateGameButtonUI(isRunning)
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

    /**
     * 更新遊戲開關按鈕 UI
     */
    private fun updateGameButtonUI(isRunning: Boolean) {
        val btn = btnGameRef ?: return
        if (isRunning) {
            btn.text = "🛑 關遊戲"
            btn.setTextColor(Color.parseColor("#EF4444"))
            (btn.background as? GradientDrawable)?.setColor(Color.parseColor("#26EF4444"))
        } else {
            btn.text = "🎮 開遊戲"
            btn.setTextColor(Color.parseColor("#38BDF8"))
            (btn.background as? GradientDrawable)?.setColor(Color.parseColor("#2238BDF8"))
        }
    }
}
