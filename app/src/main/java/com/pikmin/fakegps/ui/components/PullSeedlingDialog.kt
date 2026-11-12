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
import com.pikmin.fakegps.utils.RootShell
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.util.Locale

/**
 * 🍄 金盆拉盆助手 (Sprout Pulling Assistant - 方案 A 通用標準版)
 *
 * 專門針對 Pikmin Bloom「拉金盆 / 巨花」流程設計的標準工作流對話框：
 * 1. 自動偵測剪貼簿座標，一鍵傳送就位
 * 2. 一鍵完全關閉遊戲（免手動滑掉）與停止飛人
 * 3. 一鍵直接開關手機系統 GPS 定位（免跳轉設定頁）
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
    var isGpsEnabled by remember { mutableStateOf(RootShell.isGpsEnabled(context)) }
    LaunchedEffect(Unit) {
        // 開啟對話框時：自動讀取剪貼簿並填好座標
        try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val clip = clipboard?.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val clipText = clip.getItemAt(0)?.text?.toString() ?: ""
                val extracted = GeoUtils.extractCoordinatesFromText(clipText)
                if (extracted != null) {
                    latText = String.format(Locale.US, "%.6f", extracted.latitude)
                    lngText = String.format(Locale.US, "%.6f", extracted.longitude)
                    prefs.lastPullSeedlingLat = extracted.latitude
                    prefs.lastPullSeedlingLng = extracted.longitude
                    Toast.makeText(context, "🌱 已自動填入剪貼簿金盆座標", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (_: Exception) {}

        // 定期更新 GPS 狀態
        while (isActive) {
            isGpsEnabled = RootShell.isGpsEnabled(context)
            delay(800)
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
                            Text(
                                text = "${stepNum}",
                                fontSize = 12.sp,
                                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                                color = when {
                                    isCurrent -> MaterialTheme.colorScheme.onPrimary
                                    isDone -> Color(0xFF10B981)
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier
                                    .padding(vertical = 6.dp)
                                    .wrapContentWidth(Alignment.CenterHorizontally)
                            )
                        }
                    }
                }

                // 懸浮迷你控制器開關橫條
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("🌱 遊戲內懸浮控制器", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                if (isOverlayRunning) {
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Surface(
                                        shape = RoundedCornerShape(4.dp),
                                        color = Color(0xFF10B981).copy(alpha = 0.15f)
                                    ) {
                                        Text("已開啟", color = Color(0xFF10B981), fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp))
                                    }
                                }
                            }
                            Text(
                                text = "浮在遊戲畫面最上方，一鍵飛目標/關GPS/關遊戲",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = isOverlayRunning,
                            onCheckedChange = { enable ->
                                if (enable) {
                                    PullSeedlingOverlayService.start(context)
                                } else {
                                    PullSeedlingOverlayService.stop(context)
                                }
                            }
                        )
                    }
                }

                // 當前步驟內容區塊
                when (currentStep) {
                    // ===== 步驟 1：確認目標與傳送就位 =====
                    1 -> {
                        Text(
                            text = "步驟 1：確認目標金盆座標並傳送就位",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "複製目標金盆座標後會自動填入，點擊傳送即可飛至目標位置確認金盆／巨花存在：",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        // 快速填入按鈕組
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                    val clip = clipboard?.primaryClip
                                    val text = if (clip != null && clip.itemCount > 0) clip.getItemAt(0)?.text?.toString() ?: "" else ""
                                    val extracted = GeoUtils.extractCoordinatesFromText(text)
                                    if (extracted != null) {
                                        latText = String.format(Locale.US, "%.6f", extracted.latitude)
                                        lngText = String.format(Locale.US, "%.6f", extracted.longitude)
                                        errorMessage = null
                                        Toast.makeText(context, "已讀取剪貼簿座標！", Toast.LENGTH_SHORT).show()
                                    } else {
                                        errorMessage = "剪貼簿內未偵測到經緯度座標"
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(Icons.Default.ContentPaste, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("從剪貼簿貼上", fontSize = 12.sp)
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
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    text = "② 完整關閉 Pikmin Bloom 遊戲",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )
                                Text(
                                    text = "可點擊下方按鈕一鍵徹底關閉遊戲（免手動滑掉）：",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                Button(
                                    onClick = {
                                        val stopped = RootShell.forceStopPackage(context, PullSeedlingOverlayService.PIKMIN_PACKAGE)
                                        if (stopped) {
                                            Toast.makeText(context, "🛑 Pikmin Bloom 已完全關閉！", Toast.LENGTH_SHORT).show()
                                        } else {
                                            Toast.makeText(context, "請點擊「強行停止」以關閉遊戲", Toast.LENGTH_SHORT).show()
                                            openPikminAppSettings(context)
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("🛑 一鍵完全關閉遊戲 (免手動滑掉)", fontSize = 12.5.sp)
                                }

                                OutlinedButton(
                                    onClick = { openPikminAppSettings(context) },
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("開啟遊戲詳細資訊 (備用手動強停)", fontSize = 12.sp)
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
                            text = "拉盆的關鍵機制是在「無 GPS 訊號」狀態下啟動遊戲。請直接點擊開關切換：",
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
                                        text = if (!isGpsEnabled) "已符合拉盆啟動條件，請進行下一步。" else "點擊下方按鈕直接切換 GPS。",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        // 一鍵直接開關 GPS (無需跳轉設定頁)
                        Button(
                            onClick = {
                                val target = !isGpsEnabled
                                val handled = RootShell.setGpsEnabled(context, target)
                                if (handled) {
                                    isGpsEnabled = target
                                    Toast.makeText(context, if (target) "🟢 GPS 已開啟！" else "⚪ GPS 已關閉！", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "正在開啟系統設定頁...", Toast.LENGTH_SHORT).show()
                                    openLocationSettings(context)
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isGpsEnabled) Color(0xFFEF4444) else Color(0xFF10B981)
                            ),
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.LocationOff, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(if (isGpsEnabled) "⚪ 點擊直接關閉手機 GPS" else "🟢 點擊直接開啟手機 GPS")
                        }

                        OutlinedButton(
                            onClick = { openLocationSettings(context) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("⚙️ 開啟系統定位設定頁面 (備用)", fontSize = 12.sp)
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
                                    "1. 點擊下方按鈕啟動 Pikmin Bloom。\n2. 遊戲進入後會因無 GPS 而停留在遠端目標快取，並提示「請開啟定位功能」。\n3. 此時在懸浮控制器點擊「⚪ 開GPS」或下拉通知列開啟定位。\n4. 遊戲讀取到位置切換，即可順利拉取金盆！",
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

                        if (!isGpsEnabled) {
                            OutlinedButton(
                                onClick = {
                                    val handled = RootShell.setGpsEnabled(context, true)
                                    if (handled) {
                                        isGpsEnabled = true
                                        Toast.makeText(context, "🟢 GPS 已開啟！", Toast.LENGTH_SHORT).show()
                                    } else {
                                        openLocationSettings(context)
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Default.LocationOn, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("🟢 恢復開啟系統 GPS 定位")
                            }
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
    RootShell.launchPackage(context, PullSeedlingOverlayService.PIKMIN_PACKAGE)
}

private fun openPikminAppSettings(context: Context) {
    RootShell.openAppSettings(context, PullSeedlingOverlayService.PIKMIN_PACKAGE)
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
