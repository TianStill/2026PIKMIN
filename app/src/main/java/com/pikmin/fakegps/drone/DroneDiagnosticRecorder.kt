package com.pikmin.fakegps.drone

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.core.content.FileProvider
import com.pikmin.fakegps.BuildConfig
import com.pikmin.fakegps.data.model.LocationPoint
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Small rolling on-device corpus used to reproduce cruise false positives and misses. */
object DroneDiagnosticRecorder {
    private const val MAX_FRAMES = 24
    private const val MIN_FRAME_INTERVAL_MS = 500L
    private const val MAX_REPORTS = 5
    private var lastSavedFrameTime = 0L
    private val lock = Any()

    private fun root(context: Context) = File(context.cacheDir, "drone-diagnostics")
    private fun active(context: Context) = File(root(context), "active")
    private fun utc(time: Long = System.currentTimeMillis()): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(time))

    fun startSession(context: Context, targets: Set<String>, totalPoints: Int) = synchronized(lock) {
        val directory = active(context)
        directory.deleteRecursively()
        directory.mkdirs()
        lastSavedFrameTime = 0L
        File(directory, "session.txt").writeText(
            "createdUtc=${utc()}\nappVersion=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n" +
                "device=${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}\n" +
                "android=${android.os.Build.VERSION.RELEASE} sdk=${android.os.Build.VERSION.SDK_INT}\n" +
                "targets=${targets.sorted().joinToString(",")}\ntotalPoints=$totalPoints\n"
        )
    }

    fun recordFrame(
        context: Context,
        bitmap: Bitmap,
        frameTime: Long,
        waypointIndex: Int,
        waypoint: LocationPoint,
        trace: String
    ) = synchronized(lock) {
        val directory = active(context).apply { mkdirs() }
        File(directory, "analysis.log").appendText(
            "${utc()} elapsed=$frameTime waypoint=$waypointIndex " +
                "lat=${"%.7f".format(Locale.US, waypoint.latitude)} " +
                "lng=${"%.7f".format(Locale.US, waypoint.longitude)} $trace\n"
        )
        if (frameTime - lastSavedFrameTime < MIN_FRAME_INTERVAL_MS) return@synchronized
        lastSavedFrameTime = frameTime
        val frames = File(directory, "frames").apply { mkdirs() }
        val output = File(frames, "%013d-wp%05d.jpg".format(Locale.US, frameTime, waypointIndex))
        val maxSide = maxOf(bitmap.width, bitmap.height)
        val saved = if (maxSide > 960) {
            val scale = 960f / maxSide
            Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
        } else bitmap
        try {
            FileOutputStream(output).use { saved.compress(Bitmap.CompressFormat.JPEG, 72, it) }
        } finally {
            if (saved !== bitmap) saved.recycle()
        }
        frames.listFiles()?.sortedBy { it.lastModified() }?.dropLast(MAX_FRAMES)?.forEach { it.delete() }
    }

    fun createReport(context: Context, issue: String, status: String): File = synchronized(lock) {
        val source = active(context)
        require(source.isDirectory) { "尚無巡航診斷紀錄，請先執行一次巡航" }
        val reports = File(root(context), "reports").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val zipFile = File(reports, "pikmin-$issue-$stamp.zip")
        ZipOutputStream(FileOutputStream(zipFile)).use { zip ->
            zip.putNextEntry(ZipEntry("report.txt"))
            zip.write("issue=$issue\nmarkedUtc=${utc()}\nstatus=$status\n".toByteArray())
            zip.closeEntry()
            source.walkTopDown().filter { it.isFile }.forEach { file ->
                zip.putNextEntry(ZipEntry("diagnostic/${file.relativeTo(source).invariantSeparatorsPath}"))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        reports.listFiles { file -> file.extension == "zip" }?.sortedByDescending { it.lastModified() }
            ?.drop(MAX_REPORTS)?.forEach { it.delete() }
        zipFile
    }

    fun shareIntent(context: Context, report: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", report)
        return Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Pikmin 巡航診斷 ${report.name}")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
