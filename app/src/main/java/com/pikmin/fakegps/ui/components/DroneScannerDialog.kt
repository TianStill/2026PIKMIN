package com.pikmin.fakegps.ui.components

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pikmin.fakegps.cv.DetectedMushroom
import com.pikmin.fakegps.cv.MushroomCategory
import com.pikmin.fakegps.cv.MushroomDetector
import com.pikmin.fakegps.cv.MushroomType
import com.pikmin.fakegps.drone.DroneScannerManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DroneScannerDialog(
    currentLat: Double,
    currentLng: Double,
    onStartDroneScan: (radiusKm: Double, targetTypes: Set<MushroomType>, dwellSec: Float) -> Unit,
    onResumeDroneScan: () -> Unit = {},
    onStopDroneScan: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scanStatus by DroneScannerManager.status.collectAsState()

    var selectedRadiusKm by remember { mutableStateOf(1.5) }
    var selectedTypes by remember { mutableStateOf(MushroomType.ALL_TARGETS) }
    var dwellSeconds by remember { mutableStateOf(3.2f) }

    // 相片辨識測試狀態
    var testBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var testDetections by remember { mutableStateOf<List<DetectedMushroom>?>(null) }
    var showTestResultDialog by remember { mutableStateOf(false) }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val source = ImageDecoder.createSource(context.contentResolver, uri)
                    ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                        decoder.isMutableRequired = true
                    }
                } else {
                    @Suppress("DEPRECATION")
                    MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
                }

                val targetsToTest = if (selectedTypes.isNotEmpty()) selectedTypes else MushroomType.ALL_TARGETS
                val detections = MushroomDetector.detectMushrooms(bitmap, targetsToTest)
                val previewBitmap = MushroomDetector.drawDetectionPreview(bitmap, detections)
                testBitmap = previewBitmap
                testDetections = detections
                showTestResultDialog = true
            } catch (e: Exception) {
                Toast.makeText(context, "讀取相片失敗：${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Radar,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text("🛸 無人機尋菇雷達", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
                    Text("專屬鎖定：巨大活動菇、大顏色菇、大元素菇", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // 1. 巡航狀態指示卡片
                if (scanStatus.isScanning) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.5.dp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = "無人機雷達巡航中...",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            LinearProgressIndicator(
                                progress = if (scanStatus.totalPoints > 0) scanStatus.currentIndex.toFloat() / scanStatus.totalPoints else 0f,
                                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = scanStatus.statusMessage,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                } else if (scanStatus.foundTarget != null) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = Color(0xFF16A34A).copy(alpha = 0.15f),
                        border = androidx.compose.foundation.BorderStroke(1.5.dp, Color(0xFF16A34A)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = Color(0xFF16A34A),
                                    modifier = Modifier.size(22.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "已鎖定目標蘑菇！",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF16A34A)
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = scanStatus.statusMessage,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            scanStatus.foundLocation?.let { loc ->
                                Text(
                                    text = "座標：${String.format("%.6f, %.6f", loc.latitude, loc.longitude)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }
                            Spacer(modifier = Modifier.height(10.dp))
                            Button(
                                onClick = onResumeDroneScan,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(imageVector = Icons.Default.FastForward, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("⏩ 繼續向外搜尋下一顆菇")
                            }
                        }
                    }
                }

                // 2. 搜尋半徑選擇 (Radius)
                Column {
                    Text("📍 巡弋半徑範圍", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(1.0 to "1 km", 1.5 to "1.5 km", 2.0 to "2 km", 3.0 to "3 km").forEach { (km, label) ->
                            val isSelected = selectedRadiusKm == km
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { selectedRadiusKm = km }
                            ) {
                                Box(
                                    modifier = Modifier.padding(vertical = 8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = label,
                                        style = MaterialTheme.typography.labelLarge,
                                        color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }

                // 3. 目標蘑菇分類選擇 (巨大活動菇 / 大顏色菇 / 大元素菇)
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("🍄 目標蘑菇種類 (多選)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = if (selectedTypes.size == MushroomType.ALL_TARGETS.size) "取消全選" else "全選",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.clickable {
                                    selectedTypes = if (selectedTypes.size == MushroomType.ALL_TARGETS.size) {
                                        setOf(MushroomType.GIANT_EVENT)
                                    } else {
                                        MushroomType.ALL_TARGETS
                                    }
                                }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // 👑 類別 1: 巨大活動特殊菇
                    Text("👑 活動限定", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.height(4.dp))
                    val isEventSelected = selectedTypes.contains(MushroomType.GIANT_EVENT)
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (isEventSelected) Color(0xFFF59E0B).copy(alpha = 0.25f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        border = if (isEventSelected) androidx.compose.foundation.BorderStroke(1.5.dp, Color(0xFFF59E0B)) else null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selectedTypes = if (isEventSelected) {
                                    if (selectedTypes.size > 1) selectedTypes - MushroomType.GIANT_EVENT else selectedTypes
                                } else {
                                    selectedTypes + MushroomType.GIANT_EVENT
                                }
                            }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (isEventSelected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                                contentDescription = null,
                                tint = if (isEventSelected) Color(0xFFF59E0B) else MaterialTheme.colorScheme.outline,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "巨大活動特殊菇 (萬聖節/神秘/派對等限定巨大菇)",
                                fontSize = 13.sp,
                                fontWeight = if (isEventSelected) FontWeight.Bold else FontWeight.Normal,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // ⚡ 類別 2: 大元素菇 (火、水、電、水晶、毒)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("⚡ 大元素菇 (全5種)", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        Text(
                            text = "只選元素菇",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.clickable { selectedTypes = MushroomType.ELEMENT_TARGETS }
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    val elementTypes = MushroomType.entries.filter { it.category == MushroomCategory.LARGE_ELEMENT }
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        elementTypes.chunked(3).forEach { rowTypes ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                rowTypes.forEach { type ->
                                    val isSelected = selectedTypes.contains(type)
                                    Surface(
                                        shape = RoundedCornerShape(10.dp),
                                        color = if (isSelected) Color(type.colorHex).copy(alpha = 0.25f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                        border = if (isSelected) androidx.compose.foundation.BorderStroke(1.5.dp, Color(type.colorHex)) else null,
                                        modifier = Modifier
                                            .weight(1f)
                                            .clickable {
                                                selectedTypes = if (isSelected) {
                                                    if (selectedTypes.size > 1) selectedTypes - type else selectedTypes
                                                } else {
                                                    selectedTypes + type
                                                }
                                            }
                                    ) {
                                        Box(
                                            modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = type.title.replace("大型", "大"),
                                                fontSize = 11.5.sp,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                color = if (isSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline
                                            )
                                        }
                                    }
                                }
                                // 若最後一行未填滿 3 個，補齊空白佔位
                                if (rowTypes.size < 3) {
                                    repeat(3 - rowTypes.size) {
                                        Spacer(modifier = Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // 🌈 類別 3: 大顏色菇 (紅、黃、藍、紫、白、粉、灰)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("🌈 大顏色菇 (全7種)", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        Text(
                            text = "只選顏色菇",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.clickable { selectedTypes = MushroomType.COLOR_TARGETS }
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    val colorTypes = MushroomType.entries.filter { it.category == MushroomCategory.LARGE_COLOR }
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        colorTypes.chunked(4).forEach { rowTypes ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                rowTypes.forEach { type ->
                                    val isSelected = selectedTypes.contains(type)
                                    Surface(
                                        shape = RoundedCornerShape(10.dp),
                                        color = if (isSelected) Color(type.colorHex).copy(alpha = 0.25f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                        border = if (isSelected) androidx.compose.foundation.BorderStroke(1.5.dp, Color(type.colorHex)) else null,
                                        modifier = Modifier
                                            .weight(1f)
                                            .clickable {
                                                selectedTypes = if (isSelected) {
                                                    if (selectedTypes.size > 1) selectedTypes - type else selectedTypes
                                                } else {
                                                    selectedTypes + type
                                                }
                                            }
                                    ) {
                                        Box(
                                            modifier = Modifier.padding(vertical = 8.dp, horizontal = 2.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = type.shortName,
                                                fontSize = 11.5.sp,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                color = if (isSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline
                                            )
                                        }
                                    }
                                }
                                if (rowTypes.size < 4) {
                                    repeat(4 - rowTypes.size) {
                                        Spacer(modifier = Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    }
                }

                // 4. 每個點加載等待時間
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("⏱️ 每個網格停留時間", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Text("${String.format("%.1f", dwellSeconds)} 秒", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    }
                    Text("給予 Pikmin Bloom 地圖讀取周邊蘑菇之緩衝時間", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                    Slider(
                        value = dwellSeconds,
                        onValueChange = { dwellSeconds = it },
                        valueRange = 2.0f..6.0f,
                        steps = 7
                    )
                }

                HorizontalDivider()

                // 5. 圖片辨識測試工具
                OutlinedButton(
                    onClick = { photoPickerLauncher.launch("image/*") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(imageVector = Icons.Default.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("📸 從相簿選取截圖測試辨識效果")
                }
            }
        },
        confirmButton = {
            if (scanStatus.isScanning) {
                Button(
                    onClick = onStopDroneScan,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(imageVector = Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("停止無人機")
                }
            } else if (scanStatus.foundTarget != null) {
                Button(
                    onClick = onResumeDroneScan,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(imageVector = Icons.Default.FastForward, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("繼續搜尋下一個菇")
                }
            } else {
                Button(
                    onClick = {
                        onStartDroneScan(selectedRadiusKm, selectedTypes, dwellSeconds)
                    },
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("啟動無人機掃描")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("關閉")
            }
        }
    )

    // 相片檢測結果彈出視窗
    if (showTestResultDialog && testBitmap != null) {
        AlertDialog(
            onDismissRequest = { showTestResultDialog = false },
            title = {
                Text("📸 蘑菇辨識分析結果", fontWeight = FontWeight.Bold)
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    val count = testDetections?.size ?: 0
                    if (count > 0) {
                        Text(
                            text = "✅ 成功辨識出 $count 顆目標蘑菇！已在下方高亮標註：",
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                    } else {
                        Text(
                            text = "未在畫面上偵測到符合特徵之大蘑菇/特殊菇（小菇與普通菇已被自動過濾）。",
                            color = MaterialTheme.colorScheme.outline
                        )
                    }

                    Image(
                        bitmap = testBitmap!!.asImageBitmap(),
                        contentDescription = "Detection Preview",
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 360.dp)
                            .clip(RoundedCornerShape(12.dp))
                    )

                    testDetections?.forEach { m ->
                        Text("• ${m.type.title} - 信賴度 ${(m.confidence * 100).toInt()}%")
                    }
                }
            },
            confirmButton = {
                Button(onClick = { showTestResultDialog = false }) {
                    Text("確定")
                }
            }
        )
    }
}
