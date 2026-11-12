package com.pikmin.fakegps.utils

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * 系統 Shell 與 Root 命令輔助工具
 *
 * 專為拉盆極速流程提供底層能力：
 * 1. 一鍵開關手機 GPS 定位 (無需跳轉設定頁面)
 * 2. 一鍵強制關閉遊戲 (相當於手動滑掉 Pikmin Bloom)
 * 3. 偵測遊戲是否正在執行
 */
object RootShell {

    private const val TAG = "RootShell"
    private var isRootCached: Boolean? = null

    data class CommandResult(val exitCode: Int, val stdout: String, val stderr: String) {
        val isSuccess: Boolean get() = exitCode == 0
    }

    /**
     * 檢測設備是否具備 Root 權限
     */
    fun isRootAvailable(): Boolean {
        isRootCached?.let { return it }
        val result = executeSync("id", asRoot = true, timeoutSeconds = 2)
        val available = result.isSuccess && result.stdout.contains("uid=0")
        isRootCached = available
        Log.d(TAG, "Root availability check: $available")
        return available
    }

    /**
     * 執行 Shell 指令 (同步，具備超時機制避免阻塞)
     */
    fun executeSync(command: String, asRoot: Boolean = true, timeoutSeconds: Long = 3): CommandResult {
        return try {
            val process = if (asRoot) {
                ProcessBuilder("su", "-c", command).start()
            } else {
                ProcessBuilder("sh", "-c", command).start()
            }

            val completed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            } else {
                process.waitFor()
                true
            }

            if (!completed) {
                process.destroy()
                return CommandResult(-1, "", "Command timed out")
            }

            val stdout = process.inputStream.bufferedReader().use(BufferedReader::readText).trim()
            val stderr = process.errorStream.bufferedReader().use(BufferedReader::readText).trim()
            CommandResult(process.exitValue(), stdout, stderr)
        } catch (e: Exception) {
            Log.w(TAG, "Execution failed: ${e.message}")
            CommandResult(-1, "", e.message ?: "Execution failed")
        }
    }

    /**
     * 異步執行 Shell 指令
     */
    suspend fun execute(command: String, asRoot: Boolean = true): CommandResult = withContext(Dispatchers.IO) {
        executeSync(command, asRoot)
    }

    /**
     * 檢測系統 GPS 定位開關狀態
     */
    fun isGpsEnabled(context: Context): Boolean {
        return try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                lm?.isLocationEnabled == true
            } else {
                @Suppress("DEPRECATION")
                val mode = Settings.Secure.getInt(context.contentResolver, Settings.Secure.LOCATION_MODE, Settings.Secure.LOCATION_MODE_OFF)
                mode != Settings.Secure.LOCATION_MODE_OFF
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 直接開關手機 GPS 定位 (優先使用 Root / Secure Settings，失敗時才需跳轉設定頁)
     *
     * @return true 代表已直接在背景成功切換；false 代表無權限需引導至設定頁
     */
    fun setGpsEnabled(context: Context, enable: Boolean): Boolean {
        // 1. 嘗試使用 Root 權限直接修改系統定位開關 (最乾淨快速，完全不跳頁)
        if (isRootAvailable()) {
            val cmd1 = if (enable) "cmd location set-location-enabled true" else "cmd location set-location-enabled false"
            val cmd2 = if (enable) "settings put secure location_mode 3" else "settings put secure location_mode 0"
            executeSync("$cmd1; $cmd2", asRoot = true, timeoutSeconds = 2)
            // 等待短暫狀態變更確認
            Thread.sleep(100)
            if (isGpsEnabled(context) == enable) {
                return true
            }
        }

        // 2. 嘗試使用 WRITE_SECURE_SETTINGS 權限 (ADB 授權過的使用者)
        try {
            val mode = if (enable) Settings.Secure.LOCATION_MODE_HIGH_ACCURACY else Settings.Secure.LOCATION_MODE_OFF
            val success = Settings.Secure.putInt(context.contentResolver, Settings.Secure.LOCATION_MODE, mode)
            if (success) {
                Thread.sleep(100)
                if (isGpsEnabled(context) == enable) {
                    return true
                }
            }
        } catch (_: Exception) {}

        return false
    }

    /**
     * 強制停止指定應用 (相當於從多工任務中滑掉)
     *
     * @return true 代表已透過 Root 成功強制終止；false 代表無 Root 權限
     */
    fun forceStopPackage(context: Context, packageName: String): Boolean {
        if (isRootAvailable()) {
            val result = executeSync("am force-stop $packageName", asRoot = true)
            if (result.isSuccess) {
                return true
            }
        }

        // 若無 Root，嘗試調用系統 killBackgroundProcesses
        try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            am?.killBackgroundProcesses(packageName)
        } catch (_: Exception) {}

        return false
    }

    /**
     * 檢測指定應用程式當前是否正在執行
     */
    fun isPackageRunning(context: Context, packageName: String): Boolean {
        // 1. 優先使用 Root 查詢 pid
        if (isRootAvailable()) {
            val result = executeSync("pidof $packageName", asRoot = true, timeoutSeconds = 1)
            if (result.isSuccess && result.stdout.isNotBlank()) {
                return true
            }
        }

        // 2. 透過 ActivityManager 查詢
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val processes = am?.runningAppProcesses ?: emptyList()
            processes.any { it.processName == packageName }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 啟動指定應用程式
     */
    fun launchPackage(context: Context, packageName: String): Boolean {
        return try {
            val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                true
            } else {
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 開啟指定應用的「應用程式詳細資訊」設定頁 (供無 Root 設備手動點擊「強制停止」)
     */
    fun openAppSettings(context: Context, packageName: String) {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Exception) {}
    }
}
