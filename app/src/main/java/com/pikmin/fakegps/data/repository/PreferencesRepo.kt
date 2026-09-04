package com.pikmin.fakegps.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.pikmin.fakegps.data.model.BookmarkPoint
import com.pikmin.fakegps.data.model.LocationHistoryPoint
import com.pikmin.fakegps.data.model.MovementMode
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class PreferencesRepo(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("fake_gps_preferences", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_LAST_LAT = "key_last_lat"
        private const val KEY_LAST_LNG = "key_last_lng"
        private const val KEY_LAST_ZOOM = "key_last_zoom"
        private const val KEY_SPEED_MODE = "key_speed_mode"
        private const val KEY_ENABLE_JITTER = "key_enable_jitter"
        private const val KEY_AUTO_CLIPBOARD = "key_auto_clipboard"
        private const val KEY_LAST_CLIPBOARD = "key_last_clipboard"
        private const val KEY_MAP_TYPE = "key_map_type"
        private const val KEY_BOOKMARKS = "key_bookmarks_json"
        private const val KEY_HISTORY = "key_location_history_json"

        // 預設台北 101 座標
        const val DEFAULT_LAT = 25.033964
        const val DEFAULT_LNG = 121.564468
        const val DEFAULT_ZOOM = 16.0
    }

    var lastLatitude: Double
        get() = prefs.getString(KEY_LAST_LAT, DEFAULT_LAT.toString())?.toDoubleOrNull() ?: DEFAULT_LAT
        set(value) = prefs.edit().putString(KEY_LAST_LAT, value.toString()).apply()

    var lastLongitude: Double
        get() = prefs.getString(KEY_LAST_LNG, DEFAULT_LNG.toString())?.toDoubleOrNull() ?: DEFAULT_LNG
        set(value) = prefs.edit().putString(KEY_LAST_LNG, value.toString()).apply()

    var lastZoom: Double
        get() = prefs.getFloat(KEY_LAST_ZOOM, DEFAULT_ZOOM.toFloat()).toDouble()
        set(value) = prefs.edit().putFloat(KEY_LAST_ZOOM, value.toFloat()).apply()

    var movementMode: MovementMode
        get() {
            val name = prefs.getString(KEY_SPEED_MODE, MovementMode.WALK.name)
            return try {
                MovementMode.valueOf(name ?: MovementMode.WALK.name)
            } catch (e: Exception) {
                MovementMode.WALK
            }
        }
        set(value) = prefs.edit().putString(KEY_SPEED_MODE, value.name).apply()

    var mapType: com.pikmin.fakegps.utils.MapType
        get() {
            val name = prefs.getString(KEY_MAP_TYPE, com.pikmin.fakegps.utils.MapType.GOOGLE_ROADMAP.name)
            return try {
                com.pikmin.fakegps.utils.MapType.valueOf(name ?: com.pikmin.fakegps.utils.MapType.GOOGLE_ROADMAP.name)
            } catch (e: Exception) {
                com.pikmin.fakegps.utils.MapType.GOOGLE_ROADMAP
            }
        }
        set(value) = prefs.edit().putString(KEY_MAP_TYPE, value.name).apply()

    var enableJitter: Boolean
        get() = prefs.getBoolean(KEY_ENABLE_JITTER, true)
        set(value) = prefs.edit().putBoolean(KEY_ENABLE_JITTER, value).apply()

    var isAutoClipboardEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_CLIPBOARD, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_CLIPBOARD, value).apply()

    var lastProcessedClipboard: String
        get() = prefs.getString(KEY_LAST_CLIPBOARD, "") ?: ""
        set(value) = prefs.edit().putString(KEY_LAST_CLIPBOARD, value).apply()

    var overlayX: Int
        get() = prefs.getInt("key_overlay_x", 80)
        set(value) = prefs.edit().putInt("key_overlay_x", value).apply()

    var overlayY: Int
        get() = prefs.getInt("key_overlay_y", 300)
        set(value) = prefs.edit().putInt("key_overlay_y", value).apply()

    // ===== 書籤 (Bookmarks) =====
    fun getBookmarks(): List<BookmarkPoint> {
        val jsonStr = prefs.getString(KEY_BOOKMARKS, null) ?: return emptyList()
        val list = mutableListOf<BookmarkPoint>()
        try {
            val jsonArray = JSONArray(jsonStr)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                list.add(
                    BookmarkPoint(
                        id = obj.getString("id"),
                        name = obj.getString("name"),
                        latitude = obj.getDouble("latitude"),
                        longitude = obj.getDouble("longitude"),
                        createdAt = obj.optLong("createdAt", System.currentTimeMillis())
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    fun addBookmark(bookmark: BookmarkPoint) {
        val current = getBookmarks().toMutableList()
        current.removeAll { it.id == bookmark.id }
        current.add(0, bookmark)
        saveBookmarks(current)
    }

    fun removeBookmark(id: String) {
        val current = getBookmarks().toMutableList()
        current.removeAll { it.id == id }
        saveBookmarks(current)
    }

    private fun saveBookmarks(list: List<BookmarkPoint>) {
        val jsonArray = JSONArray()
        for (item in list) {
            val obj = JSONObject().apply {
                put("id", item.id)
                put("name", item.name)
                put("latitude", item.latitude)
                put("longitude", item.longitude)
                put("createdAt", item.createdAt)
            }
            jsonArray.put(obj)
        }
        prefs.edit().putString(KEY_BOOKMARKS, jsonArray.toString()).apply()
    }

    // ===== 歷史紀錄 (Location History，最多記錄 3 筆) =====
    fun getHistory(): List<LocationHistoryPoint> {
        val jsonStr = prefs.getString(KEY_HISTORY, null) ?: return emptyList()
        val list = mutableListOf<LocationHistoryPoint>()
        try {
            val jsonArray = JSONArray(jsonStr)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                list.add(
                    LocationHistoryPoint(
                        id = obj.optString("id", UUID.randomUUID().toString()),
                        latitude = obj.getDouble("latitude"),
                        longitude = obj.getDouble("longitude"),
                        note = obj.optString("note", ""),
                        timestamp = obj.optLong("timestamp", System.currentTimeMillis())
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    fun addHistory(latitude: Double, longitude: Double, note: String = ""): List<LocationHistoryPoint> {
        val current = getHistory().toMutableList()

        // 去除重複點（若與先前紀錄座標極為接近，視為同一定位點進行置頂更新）
        current.removeAll {
            kotlin.math.abs(it.latitude - latitude) < 0.0001 &&
                    kotlin.math.abs(it.longitude - longitude) < 0.0001
        }

        val newPoint = LocationHistoryPoint(
            id = UUID.randomUUID().toString(),
            latitude = latitude,
            longitude = longitude,
            note = note,
            timestamp = System.currentTimeMillis()
        )
        current.add(0, newPoint)

        // 最多保留最新 3 筆
        val trimmed = current.take(3)
        saveHistory(trimmed)
        return trimmed
    }

    fun clearHistory() {
        prefs.edit().remove(KEY_HISTORY).apply()
    }

    private fun saveHistory(list: List<LocationHistoryPoint>) {
        val jsonArray = JSONArray()
        for (item in list) {
            val obj = JSONObject().apply {
                put("id", item.id)
                put("latitude", item.latitude)
                put("longitude", item.longitude)
                put("note", item.note)
                put("timestamp", item.timestamp)
            }
            jsonArray.put(obj)
        }
        prefs.edit().putString(KEY_HISTORY, jsonArray.toString()).apply()
    }
}
