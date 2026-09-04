package com.pikmin.fakegps.update

import java.io.File

/**
 * GitHub Release 最新版本資訊模型
 */
data class AppReleaseInfo(
    val tagName: String,
    val versionName: String,
    val title: String,
    val releaseNotes: String,
    val downloadUrl: String,
    val apkSize: Long,
    val publishedAt: String
)

/**
 * 應用程式更新狀態
 */
sealed class UpdateUiState {
    object Idle : UpdateUiState()
    object Checking : UpdateUiState()
    data class UpdateAvailable(val releaseInfo: AppReleaseInfo) : UpdateUiState()
    data class UpToDate(val currentVersion: String) : UpdateUiState()
    data class Downloading(
        val progress: Float,
        val downloadedBytes: Long,
        val totalBytes: Long,
        val releaseInfo: AppReleaseInfo
    ) : UpdateUiState()
    data class ReadyToInstall(val apkFile: File, val releaseInfo: AppReleaseInfo) : UpdateUiState()
    data class Error(val message: String) : UpdateUiState()
}
