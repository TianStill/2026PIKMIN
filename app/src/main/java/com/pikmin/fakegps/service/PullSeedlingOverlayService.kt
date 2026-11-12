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
import com.pikmin.fakegps.data.repository.PreferencesRepo
import com.pikmin.fakegps.ui.TransparentClipboardActivity
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
 * 4. [🛑 關遊戲]：一鍵強制終止 Pikmin Bloom（免去手動多工滑掉程式）
 * 5. [🎮 開遊戲]：一鍵快速啟動 Pikmin Bloom
 * 6. [🌱] 點擊隨時縮小為小綠苗徽章，按住可任意拖曳移動
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
            setPadding(dpToPx(5), dpToPx(4), dpToPx(5), dpToPx(4))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(24).toFloat()
                setColor(Color.parseColor("#F00F172A")) // 深色高透科技藍黑
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
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(dpToPx(7), dpToPx(5), dpToPx(7), dpToPx(5))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(16).toFloat()
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
                setPadding(dpToPx(7), dpToPx(5), dpToPx(7), dpToPx(5))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(dpToPx(2), 0, dpToPx(2), 0)
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dpToPx(10).toFloat()
                    setColor(Color.parseColor(bgHex))
                }
                setOnClickListener { onClick() }
            }
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

        // 按鈕 4：[🛑 關遊戲] (明確獨立按鈕：一鍵徹底關閉遊戲進程)
        val btnCloseGame = createActionButton("🛑 關遊戲", "#F43F5E", "#26F43F5E") {
            performForceCloseGame()
        }

        // 按鈕 5：[🎮 開遊戲] (明確獨立按鈕：一鍵快速開啟 Pikmin Bloom)
        val btnOpenGame = createActionButton("🎮 開遊戲", "#38BDF8", "#2238BDF8") {
            performLaunchGame()
        }

        // 按鈕 6：[✕ 關閉懸浮窗]
        val btnClose = createActionButton("✕", "#94A3B8", "#1E334155") {
            stopSelf()
        }

        actionsContainer.addView(btnFly)
        actionsContainer.addView(btnStop)
        actionsContainer.addView(btnGps)
        actionsContainer.addView(btnCloseGame)
        actionsContainer.addView(btnOpenGame)
        actionsContainer.addView(btnClose)

        rootLayout.addView(badge)
        rootLayout.addView(actionsContainer)

        overlayView = rootLayout
        windowManager?.addView(overlayView, params)

        updateGpsButtonUI()
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
     * 執行一鍵關閉 Pikmin Bloom 遊戲（相當於手動滑掉程式）
     */
    private fun performForceCloseGame() {
        serviceScope.launch {
            val stopped = withContext(Dispatchers.IO) {
                RootShell.forceStopPackage(this@PullSeedlingOverlayService, PIKMIN_PACKAGE)
            }

            if (stopped) {
                Toast.makeText(this@PullSeedlingOverlayService, "🛑 Pikmin Bloom 已完全強制關閉！", Toast.LENGTH_SHORT).show()
            } else {
                // 無 Root 設備引導至應用程式資訊頁面手動點擊「強行停止」
                Toast.makeText(this@PullSeedlingOverlayService, "⚠️ 請點擊頁面上的「強行停止」以關閉遊戲", Toast.LENGTH_LONG).show()
                RootShell.openAppSettings(this@PullSeedlingOverlayService, PIKMIN_PACKAGE)
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
