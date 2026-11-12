package com.pikmin.fakegps.service

import android.annotation.SuppressLint
import android.app.Service
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.*
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.pikmin.fakegps.data.model.LocationPoint
import com.pikmin.fakegps.data.repository.PreferencesRepo
import com.pikmin.fakegps.utils.GeoUtils
import com.pikmin.fakegps.utils.PermissionHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import kotlin.math.abs

/**
 * 🍄 金盆拉盆懸浮控制器服務 (PullSeedlingOverlayService)
 *
 * 浮在所有 App（包括遊戲與桌面）最上層的極簡操作膠囊：
 * 1. [📍 飛目標]：自動讀取剪貼簿新座標或已存目標，直接飛去
 * 2. [🛑 停飛]：一鍵停止虛擬定位
 * 3. [⚙️ 關GPS]：一鍵跳轉手機系統定位設定頁
 * 4. [🎮 開遊戲]：一鍵喚醒 Pikmin Bloom
 * 5. [🌱] 點擊可隨時縮小為小綠苗徽章，按住可任意拖曳移動
 */
class PullSeedlingOverlayService : Service() {

    companion object {
        private val _isRunning = MutableStateFlow(false)
        val isRunning = _isRunning.asStateFlow()

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

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = PreferencesRepo(this)
        _isRunning.value = true
        createOverlay()
    }

    override fun onDestroy() {
        super.onDestroy()
        _isRunning.value = false
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

        // 按鈕 1：[📍 飛目標 / 📋 貼上]
        val btnFly = createActionButton("📍 飛目標", "#10B981", "#2210B981") {
            // 優先讀取剪貼簿中是否有新的金盆座標
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val clipText = clipboard?.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
            val extracted = GeoUtils.extractCoordinatesFromText(clipText)

            val targetLat: Double
            val targetLng: Double
            val sourceDesc: String

            if (extracted != null) {
                targetLat = extracted.latitude
                targetLng = extracted.longitude
                sourceDesc = "剪貼簿"
                prefs.lastPullSeedlingLat = targetLat
                prefs.lastPullSeedlingLng = targetLng
            } else {
                targetLat = prefs.lastPullSeedlingLat
                targetLng = prefs.lastPullSeedlingLng
                sourceDesc = "預設目標"
            }

            MockLocationService.start(this, LocationPoint(targetLat, targetLng))
            Toast.makeText(
                this,
                "🌱 已飛至${sourceDesc}座標：${String.format(Locale.US, "%.5f, %.5f", targetLat, targetLng)}",
                Toast.LENGTH_SHORT
            ).show()
        }

        // 按鈕 2：[🛑 停飛]
        val btnStop = createActionButton("🛑 停飛", "#EF4444", "#26EF4444") {
            MockLocationService.stop(this)
            Toast.makeText(this, "🛑 虛擬定位已停止！", Toast.LENGTH_SHORT).show()
        }

        // 按鈕 3：[⚙️ 關GPS]
        val btnGps = createActionButton("⚙️ 關GPS", "#F59E0B", "#26F59E0B") {
            try {
                val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(this, "無法開啟定位設定：${e.localizedMessage}", Toast.LENGTH_SHORT).show()
            }
        }

        // 按鈕 4：[🎮 開遊戲]
        val btnGame = createActionButton("🎮 遊戲", "#38BDF8", "#2238BDF8") {
            val intent = packageManager.getLaunchIntentForPackage("com.nianticlabs.pikmin")
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
            } else {
                Toast.makeText(this, "未安裝 Pikmin Bloom 遊戲", Toast.LENGTH_SHORT).show()
            }
        }

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
    }
}
