package com.pikmin.fakegps.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

object AppUpdateManager {

    private const val TAG = "AppUpdateManager"
    private const val GITHUB_OWNER = "TianStill"
    private const val GITHUB_REPO = "2026PIKMIN"
    private const val RELEASES_API_URL = "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases/latest"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val _updateState = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
    val updateState: StateFlow<UpdateUiState> = _updateState.asStateFlow()

    // 快取最新版本資訊
    var latestReleaseInfo: AppReleaseInfo? = null
        private set

    fun resetState() {
        _updateState.value = UpdateUiState.Idle
    }

    /**
     * 檢查 GitHub 是否有更新版本
     * @param currentVersion 例如 "1.0.0"
     * @param silentCheck 若為背景定期檢查或開機自檢，無更新時不彈出通知
     */
    suspend fun checkForUpdates(currentVersion: String, silentCheck: Boolean = false): AppReleaseInfo? {
        return withContext(Dispatchers.IO) {
            try {
                if (!silentCheck) {
                    _updateState.value = UpdateUiState.Checking
                }

                val request = Request.Builder()
                    .url(RELEASES_API_URL)
                    .header("Accept", "application/vnd.github.v3+json")
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        if (!silentCheck) {
                            _updateState.value = UpdateUiState.Error("無法獲取更新資訊 (HTTP ${response.code})")
                        }
                        return@withContext null
                    }

                    val bodyString = response.body?.string() ?: ""
                    val json = JSONObject(bodyString)

                    val tagName = json.optString("tag_name", "")
                    val title = json.optString("name", tagName)
                    val body = json.optString("body", "無更新說明")
                    val publishedAt = json.optString("published_at", "")

                    val cleanRemoteVersion = tagName.trimStart('v', 'V').trim()
                    val cleanCurrentVersion = currentVersion.trimStart('v', 'V').trim()

                    // 尋找 APK 附檔
                    var downloadUrl = ""
                    var apkSize = 0L
                    val assets = json.optJSONArray("assets")
                    if (assets != null) {
                        for (i in 0 until assets.length()) {
                            val asset = assets.getJSONObject(i)
                            val name = asset.optString("name", "")
                            if (name.endsWith(".apk", ignoreCase = true)) {
                                downloadUrl = asset.optString("browser_download_url", "")
                                apkSize = asset.optLong("size", 0L)
                                break
                            }
                        }
                    }

                    if (downloadUrl.isBlank()) {
                        if (!silentCheck) {
                            _updateState.value = UpdateUiState.Error("最新發布中未找到 APK 安裝檔")
                        }
                        return@withContext null
                    }

                    val releaseInfo = AppReleaseInfo(
                        tagName = tagName,
                        versionName = cleanRemoteVersion,
                        title = title,
                        releaseNotes = body,
                        downloadUrl = downloadUrl,
                        apkSize = apkSize,
                        publishedAt = publishedAt
                    )

                    latestReleaseInfo = releaseInfo

                    if (isNewerVersion(cleanRemoteVersion, cleanCurrentVersion)) {
                        _updateState.value = UpdateUiState.UpdateAvailable(releaseInfo)
                        return@withContext releaseInfo
                    } else {
                        if (!silentCheck) {
                            _updateState.value = UpdateUiState.UpToDate(currentVersion)
                        } else {
                            _updateState.value = UpdateUiState.Idle
                        }
                        return@withContext null
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "檢查更新失敗", e)
                if (!silentCheck) {
                    _updateState.value = UpdateUiState.Error("檢查更新失敗: ${e.localizedMessage ?: "網路異常"}")
                }
                null
            }
        }
    }

    /**
     * 下載 APK 安裝檔案並提供即時進度回調
     */
    suspend fun downloadApk(context: Context, releaseInfo: AppReleaseInfo): File? {
        return withContext(Dispatchers.IO) {
            try {
                val updateDir = File(context.cacheDir, "updates").apply {
                    if (!exists()) mkdirs()
                }
                val outputFile = File(updateDir, "app-update-${releaseInfo.versionName}.apk")

                _updateState.value = UpdateUiState.Downloading(0f, 0L, releaseInfo.apkSize, releaseInfo)

                val request = Request.Builder()
                    .url(releaseInfo.downloadUrl)
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        _updateState.value = UpdateUiState.Error("下載失敗 (HTTP ${response.code})")
                        return@withContext null
                    }

                    val body = response.body ?: run {
                        _updateState.value = UpdateUiState.Error("伺服器未回傳檔案內容")
                        return@withContext null
                    }

                    val totalBytes = if (releaseInfo.apkSize > 0) releaseInfo.apkSize else body.contentLength()
                    var downloadedBytes = 0L

                    body.byteStream().use { input ->
                        FileOutputStream(outputFile).use { output ->
                            val buffer = ByteArray(8 * 1024)
                            var read: Int
                            var lastProgressTime = 0L

                            while (input.read(buffer).also { read = it } != -1) {
                                output.write(buffer, 0, read)
                                downloadedBytes += read

                                val now = System.currentTimeMillis()
                                if (now - lastProgressTime > 150 || downloadedBytes == totalBytes) {
                                    lastProgressTime = now
                                    val progress = if (totalBytes > 0) {
                                        downloadedBytes.toFloat() / totalBytes.toFloat()
                                    } else {
                                        0f
                                    }
                                    _updateState.value = UpdateUiState.Downloading(
                                        progress = progress.coerceIn(0f, 1f),
                                        downloadedBytes = downloadedBytes,
                                        totalBytes = totalBytes,
                                        releaseInfo = releaseInfo
                                    )
                                }
                            }
                            output.flush()
                        }
                    }

                    _updateState.value = UpdateUiState.ReadyToInstall(outputFile, releaseInfo)
                    outputFile
                }
            } catch (e: Exception) {
                Log.e(TAG, "APK 下載失敗", e)
                _updateState.value = UpdateUiState.Error("下載失敗: ${e.localizedMessage ?: "網路連線中斷"}")
                null
            }
        }
    }

    /**
     * 啟動 Android 系統安裝程序
     */
    fun installApk(context: Context, apkFile: File): Boolean {
        return try {
            if (!apkFile.exists() || apkFile.length() == 0L) {
                _updateState.value = UpdateUiState.Error("APK 安裝檔不存在或已損毀")
                return false
            }

            // Android 8.0+ 檢測是否有安裝未知來源權限
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!context.packageManager.canRequestPackageInstalls()) {
                    val settingsIntent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                        data = Uri.parse("package:${context.packageName}")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(settingsIntent)
                    return false
                }
            }

            val apkUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            context.startActivity(installIntent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "啟動安裝失敗", e)
            _updateState.value = UpdateUiState.Error("無法啟動系統安裝程式: ${e.localizedMessage}")
            false
        }
    }

    /**
     * 語意化版本號比較演算法 (例如 "1.0.1" > "1.0.0")
     */
    fun isNewerVersion(remoteVersion: String, currentVersion: String): Boolean {
        try {
            val remoteParts = remoteVersion.split(".").map { it.filter { c -> c.isDigit() }.toIntOrNull() ?: 0 }
            val currentParts = currentVersion.split(".").map { it.filter { c -> c.isDigit() }.toIntOrNull() ?: 0 }

            val maxLen = maxOf(remoteParts.size, currentParts.size)
            for (i in 0 until maxLen) {
                val remoteNum = remoteParts.getOrElse(i) { 0 }
                val currentNum = currentParts.getOrElse(i) { 0 }
                if (remoteNum > currentNum) return true
                if (remoteNum < currentNum) return false
            }
            return false
        } catch (e: Exception) {
            return remoteVersion != currentVersion
        }
    }
}
