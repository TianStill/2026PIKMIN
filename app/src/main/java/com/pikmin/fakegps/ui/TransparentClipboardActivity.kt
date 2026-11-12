package com.pikmin.fakegps.ui

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import com.pikmin.fakegps.data.model.LocationPoint
import com.pikmin.fakegps.data.repository.PreferencesRepo
import com.pikmin.fakegps.service.MockLocationService
import com.pikmin.fakegps.utils.GeoUtils
import java.util.Locale

/**
 * ⚡ 透明剪貼簿讀取跳板 Activity
 *
 * 解決 Android 10+ 限制「背景 Service / 懸浮窗無焦點無法讀取剪貼簿」的系統限制：
 * 以全透明、無動畫形式在 0.03 秒內極速啟動並獲取 Window Focus，100% 讀取系統剪貼簿最新內容，
 * 解析金盆經緯度、傳送虛擬定位，並立即自動 finish。
 * 使用者在視覺上完全感受不到跳轉，原汁原味停留在遊戲畫面中。
 */
class TransparentClipboardActivity : Activity() {

    private var hasProcessed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 移除所有視窗動畫
        window.setWindowAnimations(0)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        )
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !hasProcessed) {
            hasProcessed = true
            processClipboardAndMock()
        }
    }

    override fun onResume() {
        super.onResume()
        // 雙重保險：部分客製化 ROM 在 onResume 時即具備焦點
        window.decorView.post {
            if (!hasProcessed) {
                hasProcessed = true
                processClipboardAndMock()
            }
        }
    }

    private fun processClipboardAndMock() {
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val clip = clipboard?.primaryClip
            val clipText = if (clip != null && clip.itemCount > 0) {
                clip.getItemAt(0)?.coerceToText(this)?.toString() ?: ""
            } else {
                ""
            }

            val extracted = GeoUtils.extractCoordinatesFromText(clipText)
            val prefs = PreferencesRepo(this)

            val targetLat: Double
            val targetLng: Double
            val sourceName: String

            if (extracted != null) {
                targetLat = extracted.latitude
                targetLng = extracted.longitude
                prefs.lastPullSeedlingLat = targetLat
                prefs.lastPullSeedlingLng = targetLng
                sourceName = "剪貼簿"
            } else {
                targetLat = prefs.lastPullSeedlingLat
                targetLng = prefs.lastPullSeedlingLng
                sourceName = "上次目標"
            }

            if (targetLat != 0.0 || targetLng != 0.0) {
                MockLocationService.start(this, LocationPoint(targetLat, targetLng))
                val coordStr = String.format(Locale.US, "%.5f, %.5f", targetLat, targetLng)
                Toast.makeText(this, "🌱 已飛至${sourceName}座標：$coordStr", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "⚠️ 剪貼簿內未偵測到有效座標，請先複製金盆座標！", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "讀取座標失敗：${e.localizedMessage}", Toast.LENGTH_SHORT).show()
        } finally {
            finish()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
            } else {
                @Suppress("DEPRECATION")
                overridePendingTransition(0, 0)
            }
        }
    }
}
