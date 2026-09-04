package com.pikmin.fakegps.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.pikmin.fakegps.ui.components.*
import com.pikmin.fakegps.ui.theme.AccentGreen
import com.pikmin.fakegps.ui.theme.AccentRed
import com.pikmin.fakegps.ui.theme.FakeGPSTheme
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import com.pikmin.fakegps.ui.viewmodel.MainViewModel
import com.pikmin.fakegps.utils.PermissionHelper
import com.pikmin.fakegps.BuildConfig
import com.pikmin.fakegps.update.AppUpdateManager
import com.pikmin.fakegps.update.UpdateUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        // 權限回傳處理
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        checkAndRequestPermissions()

        setContent {
            FakeGPSTheme {
                MainScreen(viewModel = viewModel)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        checkClipboardForCoordinates()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            checkClipboardForCoordinates()
        }
    }

    private fun checkClipboardForCoordinates() {
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            if (clipboard != null && clipboard.hasPrimaryClip()) {
                val clipData = clipboard.primaryClip
                if (clipData != null && clipData.itemCount > 0) {
                    val text = clipData.getItemAt(0).text?.toString() ?: ""
                    if (text.isNotBlank()) {
                        viewModel.checkAndApplyClipboard(text)
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun checkAndRequestPermissions() {
        val permissionsToRequest = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missingPermissions = permissionsToRequest.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            permissionLauncher.launch(missingPermissions.toTypedArray())
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainViewModel) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val targetLocation by viewModel.targetLocation.collectAsState()
    val isMocking by viewModel.isMocking.collectAsState()
    val bookmarks by viewModel.bookmarks.collectAsState()
    val enableJitter by viewModel.enableJitter.collectAsState()
    val isAutoClipboardEnabled by viewModel.isAutoClipboardEnabled.collectAsState()
    val mapType by viewModel.mapType.collectAsState()

    val searchResults by viewModel.searchResults.collectAsState()
    val isSearching by viewModel.isSearching.collectAsState()
    val history by viewModel.history.collectAsState()

    val droneStatus by com.pikmin.fakegps.drone.DroneScannerManager.status.collectAsState()
    val updateState by AppUpdateManager.updateState.collectAsState()

    // 啟動時在背景自動檢查更新
    LaunchedEffect(Unit) {
        AppUpdateManager.checkForUpdates(BuildConfig.VERSION_NAME, silentCheck = true)
    }

    var searchQuery by remember { mutableStateOf("") }
    var showFavoritesSheet by remember { mutableStateOf(false) }
    var showHistorySheet by remember { mutableStateOf(false) }
    var showCoordinatesDialog by remember { mutableStateOf(false) }
    var showLayerMenu by remember { mutableStateOf(false) }
    var showDroneDialog by remember { mutableStateOf(false) }

    var pendingScanParams by remember { mutableStateOf<Triple<Double, Set<com.pikmin.fakegps.cv.MushroomType>, Float>?>(null) }
    val mediaProjectionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK && result.data != null) {
            // Android 14+ 必須先啟動支援 MediaProjection 的 ForegroundService
            com.pikmin.fakegps.service.MockLocationService.enableMediaProjection(context)

            val resultCode = result.resultCode
            val data = result.data!!
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                val ok = com.pikmin.fakegps.drone.DroneScannerManager.setupMediaProjection(context, resultCode, data)
                if (!ok) {
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        com.pikmin.fakegps.drone.DroneScannerManager.setupMediaProjection(context, resultCode, data)
                    }, 350L)
                }
                pendingScanParams?.let { (radius, types, dwell) ->
                    com.pikmin.fakegps.drone.DroneScannerManager.startScan(
                        context = context,
                        centerLat = targetLocation.latitude,
                        centerLng = targetLocation.longitude,
                        radiusKm = radius,
                        targetTypes = types,
                        dwellSeconds = dwell
                    )
                }
            }, 350L)
            Toast.makeText(context, "🛸 無人機已起飛！請切換至《Pikmin Bloom》遊戲畫面！", Toast.LENGTH_LONG).show()
        } else {
            // 若取消授權截圖，依然照常執行 GPS 網格巡弋
            pendingScanParams?.let { (radius, types, dwell) ->
                com.pikmin.fakegps.drone.DroneScannerManager.startScan(
                    context = context,
                    centerLat = targetLocation.latitude,
                    centerLng = targetLocation.longitude,
                    radiusKm = radius,
                    targetTypes = types,
                    dwellSeconds = dwell
                )
            }
            Toast.makeText(context, "🛸 無人機自走巡弋已啟動！請切換至遊戲！", Toast.LENGTH_LONG).show()
        }
    }

    LaunchedEffect(droneStatus.currentCoordinate) {
        droneStatus.currentCoordinate?.let { loc ->
            if (droneStatus.isScanning) {
                viewModel.setTargetLocation(loc.latitude, loc.longitude)
            }
        }
    }

    LaunchedEffect(droneStatus.foundLocation) {
        droneStatus.foundLocation?.let { loc ->
            viewModel.setTargetLocation(loc.latitude, loc.longitude)
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize()
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // 1. 全螢幕互動地圖 (Google 高清圖資 / 衛星空照圖 / OSM，支援歷史 Pin 標記)
            MapViewContainer(
                targetLocation = targetLocation,
                mapType = mapType,
                historyList = history,
                onMapCenterChanged = { lat, lng ->
                    viewModel.setTargetLocation(lat, lng)
                }
            )

            // 2. 頂部搜尋欄與單獨右上角檢查版本按鈕
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.Top
            ) {
                LocationSearchBar(
                    query = searchQuery,
                    onQueryChange = { searchQuery = it },
                    onSearch = { viewModel.searchLocation(it) },
                    searchResults = searchResults,
                    isSearching = isSearching,
                    onResultSelected = { result ->
                        viewModel.setTargetLocation(result.latitude, result.longitude)
                        viewModel.clearSearchResults()
                        searchQuery = result.displayName.split(",").firstOrNull() ?: ""
                    },
                    onClearResults = { viewModel.clearSearchResults() },
                    modifier = Modifier.weight(1f)
                )

                Spacer(modifier = Modifier.width(10.dp))

                // 🔄 單獨右上角檢查版本按鈕 (圓形卡片懸浮按鈕)
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                    shadowElevation = 6.dp,
                    tonalElevation = 3.dp,
                    modifier = Modifier.size(52.dp)
                ) {
                    IconButton(
                        onClick = {
                            CoroutineScope(Dispatchers.Main).launch {
                                AppUpdateManager.checkForUpdates(BuildConfig.VERSION_NAME, silentCheck = false)
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    ) {
                        Icon(
                            imageVector = Icons.Default.SystemUpdate,
                            contentDescription = "Check for Updates",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                }
            }

            // 3. 右側功能工具欄 (直放靠齊右下方，加大圖示方便大拇指單手按取)
            Column(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(bottom = 115.dp, end = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 懸浮工具膠囊卡片
                Surface(
                    shape = RoundedCornerShape(26.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                    shadowElevation = 8.dp,
                    tonalElevation = 3.dp
                ) {
                    Column(
                        modifier = Modifier.padding(vertical = 6.dp, horizontal = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // 📍 1. 回到目前位置 (My Location)
                        IconButton(
                            onClick = { viewModel.moveToCurrentLocation() },
                            modifier = Modifier.size(52.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.MyLocation,
                                contentDescription = "My Location",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(28.dp)
                            )
                        }

                        // 🗺️ 2. 地圖圖層切換 (Google 道路 / 衛星 / 地形 / OSM)
                        Box {
                            IconButton(
                                onClick = { showLayerMenu = true },
                                modifier = Modifier.size(52.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Layers,
                                    contentDescription = "Map Layers",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                            DropdownMenu(
                                expanded = showLayerMenu,
                                onDismissRequest = { showLayerMenu = false }
                            ) {
                                com.pikmin.fakegps.utils.MapType.entries.forEach { type ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                text = type.title,
                                                fontWeight = if (type == mapType) androidx.compose.ui.text.font.FontWeight.Bold else androidx.compose.ui.text.font.FontWeight.Normal,
                                                color = if (type == mapType) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                            )
                                        },
                                        onClick = {
                                            viewModel.setMapType(type)
                                            showLayerMenu = false
                                        },
                                        leadingIcon = {
                                            val icon = when (type) {
                                                com.pikmin.fakegps.utils.MapType.GOOGLE_ROADMAP -> Icons.Default.Map
                                                com.pikmin.fakegps.utils.MapType.GOOGLE_SATELLITE -> Icons.Default.Satellite
                                                com.pikmin.fakegps.utils.MapType.GOOGLE_TERRAIN -> Icons.Default.Terrain
                                                com.pikmin.fakegps.utils.MapType.OPEN_STREET_MAP -> Icons.Default.Public
                                            }
                                            Icon(imageVector = icon, contentDescription = null)
                                        }
                                    )
                                }
                            }
                        }

                        // 📋 3. 自動偵測剪貼簿座標開關
                        IconButton(
                            onClick = {
                                val newState = !isAutoClipboardEnabled
                                viewModel.toggleAutoClipboard(newState)
                                val msg = if (newState) "已開啟「切換回 APP 自動套用剪貼簿座標」" else "已關閉「切換回 APP 自動套用剪貼簿座標」"
                                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.size(52.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentPasteGo,
                                contentDescription = "Toggle Auto Clipboard Paste",
                                tint = if (isAutoClipboardEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                modifier = Modifier.size(28.dp)
                            )
                        }

                        // ⭐ 4. 我的書籤收藏
                        IconButton(
                            onClick = { showFavoritesSheet = true },
                            modifier = Modifier.size(52.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Star,
                                contentDescription = "Favorites",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(28.dp)
                            )
                        }

                        // 🕒 5. 定位歷史紀錄 (最新 3 筆)
                        IconButton(
                            onClick = { showHistorySheet = true },
                            modifier = Modifier.size(52.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.History,
                                contentDescription = "Location History",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(28.dp)
                            )
                        }

                        // ✍️ 6. 手動精確輸入經緯度
                        IconButton(
                            onClick = { showCoordinatesDialog = true },
                            modifier = Modifier.size(52.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.PinDrop,
                                contentDescription = "Manual Coordinates",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(28.dp)
                            )
                        }

                        // 🛸 7. 無人機尋菇雷達 (電腦視覺自動巡航)
                        IconButton(
                            onClick = { showDroneDialog = true },
                            modifier = Modifier.size(52.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Radar,
                                contentDescription = "Drone Radar",
                                tint = if (droneStatus.isScanning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }
                }
            }

            // 4. 底部資訊與主控制列 (整合設計，整潔清晰)
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 14.dp, vertical = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 底部座標資訊卡與開始/停止按鈕
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    shadowElevation = 8.dp,
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(9.dp)
                                        .clip(CircleShape)
                                        .background(if (isMocking) AccentGreen else AccentRed)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (isMocking) "模擬中 (Active)" else "待命中 (Idle)",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = if (isMocking) AccentGreen else AccentRed
                                )

                                Spacer(modifier = Modifier.width(8.dp))

                                // 擬真微幅抖動切換 Chip
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = if (enableJitter) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                                    modifier = Modifier.clickable { viewModel.toggleJitter(!enableJitter) }
                                ) {
                                    Text(
                                        text = if (enableJitter) "✨ 抖動:開" else "抖動:關",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (enableJitter) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "緯: ${String.format("%.6f", targetLocation.latitude)}  經: ${String.format("%.6f", targetLocation.longitude)}",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                            )
                        }

                        // 開始 / 停止 按鈕
                        Button(
                            onClick = {
                                if (isMocking) {
                                    viewModel.stopMocking()
                                } else {
                                    viewModel.startMocking()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isMocking) AccentRed else AccentGreen
                            ),
                            shape = RoundedCornerShape(14.dp),
                            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp)
                        ) {
                            Icon(
                                imageVector = if (isMocking) Icons.Default.Stop else Icons.Default.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (isMocking) "停止" else "開始模擬",
                                style = MaterialTheme.typography.titleMedium
                            )
                        }
                    }
                }
            }

            // 手動輸入經緯度對話框
            if (showCoordinatesDialog) {
                InputCoordinatesDialog(
                    initialLat = targetLocation.latitude,
                    initialLng = targetLocation.longitude,
                    onConfirm = { lat, lng ->
                        viewModel.setTargetLocation(lat, lng)
                    },
                    onDismiss = { showCoordinatesDialog = false }
                )
            }

            // 書籤 Bottom Sheet
            if (showFavoritesSheet) {
                FavoritesSheet(
                    bookmarks = bookmarks,
                    onSelectBookmark = { bookmark ->
                        viewModel.setTargetLocation(bookmark.latitude, bookmark.longitude)
                    },
                    onAddBookmark = { name ->
                        viewModel.addBookmark(name)
                    },
                    onDeleteBookmark = { id ->
                        viewModel.removeBookmark(id)
                    },
                    onDismiss = { showFavoritesSheet = false }
                )
            }

            // 定位歷史紀錄 Bottom Sheet (最多 3 筆)
            if (showHistorySheet) {
                HistorySheet(
                    historyList = history,
                    onSelectHistory = { historyPoint ->
                        viewModel.selectHistory(historyPoint)
                    },
                    onClearHistory = { viewModel.clearHistory() },
                    onDismiss = { showHistorySheet = false }
                )
            }

            // 無人機尋菇雷達 Dialog
            if (showDroneDialog) {
                DroneScannerDialog(
                    currentLat = targetLocation.latitude,
                    currentLng = targetLocation.longitude,
                    onStartDroneScan = { radiusKm, targetTypes, dwellSec ->
                        showDroneDialog = false // 立即關閉對話框
                        val mpManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
                        pendingScanParams = Triple(radiusKm, targetTypes, dwellSec)
                        if (!isMocking) {
                            viewModel.startMocking()
                        }
                        try {
                            mediaProjectionLauncher.launch(mpManager.createScreenCaptureIntent())
                        } catch (e: Exception) {
                            // 系統若不支援螢幕截圖，直接以網格巡弋模式運作
                            com.pikmin.fakegps.drone.DroneScannerManager.startScan(
                                context = context,
                                centerLat = targetLocation.latitude,
                                centerLng = targetLocation.longitude,
                                radiusKm = radiusKm,
                                targetTypes = targetTypes,
                                dwellSeconds = dwellSec
                            )
                            Toast.makeText(context, "🛸 無人機已起飛！請切換至《Pikmin Bloom》！", Toast.LENGTH_LONG).show()
                        }
                    },
                    onResumeDroneScan = {
                        showDroneDialog = false
                        com.pikmin.fakegps.drone.DroneScannerManager.resumeScan(context)
                    },
                    onStopDroneScan = {
                        com.pikmin.fakegps.drone.DroneScannerManager.stopScan()
                    },
                    onDismiss = { showDroneDialog = false }
                )
            }

            // 線上更新 Dialog
            if (updateState !is UpdateUiState.Idle) {
                UpdateDialog(
                    updateState = updateState,
                    onDismiss = { AppUpdateManager.resetState() }
                )
            }
        }
    }
}

