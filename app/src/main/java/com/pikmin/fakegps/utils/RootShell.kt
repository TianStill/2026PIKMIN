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
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 系統 Shell 與 Root 命令輔助工具
 *
 * 專為拉盆極速流程提供底層能力：
 * 1. 一鍵開關手機 GPS 定位 (無需跳轉設定頁面)
 * 2. 一鍵強制關閉遊戲 (Root 秒關，無 Root 快速引導強停)
 * 3. 設備 Root 狀態與權限偵測 (支援 Magisk / KernelSU / APatch / SuperSU)
 */
object RootShell {

    private const val TAG = "RootShell"

    // 僅快取成功狀態 (true)，失敗時絕不快取 false，確保使用者在彈出授權視窗允許後能即時生效
    @Volatile
    private var isRootConfirmed: Boolean = false

    data class CommandResult(val exitCode: Int, val stdout: String, val stderr: String) {
        val isSuccess: Boolean get() = exitCode == 0
    }

    /**
     * 檢測設備是否存在 su 執行檔或已確認 Root
     */
    fun hasSuBinary(): Boolean {
        if (isRootConfirmed) return true
        val paths = arrayOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/system/sd/xbin/su",
            "/system/bin/failsafe/su",
            "/data/local/xbin/su",
            "/data/local/bin/su",
            "/data/local/su",
            "/data/adb/ksu/bin/su",
            "/data/adb/ap/bin/su"
        )
        return paths.any { File(it).exists() }
    }

    /**
     * 檢測並驗證設備是否具備可用之 Root 權限
     */
    fun isRootAvailable(): Boolean {
        if (isRootConfirmed) return true
        val result = executeSync("id", asRoot = true, timeoutSeconds = 5)
        if (result.isSuccess && result.stdout.contains("uid=0")) {
            isRootConfirmed = true
            Log.d(TAG, "Root permission confirmed and active")
            return true
        }
        return false
    }

    /**
     * 執行 Shell 指令 (同步，提供充足超時時間避免 Magisk 彈窗被強制中斷)
     */
    fun executeSync(command: String, asRoot: Boolean = true, timeoutSeconds: Long = 6): CommandResult {
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
            val code = process.exitValue()

            if (asRoot && code == 0) {
                isRootConfirmed = true
            }

            CommandResult(code, stdout, stderr)
        } catch (e: Exception) {
            Log.w(TAG, "Execution failed ($command): ${e.message}")
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
     * 直接開關手機 GPS 定位 (優先使用 Root / Secure Settings，失敗時由呼叫端安全跳轉)
     *
     * @return true 代表已直接在背景成功切換；false 代表無權限需跳轉設定頁
     */
    fun setGpsEnabled(context: Context, enable: Boolean): Boolean {
        // 1. 嘗試透過 Root 指令直接切換系統 GPS
        val cmd1 = if (enable) "cmd location set-location-enabled true" else "cmd location set-location-enabled false"
        val cmd2 = if (enable) "settings put secure location_mode 3" else "settings put secure location_mode 0"
        val rootResult = executeSync("$cmd1; $cmd2", asRoot = true, timeoutSeconds = 4)
        if (rootResult.isSuccess) {
            Thread.sleep(120)
            if (isGpsEnabled(context) == enable) {
                return true
            }
        }

        // 2. 嘗試使用 WRITE_SECURE_SETTINGS 權限
        try {
            val mode = if (enable) Settings.Secure.LOCATION_MODE_HIGH_ACCURACY else Settings.Secure.LOCATION_MODE_OFF
            val success = Settings.Secure.putInt(context.contentResolver, Settings.Secure.LOCATION_MODE, mode)
            if (success) {
                Thread.sleep(120)
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
        // 1. 直接嘗試以 Root 強制終止所有相關進程
        val killCommand = "am force-stop $packageName; pkill -9 -f $packageName"
        val result = executeSync(killCommand, asRoot = true, timeoutSeconds = 4)
        if (result.isSuccess) {
            Log.d(TAG, "Force stop $packageName succeeded via Root")
            return true
        }

        // 2. 無 Root 時調用系統 killBackgroundProcesses
        try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            am?.killBackgroundProcesses(packageName)
        } catch (_: Exception) {}

        return false
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
