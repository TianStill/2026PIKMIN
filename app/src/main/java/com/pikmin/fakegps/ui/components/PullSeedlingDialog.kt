package com.pikmin.fakegps.ui.components

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pikmin.fakegps.data.repository.PreferencesRepo
import com.pikmin.fakegps.service.PullSeedlingOverlayService
import com.pikmin.fakegps.utils.GeoUtils
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.util.Locale

/**
 * 🍄 金盆拉盆助手 (Sprout Pulling Assistant - 方案 A 通用標準版)
 *
 * 專門針對 Pikmin Bloom「拉金盆 / 巨花」流程設計的標準工作流對話框：
 * 1. 確認目標與傳送就位
 * 2. 關閉遊戲與停止飛人
 * 3. 關閉手機系統 GPS 定位
 * 4. 開啟遊戲與同步拉盆
 */
@Composable
fun PullSeedlingDialog(
    initialLat: Double,
    initialLng: Double,
    isMocking: Boolean,
    onStartMocking: (latitude: Double, longitude: Double) -> Unit,
    onStopMocking: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { PreferencesRepo(context) }
    val isOverlayRunning by PullSeedlingOverlayService.isRunning.collectAsState()

    // 當前步驟：1..4
    var currentStep by remember { mutableIntStateOf(1) }

    // 座標輸入文字
    var latText by remember {
        mutableStateOf(String.format(Locale.US, "%.6f", if (initialLat != 0.0) initialLat else prefs.lastPullSeedlingLat))
    }
    var lngText by remember {
        mutableStateOf(String.format(Locale.US, "%.6f", if (initialLng != 0.0) initialLng else prefs.lastPullSeedlingLng))
    }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // 即時偵測系統 GPS 開關狀態
    var isGpsEnabled by remember { mutableStateOf(isSystemLocationEnabled(context)) }
    LaunchedEffect(Unit) {
        while (isActive) {
            isGpsEnabled = isSystemLocationEnabled(context)
            delay(1000)
        }
    }

    val parsedLat = latText.toDoubleOrNull()
    val parsedLng = lngText.toDoubleOrNull()
    val isValidCoords = parsedLat != null && parsedLng != null &&
            parsedLat in -90.0..90.0 && parsedLng in -180.0..180.0

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.LocalFlorist,
                        contentDescription = null,
                        tint = Color(0xFF10B981),
                        modifier = Modifier.size(26.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "🌱 金盆拉盆助手",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Pikmin Bloom 遠端拉盆專用輔助流程 (標準版)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // 步驟指示器 (Step Indicator)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val stepTitles = listOf("1. 傳送就位", "2. 停飛關遊戲", "3. 關閉定位", "4. 啟動拉盆")
                    stepTitles.forEachIndexed { index, _ ->
                        val stepNum = index + 1
                        val isCurrent = currentStep == stepNum
                        val isDone = currentStep > stepNum

                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = when {
                                isCurrent -> MaterialTheme.colorScheme.primary
                                isDone -> Color(0xFF10B981).copy(alpha = 0.2f)
                                else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            },
                            border = if (isDone) androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF10B981)) else null,
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 2.dp)
                                .clickable { currentStep = stepNum }
                        ) {
                            Box(
                                modifier = Modifier.padding(vertical = 6.dp, horizontal = 2.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = if (isDone) "✓ $stepNum" else "$stepNum",
                                    fontSize = 11.sp,
                                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                                    color = when {
                                        isCurrent -> MaterialTheme.colorScheme.onPrimary
                                        isDone -> Color(0xFF10B981)
                                        else -> MaterialTheme.colorScheme.outline
                                    }
                                )
                            }
                        }
                    }
                }

                HorizontalDivider()

                // 🎈 遊戲懸浮迷你控制器快捷切換
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (isOverlayRunning) Color(0xFF10B981).copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                    border = if (isOverlayRunning) androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF10B981)) else null,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(text = "🎈", fontSize = 18.sp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = if (isOverlayRunning) "遊戲懸浮控制器：運行中" else "遊戲上方懸浮迷你控制器",
                                    fontSize = 12.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isOverlayRunning) Color(0xFF10B981) else MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "在遊戲畫面上層 1 秒完成各項拉盆動作",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }
                        }
                        Switch(
                            checked = isOverlayRunning,
                            onCheckedChange = { enabled ->
                                if (enabled) {
                                    PullSeedlingOverlayService.start(context)
                                } else {
                                    PullSeedlingOverlayService.stop(context)
                                }
                            }
                        )
                    }
                }

                // 各步驟主要內容
                when (currentStep) {
                    // ===== 步驟 1：確認目標與傳送就位 =====
                    1 -> {
                        Text(
                            text = "步驟 1：輸入目標座標並傳送就位",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "請輸入或貼上目標金盆／巨花座標。點擊傳送後，可直接開啟遊戲確認目標已出現在畫面上。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        // 快捷載入列：[📋 貼上剪貼簿] [📍 載入地圖目前座標]
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                    val clipData = clipboard?.primaryClip
                                    if (clipData != null && clipData.itemCount > 0) {
                                        val text = clipData.getItemAt(0).text?.toString() ?: ""
                                        val extracted = GeoUtils.extractCoordinatesFromText(text)
                                        if (extracted != null) {
                                            latText = String.format(Locale.US, "%.6f", extracted.latitude)
                                            lngText = String.format(Locale.US, "%.6f", extracted.longitude)
                                            errorMessage = null
                                            Toast.makeText(context, "已套用剪貼簿座標", Toast.LENGTH_SHORT).show()
                                        } else {
                                            Toast.makeText(context, "剪貼簿中未偵測到有效座標", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(Icons.Default.ContentPaste, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("貼上剪貼簿", fontSize = 12.sp)
                            }

                            OutlinedButton(
                                onClick = {
                                    latText = String.format(Locale.US, "%.6f", initialLat)
                                    lngText = String.format(Locale.US, "%.6f", initialLng)
                                    errorMessage = null
                                },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(Icons.Default.MyLocation, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("地圖目前點", fontSize = 12.sp)
                            }
                        }

                        // 座標輸入欄位
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = latText,
                                onValueChange = {
                                    latText = it
                                    errorMessage = null
                                },
                                label = { Text("緯度 (Lat)") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = lngText,
                                onValueChange = {
                                    lngText = it
                                    errorMessage = null
                                },
                                label = { Text("經度 (Lng)") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                        }

                        if (errorMessage != null) {
                            Text(
                                text = errorMessage!!,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }

                        // 目前飛人狀態顯示
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (isMocking) Color(0xFF10B981).copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant,
                            border = if (isMocking) androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF10B981)) else null,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(if (isMocking) Color(0xFF10B981) else MaterialTheme.colorScheme.outline)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (isMocking) "飛人模擬運行中（已就定位）" else "飛人目前待命中",
                                    fontSize = 12.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (isMocking) Color(0xFF10B981) else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        // 操作按鈕：立即傳送 & 開啟遊戲
                        Button(
                            onClick = {
                                if (isValidCoords) {
                                    prefs.lastPullSeedlingLat = parsedLat!!
                                    prefs.lastPullSeedlingLng = parsedLng!!
                                    onStartMocking(parsedLat, parsedLng)
                                    Toast.makeText(context, "已飛至目標座標！", Toast.LENGTH_SHORT).show()
                                } else {
                                    errorMessage = "請輸入有效的經緯度座標"
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("🚀 立即傳送並開始飛人")
                        }

                        OutlinedButton(
                            onClick = { launchPikminBloom(context) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("🎮 打開 Pikmin Bloom 遊戲確認")
                        }
                    }

                    // ===== 步驟 2：關閉遊戲與停止飛人 =====
                    2 -> {
                        Text(
                            text = "步驟 2：滑掉遊戲並停止飛人",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "在遊戲中確認金盆/巨花載入完畢後，請依序執行下列操作：",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        // 動作一：一鍵停止飛人
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (!isMocking) Color(0xFF10B981).copy(alpha = 0.12f) else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = if (!isMocking) Icons.Default.CheckCircle else Icons.Default.Warning,
                                        contentDescription = null,
                                        tint = if (!isMocking) Color(0xFF10B981) else MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = if (!isMocking) "① 飛人定位已停止 (OK)" else "① 飛人定位仍在運行中",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp,
                                        color = if (!isMocking) Color(0xFF10B981) else MaterialTheme.colorScheme.error
                                    )
                                }
                                if (isMocking) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Button(
                                        onClick = {
                                            onStopMocking()
                                            Toast.makeText(context, "飛人已停止！", Toast.LENGTH_SHORT).show()
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("🛑 立即停止飛人")
                                    }
                                }
                            }
                        }

                        // 動作二：滑掉遊戲
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    text = "② 完整關閉 Pikmin Bloom 遊戲",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )
                                Text(
                                    text = "• 請在手機多工切換畫面中，將 Pikmin Bloom 向上滑掉完全關閉。\n• 若想確保徹底結束，可點擊下方捷徑前往應用資訊點擊「強行停止」。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                OutlinedButton(
                                    onClick = { openPikminAppSettings(context) },
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("開啟遊戲詳細資訊 (可強行停止)", fontSize = 12.sp)
                                }
                            }
                        }
                    }

                    // ===== 步驟 3：關閉手機系統 GPS 定位 =====
                    3 -> {
                        Text(
                            text = "步驟 3：關閉手機系統 GPS 定位",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "拉盆的關鍵機制是在「無 GPS 訊號」狀態下啟動遊戲。請將手機系統定位開關關閉。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        // 即時狀態卡片
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (!isGpsEnabled) Color(0xFF10B981).copy(alpha = 0.15f) else Color(0xFFF59E0B).copy(alpha = 0.15f),
                            border = androidx.compose.foundation.BorderStroke(
                                1.5.dp,
                                if (!isGpsEnabled) Color(0xFF10B981) else Color(0xFFF59E0B)
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = if (!isGpsEnabled) Icons.Default.CheckCircle else Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = if (!isGpsEnabled) Color(0xFF10B981) else Color(0xFFF59E0B),
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = if (!isGpsEnabled) "✅ 手機 GPS：已成功關閉！" else "⚠️ 手機 GPS：目前開啟中",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp,
                                        color = if (!isGpsEnabled) Color(0xFF10B981) else Color(0xFFD97706)
                                    )
                                    Text(
                                        text = if (!isGpsEnabled) "已符合拉盆啟動條件，請進行下一步。" else "請點擊下方按鈕前往關閉手機定位。",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        // 一鍵前往定位設定
                        Button(
                            onClick = { openLocationSettings(context) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("⚙️ 快速開啟系統定位設定關閉 GPS")
                        }
                    }

                    // ===== 步驟 4：開啟遊戲與同步拉盆 =====
                    4 -> {
                        Text(
                            text = "步驟 4：啟動遊戲並同步拉盆",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF10B981)
                        )
                        Text(
                            text = "最後一步！保持手機 GPS 關閉的狀態下開啟遊戲，待讀取進入後再開啟定位：",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        // 實戰流程提醒卡片
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("💡 拉盆最後動作說明：", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                Text(
                                    "1. 點擊下方按鈕啟動 Pikmin Bloom。\n2. 遊戲進入後會因無 GPS 而停留在遠端目標快取，並顯示「請開啟定位功能」或 GPS 訊號未找到。\n3. 此時下拉手機通知列或在系統提示時「開啟定位」。\n4. 遊戲讀取到真實座標與遠端快取的轉移，即可順利拉取金盆！",
                                    fontSize = 12.sp,
                                    lineHeight = 18.sp
                                )
                            }
                        }

                        // 一鍵開啟遊戲按鈕
                        Button(
                            onClick = { launchPikminBloom(context) },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("🎮 立即啟動 Pikmin Bloom 開拉！", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (currentStep > 1) {
                    OutlinedButton(
                        onClick = { currentStep -= 1 },
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("上一步")
                    }
                }

                if (currentStep < 4) {
                    Button(
                        onClick = {
                            if (currentStep == 1 && isValidCoords) {
                                prefs.lastPullSeedlingLat = parsedLat!!
                                prefs.lastPullSeedlingLng = parsedLng!!
                            }
                            currentStep += 1
                        },
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("下一步")
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                } else {
                    Button(
                        onClick = onDismiss,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("🎉 完成關閉")
                    }
                }
            }
        },
        dismissButton = {
            if (currentStep == 4) {
                TextButton(onClick = { currentStep = 1 }) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("再次拉盆")
                }
            } else {
                TextButton(onClick = onDismiss) {
                    Text("取消關閉")
                }
            }
        }
    )
}

private fun launchPikminBloom(context: Context) {
    val intent = context.packageManager.getLaunchIntentForPackage("com.nianticlabs.pikmin")
    if (intent != null) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    } else {
        Toast.makeText(context, "未安裝 Pikmin Bloom 遊戲", Toast.LENGTH_SHORT).show()
    }
}

private fun openPikminAppSettings(context: Context) {
    try {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", "com.nianticlabs.pikmin", null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        Toast.makeText(context, "無法開啟應用程式資訊頁面", Toast.LENGTH_SHORT).show()
    }
}

private fun openLocationSettings(context: Context) {
    try {
        val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        Toast.makeText(context, "無法開啟定位設定頁面", Toast.LENGTH_SHORT).show()
    }
}

private fun isSystemLocationEnabled(context: Context): Boolean {
    val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        lm.isLocationEnabled
    } else {
        @Suppress("DEPRECATION")
        lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }
}
