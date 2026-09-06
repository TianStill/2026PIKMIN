package com.pikmin.fakegps.update

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.pikmin.fakegps.R
import com.pikmin.fakegps.ui.MainActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

object AppUpdateManager {
    private const val API = "https://api.github.com/repos/TianStill/2026PIKMIN/releases/latest"
    private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS).build()
    // Downloads intentionally survive UI dismissal, and use only application context.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val downloadLock = Mutex()
    private val checkLock = Mutex()
    private var downloadJob: Job? = null
    private val _updateState = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
    val updateState = _updateState.asStateFlow()
    var latestReleaseInfo: AppReleaseInfo? = null
        private set

    fun resetState() { if (_updateState.value !is UpdateUiState.Downloading) _updateState.value = UpdateUiState.Idle }

    suspend fun checkForUpdates(currentVersion: String, silentCheck: Boolean = false): AppReleaseInfo? = checkLock.withLock {
        if (downloadJob?.isActive == true || _updateState.value is UpdateUiState.ReadyToInstall) return@withLock null
        if (!silentCheck) _updateState.value = UpdateUiState.Checking
        try {
            val release = withContext(Dispatchers.IO) {
                client.newCall(Request.Builder().url(API).header("Accept", "application/vnd.github+json").build()).execute().use { response ->
                    check(response.isSuccessful) { "查詢更新失敗 (HTTP ${response.code})" }
                    val json = JSONObject(response.body?.string() ?: error("更新資訊為空"))
                    val assets = json.getJSONArray("assets")
                    val apks = (0 until assets.length()).map { assets.getJSONObject(it) }
                        .filter { it.getString("name").endsWith(".apk", true) }
                    val asset = apks.firstOrNull { it.getString("name") == "app-release.apk" }
                        ?: apks.singleOrNull() ?: error("發布附件沒有唯一可識別的 APK")
                    val tag = json.getString("tag_name")
                    val digest = asset.optString("digest").takeIf { it.isNotBlank() && it != "null" }
                    if (digest != null) require(digest.startsWith("sha256:")) { "不支援的 APK 雜湊格式" }
                    AppReleaseInfo(tag, tag.trimStart('v', 'V'), json.optString("name", tag),
                        json.optString("body"), asset.getString("browser_download_url"), asset.getLong("size"),
                        json.optString("published_at"), digest?.removePrefix("sha256:"))
                }
            }
            latestReleaseInfo = release
            if (downloadJob?.isActive == true) return@withLock null
            val order = VersionOrder.compare(release.versionName, currentVersion) ?: error("無法辨識版本格式")
            if (order > 0) {
                _updateState.value = UpdateUiState.UpdateAvailable(release)
                release
            } else {
                if (!silentCheck) _updateState.value = UpdateUiState.UpToDate(currentVersion)
                null
            }
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) {
            if (!silentCheck && downloadJob?.isActive != true) _updateState.value = UpdateUiState.Error(e.localizedMessage ?: "查詢失敗")
            null
        }
    }

    fun startDownload(context: Context, release: AppReleaseInfo) {
        if (downloadJob?.isActive == true) return
        val app = context.applicationContext
        downloadJob = scope.launch { downloadApk(app, release) }
    }

    suspend fun downloadApk(context: Context, releaseInfo: AppReleaseInfo): File? = downloadLock.withLock {
        withContext(Dispatchers.IO) {
            val directory = File(context.cacheDir, "updates").apply { mkdirs() }
            val partial = File(directory, "app-update.apk.part")
            val output = File(directory, "app-update.apk")
            try {
                require(releaseInfo.downloadUrl.startsWith("https://github.com/TianStill/2026PIKMIN/releases/download/")) { "更新來源不符" }
                _updateState.value = UpdateUiState.Downloading(0f, 0, releaseInfo.apkSize, releaseInfo)
                client.newCall(Request.Builder().url(releaseInfo.downloadUrl).build()).execute().use { response ->
                    check(response.isSuccessful) { "下載失敗 (HTTP ${response.code})" }
                    val body = response.body ?: error("下載內容為空")
                    val total = releaseInfo.apkSize.takeIf { it > 0 } ?: body.contentLength()
                    val digest = MessageDigest.getInstance("SHA-256")
                    var downloaded = 0L
                    var lastProgress = 0L
                    body.byteStream().use { input ->
                        partial.outputStream().use { target ->
                            val buffer = ByteArray(8192)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                target.write(buffer, 0, count)
                                digest.update(buffer, 0, count)
                                downloaded += count
                                require(total <= 0 || downloaded <= total) { "APK 超出預期大小" }
                                val now = android.os.SystemClock.elapsedRealtime()
                                if (now - lastProgress >= 500) {
                                    lastProgress = now
                                    val progress = if (total > 0) (downloaded.toFloat() / total).coerceIn(0f, 1f) else 0f
                                    _updateState.value = UpdateUiState.Downloading(progress, downloaded, total, releaseInfo)
                                    notify(context, "正在下載更新", "${(progress * 100).toInt()}%", (progress * 100).toInt())
                                }
                            }
                        }
                    }
                    DownloadIntegrity.verify(downloaded, total, digest.digest().joinToString("") { "%02x".format(it) }, releaseInfo.sha256)
                }
                verifyPackage(context, partial, releaseInfo.versionName)
                java.nio.file.Files.move(partial.toPath(), output.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                _updateState.value = UpdateUiState.ReadyToInstall(output, releaseInfo)
                notify(context, "更新檔已驗證", "開啟 APP 確認安裝")
                output
            } catch (e: CancellationException) {
                _updateState.value = UpdateUiState.Error("下載已取消")
                throw e
            } catch (e: Exception) {
                _updateState.value = UpdateUiState.Error(e.localizedMessage ?: "下載或驗證失敗")
                notify(context, "更新失敗", e.localizedMessage ?: "請開啟 APP 查看")
                null
            } finally { partial.delete() }
        }
    }

    @Suppress("DEPRECATION")
    private fun verifyPackage(context: Context, file: File, expectedVersion: String? = null) {
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val apk = context.packageManager.getPackageArchiveInfo(file.absolutePath, flags) ?: error("APK 格式無效")
        val current = context.packageManager.getPackageInfo(context.packageName, flags)
        require(apk.packageName == context.packageName) { "APK 套件名稱不符" }
        val apkCode = if (Build.VERSION.SDK_INT >= 28) apk.longVersionCode else apk.versionCode.toLong()
        val currentCode = if (Build.VERSION.SDK_INT >= 28) current.longVersionCode else current.versionCode.toLong()
        require(apkCode > currentCode) { "APK 版本未高於目前版本" }
        if (expectedVersion != null) require(apk.versionName == expectedVersion) { "APK 版本與發布標籤不符" }
        fun certificates(info: PackageInfo): Set<String> {
            val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
            return signatures.orEmpty().map {
                MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { byte -> "%02x".format(byte) }
            }.toSet()
        }
        val installed = certificates(current)
        require(installed.isNotEmpty() && installed == certificates(apk)) { "APK 簽章與已安裝版本不同，不能直接覆蓋更新" }
    }

    fun installApk(context: Context, apkFile: File): Boolean = try {
        verifyPackage(context, apkFile)
        if (!context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            false
        } else {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)
            context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        }
    } catch (e: Exception) {
        _updateState.value = UpdateUiState.Error(e.localizedMessage ?: "無法安裝")
        false
    }

    fun announceUpdate(context: Context, release: AppReleaseInfo) {
        notify(context, "發現新版本 ${release.tagName}", "開啟 APP 查看更新並下載")
    }

    private fun notify(context: Context, title: String, text: String, progress: Int? = null) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = "app_updates"
        manager.createNotificationChannel(NotificationChannel(channel, "APP 更新", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(context, 2002, Intent(context, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = NotificationCompat.Builder(context, channel).setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title).setContentText(text).setContentIntent(open).setOnlyAlertOnce(true)
            .setOngoing(progress != null).setAutoCancel(progress == null)
        if (progress != null) builder.setProgress(100, progress, false)
        try { manager.notify(2002, builder.build()) } catch (_: SecurityException) { }
    }

    fun isNewerVersion(remoteVersion: String, currentVersion: String): Boolean =
        (VersionOrder.compare(remoteVersion, currentVersion) ?: 0) > 0
}
