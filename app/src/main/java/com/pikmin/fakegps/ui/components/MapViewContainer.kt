package com.pikmin.fakegps.ui.components

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.view.MotionEvent
import android.view.View
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.pikmin.fakegps.R
import com.pikmin.fakegps.data.model.LocationHistoryPoint
import com.pikmin.fakegps.data.model.LocationPoint
import com.pikmin.fakegps.utils.GoogleMapTileSources
import com.pikmin.fakegps.utils.MapType
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker

@SuppressLint("ClickableViewAccessibility")
@Composable
fun MapViewContainer(
    targetLocation: LocationPoint,
    mapType: MapType,
    historyList: List<LocationHistoryPoint> = emptyList(),
    onMapCenterChanged: (lat: Double, lng: Double) -> Unit,
    modifier: Modifier = Modifier,
    liveLocation: LocationPoint? = null,
    isMocking: Boolean = false
) {
    val context = LocalContext.current
    var mapViewInstance by remember { mutableStateOf<MapView?>(null) }
    var isUserInteracting by remember { mutableStateOf(false) }
    var historyMarkers by remember { mutableStateOf<List<Marker>>(emptyList()) }

    // 當外部 targetLocation 改變時（例如手動輸入座標、搜尋地點、點擊書籤、貼上剪貼簿、回到定位），精確移動地圖中心
    LaunchedEffect(targetLocation.latitude, targetLocation.longitude) {
        mapViewInstance?.let { map ->
            if (!isUserInteracting) {
                val currentCenter = map.mapCenter
                if (kotlin.math.abs(currentCenter.latitude - targetLocation.latitude) > 0.00001 ||
                    kotlin.math.abs(currentCenter.longitude - targetLocation.longitude) > 0.00001
                ) {
                    val targetGeoPoint = GeoPoint(targetLocation.latitude, targetLocation.longitude)
                    map.controller.setCenter(targetGeoPoint)
                    map.invalidate()
                }
            }
        }
    }

    // 當歷史紀錄清單變更時，在地圖上繪製經典紅色水滴定位 Pin 標記
    LaunchedEffect(historyList, mapViewInstance) {
        mapViewInstance?.let { map ->
            // 移除舊的歷史標記
            historyMarkers.forEach { map.overlays.remove(it) }

            val markerIcon = createClassicRedPinDrawable(context)

            val newMarkers = historyList.map { historyPoint ->
                Marker(map).apply {
                    position = GeoPoint(historyPoint.latitude, historyPoint.longitude)
                    icon = markerIcon
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    title = if (historyPoint.note.isNotBlank()) historyPoint.note else "歷史定位點"
                    snippet = "緯度: ${String.format("%.6f", historyPoint.latitude)}, 經度: ${String.format("%.6f", historyPoint.longitude)}"
                    setOnMarkerClickListener { clickedMarker, _ ->
                        onMapCenterChanged(clickedMarker.position.latitude, clickedMarker.position.longitude)
                        map.controller.setCenter(clickedMarker.position)
                        clickedMarker.showInfoWindow()
                        true
                    }
                }
            }

            // 新增至地圖圖層
            newMarkers.forEach { map.overlays.add(it) }
            historyMarkers = newMarkers
            map.invalidate()
        }
    }

    // 當使用者切換地圖圖層（例如道路/衛星/地形）時即時更換圖資
    LaunchedEffect(mapType) {
        mapViewInstance?.let { map ->
            val tileSource = when (mapType) {
                MapType.GOOGLE_ROADMAP -> GoogleMapTileSources.GOOGLE_ROADMAP
                MapType.GOOGLE_SATELLITE -> GoogleMapTileSources.GOOGLE_SATELLITE
                MapType.GOOGLE_TERRAIN -> GoogleMapTileSources.GOOGLE_TERRAIN
                MapType.OPEN_STREET_MAP -> TileSourceFactory.MAPNIK
            }
            map.setTileSource(tileSource)
            map.invalidate()
        }
    }

    // 生命週期釋放
    DisposableEffect(Unit) {
        onDispose {
            mapViewInstance?.onPause()
            mapViewInstance?.onDetach()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                MapView(ctx).apply {
                    // 啟用硬體加速與順暢平滑渲染
                    setLayerType(View.LAYER_TYPE_HARDWARE, null)

                    val initialTileSource = when (mapType) {
                        MapType.GOOGLE_ROADMAP -> GoogleMapTileSources.GOOGLE_ROADMAP
                        MapType.GOOGLE_SATELLITE -> GoogleMapTileSources.GOOGLE_SATELLITE
                        MapType.GOOGLE_TERRAIN -> GoogleMapTileSources.GOOGLE_TERRAIN
                        MapType.OPEN_STREET_MAP -> TileSourceFactory.MAPNIK
                    }
                    setTileSource(initialTileSource)
                    isTilesScaledToDpi = true    // 自動高畫質 DPI 縮放
                    isFlingEnabled = true        // 順暢慣性滑動
                    setMultiTouchControls(true) // 支援雙指手勢縮放 (Pinch-to-zoom)
                    zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)

                    minZoomLevel = 3.0
                    maxZoomLevel = 20.0
                    controller.setZoom(16.0)
                    controller.setCenter(GeoPoint(targetLocation.latitude, targetLocation.longitude))

                    // 🌟 核心手勢優化：觸控滑動/雙指縮放期間完全由 GPU 原生高速處理，放開手指後才同步狀態，徹底消除掉幀與卡頓
                    setOnTouchListener { v, event ->
                        when (event.actionMasked) {
                            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                                isUserInteracting = true
                            }
                            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                                isUserInteracting = false
                                val center = mapCenter
                                onMapCenterChanged(center.latitude, center.longitude)
                            }
                        }
                        v.onTouchEvent(event)
                    }

                    onResume()
                    mapViewInstance = this
                }
            },
            update = { _ -> }
        )

        // 地圖正中央準心圖示 (代表當前選取的目標座標)
        Image(
            painter = painterResource(id = R.drawable.ic_crosshair),
            contentDescription = "Map Target Crosshair",
            modifier = Modifier
                .size(40.dp)
                .align(Alignment.Center)
        )
    }
}

/**
 * 動態生成與 Google Maps / Fake GPS 完全一致的經典紅色水滴定位標記 (Red Teardrop Pin)
 */
private fun createClassicRedPinDrawable(context: Context): Drawable {
    val width = 80
    val height = 100
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    val centerX = width / 2f
    val headRadius = 26f
    val headCenterY = 30f
    val bottomY = height - 4f

    // 1. 繪製底部陰影 (提升立體質感)
    val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x33000000.toInt()
        style = Paint.Style.FILL
    }
    canvas.drawOval(RectF(centerX - 14f, bottomY - 4f, centerX + 14f, bottomY + 4f), shadowPaint)

    // 2. 繪製紅色水滴形主體 (Google Red: #EA4335)
    val pinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFEA4335.toInt()
        style = Paint.Style.FILL
    }

    val teardropPath = Path().apply {
        // 頂部圓弧
        arcTo(
            RectF(centerX - headRadius, headCenterY - headRadius, centerX + headRadius, headCenterY + headRadius),
            -180f,
            180f,
            false
        )
        // 右側斜向平滑過渡至底部尖端
        cubicTo(
            centerX + headRadius, headCenterY + 14f,
            centerX + 12f, headCenterY + 42f,
            centerX, bottomY
        )
        // 左側平滑過渡回頂部圓弧
        cubicTo(
            centerX - 12f, headCenterY + 42f,
            centerX - headRadius, headCenterY + 14f,
            centerX - headRadius, headCenterY
        )
        close()
    }
    canvas.drawPath(teardropPath, pinPaint)

    // 3. 繪製中間深紅色圓點 (Dark Red Core: #8B0000)
    val innerDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF8B0000.toInt()
        style = Paint.Style.FILL
    }
    canvas.drawCircle(centerX, headCenterY, 11f, innerDotPaint)

    return BitmapDrawable(context.resources, bitmap)
}
