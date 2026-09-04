package com.pikmin.fakegps.ui.viewmodel

import android.app.Application
import android.content.Context
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pikmin.fakegps.data.model.BookmarkPoint
import com.pikmin.fakegps.data.model.LocationHistoryPoint
import com.pikmin.fakegps.data.model.LocationPoint
import com.pikmin.fakegps.data.model.MovementMode
import com.pikmin.fakegps.data.repository.PreferencesRepo
import com.pikmin.fakegps.service.MockLocationService
import com.pikmin.fakegps.utils.GeoUtils
import com.pikmin.fakegps.utils.PermissionHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.net.URLEncoder
import java.util.UUID

data class SearchResult(
    val displayName: String,
    val latitude: Double,
    val longitude: Double
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val context: Context get() = getApplication<Application>().applicationContext
    private val repo = PreferencesRepo(context)
    private val httpClient = OkHttpClient()

    // 介面選取的目前座標點
    private val _targetLocation = MutableStateFlow(
        LocationPoint(latitude = repo.lastLatitude, longitude = repo.lastLongitude)
    )
    val targetLocation: StateFlow<LocationPoint> = _targetLocation.asStateFlow()

    // 地圖縮放層級
    val mapZoom = MutableStateFlow(repo.lastZoom)

    // 服務執行狀態
    val isMocking = MockLocationService.isRunning
    val liveMockLocation = MockLocationService.currentLocation

    // 偏好設定與書籤
    private val _bookmarks = MutableStateFlow<List<BookmarkPoint>>(emptyList())
    val bookmarks: StateFlow<List<BookmarkPoint>> = _bookmarks.asStateFlow()

    // 歷史定位紀錄（最多 3 筆）
    private val _history = MutableStateFlow<List<LocationHistoryPoint>>(emptyList())
    val history: StateFlow<List<LocationHistoryPoint>> = _history.asStateFlow()

    val movementMode = MutableStateFlow(repo.movementMode)
    val enableJitter = MutableStateFlow(repo.enableJitter)
    val isAutoClipboardEnabled = MutableStateFlow(repo.isAutoClipboardEnabled)
    val mapType = MutableStateFlow(repo.mapType)

    // 搜尋相關
    private val _searchResults = MutableStateFlow<List<SearchResult>>(emptyList())
    val searchResults: StateFlow<List<SearchResult>> = _searchResults.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    init {
        loadBookmarks()
        loadHistory()
    }

    fun setMapType(type: com.pikmin.fakegps.utils.MapType) {
        mapType.value = type
        repo.mapType = type
    }

    fun toggleAutoClipboard(enabled: Boolean) {
        isAutoClipboardEnabled.value = enabled
        repo.isAutoClipboardEnabled = enabled
    }

    private fun loadHistory() {
        _history.value = repo.getHistory()
    }

    fun recordHistory(lat: Double, lng: Double, note: String = "") {
        _history.value = repo.addHistory(lat, lng, note)
    }

    fun selectHistory(item: LocationHistoryPoint) {
        setTargetLocation(item.latitude, item.longitude)
        recordHistory(item.latitude, item.longitude, item.note)
        val noteText = if (item.note.isNotBlank()) " (${item.note})" else ""
        Toast.makeText(
            context,
            "📍 已切換至歷史定位點：${String.format("%.4f, %.4f", item.latitude, item.longitude)}$noteText",
            Toast.LENGTH_SHORT
        ).show()
    }

    fun clearHistory() {
        repo.clearHistory()
        _history.value = emptyList()
    }

    /**
     * 自動偵測並套用剪貼簿內的座標文字
     * 當使用者從其他 App 複製經緯度切換回此 App 時自動觸發
     */
    fun checkAndApplyClipboard(text: String): Boolean {
        if (!isAutoClipboardEnabled.value) return false
        val trimmed = text.trim()
        if (trimmed.isBlank() || trimmed == repo.lastProcessedClipboard) return false

        val extracted = com.pikmin.fakegps.utils.GeoUtils.extractCoordinatesFromText(trimmed)
        if (extracted != null) {
            repo.lastProcessedClipboard = trimmed
            setTargetLocation(extracted.latitude, extracted.longitude)
            recordHistory(extracted.latitude, extracted.longitude, extracted.note)

            // 若尚未啟動模擬，自動啟動
            if (!isMocking.value) {
                startMocking()
            }

            val noteInfo = if (extracted.note.isNotBlank()) " (${extracted.note})" else ""
            Toast.makeText(
                context,
                "📍 已自動偵測剪貼簿座標：${String.format("%.5f, %.5f", extracted.latitude, extracted.longitude)}$noteInfo，已傳送並開始模擬！",
                Toast.LENGTH_LONG
            ).show()
            return true
        }
        return false
    }

    private fun loadBookmarks() {
        _bookmarks.value = repo.getBookmarks()
    }

    fun setTargetLocation(latitude: Double, longitude: Double) {
        _targetLocation.value = _targetLocation.value.copy(
            latitude = latitude,
            longitude = longitude
        )
        repo.lastLatitude = latitude
        repo.lastLongitude = longitude

        // 若正在模擬中，即時更新至新位置 (傳送/Teleport)
        if (isMocking.value) {
            MockLocationService.updateLocation(context, _targetLocation.value)
        }
    }

    fun setMovementMode(mode: MovementMode) {
        movementMode.value = mode
        repo.movementMode = mode
    }

    /**
     * 定位回到使用者目前真實 GPS 所在位置
     */
    fun moveToCurrentLocation() {
        if (!PermissionHelper.hasLocationPermission(context)) {
            Toast.makeText(context, "請先授予定位權限", Toast.LENGTH_SHORT).show()
            return
        }

        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? android.location.LocationManager
        try {
            val gpsLocation = locationManager?.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER)
            val netLocation = locationManager?.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER)
            val bestLocation = gpsLocation ?: netLocation

            if (bestLocation != null) {
                setTargetLocation(bestLocation.latitude, bestLocation.longitude)
                Toast.makeText(
                    context,
                    "📍 已定位至目前位置：${String.format("%.4f, %.4f", bestLocation.latitude, bestLocation.longitude)}",
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                Toast.makeText(context, "正在搜尋 GPS 定位訊號...", Toast.LENGTH_SHORT).show()
            }
        } catch (e: SecurityException) {
            Toast.makeText(context, "定位權限不足", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun toggleJitter(enabled: Boolean) {
        enableJitter.value = enabled
        repo.enableJitter = enabled
        MockLocationService.engineInstance?.enableJitter = enabled
    }

    fun startMocking() {
        if (!PermissionHelper.isMockLocationApp(context)) {
            Toast.makeText(
                context,
                "尚未在「開發人員選項」中將 Fake GPS 設為模擬位置應用程式",
                Toast.LENGTH_LONG
            ).show()
            PermissionHelper.openDevelopmentSettings(context)
            return
        }

        recordHistory(_targetLocation.value.latitude, _targetLocation.value.longitude)
        MockLocationService.start(context, _targetLocation.value)
    }

    fun stopMocking() {
        MockLocationService.stop(context)
    }

    fun addBookmark(name: String) {
        val currentPoint = _targetLocation.value
        val bookmark = BookmarkPoint(
            id = UUID.randomUUID().toString(),
            name = name.ifBlank { "自訂座標 (${String.format("%.4f, %.4f", currentPoint.latitude, currentPoint.longitude)})" },
            latitude = currentPoint.latitude,
            longitude = currentPoint.longitude
        )
        repo.addBookmark(bookmark)
        loadBookmarks()
    }

    fun removeBookmark(id: String) {
        repo.removeBookmark(id)
        loadBookmarks()
    }

    fun searchLocation(query: String) {
        if (query.isBlank()) {
            _searchResults.value = emptyList()
            return
        }

        // 智慧辨識：檢查輸入文字是否包含經緯度（即使混雜文字或備註，如「60.4469650, 23.2501560 華麗 免3」）
        val extractedCoord = GeoUtils.extractCoordinatesFromText(query)
        if (extractedCoord != null) {
            val title = if (extractedCoord.note.isNotBlank()) {
                "📍 辨識座標: ${extractedCoord.note}"
            } else {
                "📍 經緯度座標"
            }
            _searchResults.value = listOf(
                SearchResult(
                    displayName = "$title (${extractedCoord.latitude}, ${extractedCoord.longitude})",
                    latitude = extractedCoord.latitude,
                    longitude = extractedCoord.longitude
                )
            )
            return
        }

        viewModelScope.launch {
            _isSearching.value = true
            try {
                val results = withContext(Dispatchers.IO) {
                    val encoded = URLEncoder.encode(query, "UTF-8")
                    val url = "https://nominatim.openstreetmap.org/search?q=$encoded&format=json&limit=6&addressdetails=1"
                    val request = Request.Builder()
                        .url(url)
                        .header("User-Agent", "FakeGPS-App-Android")
                        .build()

                    val response = httpClient.newCall(request).execute()
                    val responseBody = response.body?.string() ?: "[]"
                    val jsonArray = JSONArray(responseBody)
                    val list = mutableListOf<SearchResult>()
                    for (i in 0 until jsonArray.length()) {
                        val obj = jsonArray.getJSONObject(i)
                        list.add(
                            SearchResult(
                                displayName = obj.getString("display_name"),
                                latitude = obj.getDouble("lat"),
                                longitude = obj.getDouble("lon")
                            )
                        )
                    }
                    list
                }
                _searchResults.value = results
            } catch (e: Exception) {
                e.printStackTrace()
                _searchResults.value = emptyList()
            } finally {
                _isSearching.value = false
            }
        }
    }

    fun clearSearchResults() {
        _searchResults.value = emptyList()
    }
}
