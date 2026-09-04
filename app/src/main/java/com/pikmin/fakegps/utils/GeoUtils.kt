package com.pikmin.fakegps.utils

import kotlin.math.*
import kotlin.random.Random

object GeoUtils {
    private const val EARTH_RADIUS_METERS = 6371000.0

    /**
     * 依據目前經緯度、速度 (m/s)、方位角 (0-360 度) 與經過時間 (秒)，計算下一個座標點 (採用 Great Circle 航位推算)
     */
    fun calculateNextCoordinate(
        lat: Double,
        lng: Double,
        speedMps: Float,
        bearingDegrees: Float,
        deltaSeconds: Double
    ): Pair<Double, Double> {
        val distance = speedMps * deltaSeconds
        if (distance <= 0) return Pair(lat, lng)

        val angularDistance = distance / EARTH_RADIUS_METERS
        val bearingRad = Math.toRadians(bearingDegrees.toDouble())
        val latRad = Math.toRadians(lat)
        val lngRad = Math.toRadians(lng)

        val nextLatRad = asin(
            sin(latRad) * cos(angularDistance) +
                    cos(latRad) * sin(angularDistance) * cos(bearingRad)
        )

        val nextLngRad = lngRad + atan2(
            sin(bearingRad) * sin(angularDistance) * cos(latRad),
            cos(angularDistance) - sin(latRad) * sin(nextLatRad)
        )

        val nextLat = Math.toDegrees(nextLatRad)
        val nextLng = (Math.toDegrees(nextLngRad) + 540.0) % 360.0 - 180.0

        return Pair(nextLat, nextLng)
    }

    /**
     * 計算兩點之間的球面距離 (公尺) - Haversine 演算法
     */
    fun calculateDistanceMeters(
        lat1: Double, lon1: Double,
        lat2: Double, lon2: Double
    ): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return EARTH_RADIUS_METERS * c
    }

    /**
     * 計算從 (lat1, lon1) 到 (lat2, lon2) 的方位角 (0 - 360度)
     */
    fun calculateBearing(
        lat1: Double, lon1: Double,
        lat2: Double, lon2: Double
    ): Float {
        val lat1Rad = Math.toRadians(lat1)
        val lat2Rad = Math.toRadians(lat2)
        val dLonRad = Math.toRadians(lon2 - lon1)

        val y = sin(dLonRad) * cos(lat2Rad)
        val x = cos(lat1Rad) * sin(lat2Rad) - sin(lat1Rad) * cos(lat2Rad) * cos(dLonRad)
        val bearingRad = atan2(y, x)
        return ((Math.toDegrees(bearingRad) + 360.0) % 360.0).toFloat()
    }

    /**
     * 擬真雜訊：為座標加入極微小的隨機微幅波動 (Jitter，例如 0.3~1.0 公尺)，模擬真實 GPS 天線的微幅飄移
     */
    fun applyRealisticJitter(
        lat: Double,
        lng: Double,
        maxJitterMeters: Double = 0.6
    ): Pair<Double, Double> {
        val jitterRadius = Random.nextDouble(0.0, maxJitterMeters)
        val jitterAngle = Random.nextDouble(0.0, 360.0)
        return calculateNextCoordinate(lat, lng, jitterRadius.toFloat(), jitterAngle.toFloat(), 1.0)
    }

    /**
     * 智慧文字座標辨識（嚴格分行處理）：
     * 避免跨行將其他文字（例如「免4」中的「4」）與下一行座標錯誤混淆拼接。
     *
     * 支援範例：
     * 1. 換行格式：
     *    一般活動菇 免4
     *    52.408297,16.93426666
     * 2. 單行包含備註：
     *    60.4469650, 23.2501560 華麗 免3
     * 3. 關鍵字標籤：
     *    緯度: 25.0339, 經度: 121.5644
     */
    fun extractCoordinatesFromText(text: String): ExtractedCoordinate? {
        if (text.isBlank()) return null

        val lines = text.lines().map { it.trim() }.filter { it.isNotBlank() }

        // 規則 1：在「同一行」中尋找帶有小數點的經緯度數值對（絕不跨行匹配換行符）
        // 數字必須有小數點，例如 52.408297,16.93426666 或 25.0339 121.5644
        val decimalPairRegex = Regex("""([+-]?\d{1,3}\.\d+)\s*[,，\s/|]\s*([+-]?\d{1,3}\.\d+)""")

        for (i in lines.indices) {
            val line = lines[i]
            val match = decimalPairRegex.find(line)
            if (match != null) {
                val val1 = match.groupValues[1].toDoubleOrNull()
                val val2 = match.groupValues[2].toDoubleOrNull()
                if (val1 != null && val2 != null) {
                    var lat: Double
                    var lng: Double

                    if (abs(val1) > 90.0 && abs(val1) <= 180.0 && abs(val2) <= 90.0) {
                        // 第 1 個數值 > 90 (如 121.56)，必為經度
                        lat = val2
                        lng = val1
                    } else if (val1 in -90.0..90.0 && val2 in -180.0..180.0) {
                        // 標準順序 [緯度, 經度]
                        lat = val1
                        lng = val2
                    } else {
                        continue
                    }

                    // 提取說明文字：該行除去座標後的文字 + 其他所有行（如「一般活動菇 免4」）
                    val lineNote = line.replace(match.value, "").trim()
                    val otherLines = lines.filterIndexed { index, _ -> index != i }
                    val allNotes = (otherLines + listOf(lineNote)).filter { it.isNotBlank() }.joinToString(" ")

                    return ExtractedCoordinate(lat, lng, allNotes)
                }
            }
        }

        // 規則 2：檢查明確的關鍵字標籤（例如 緯度: 25.0339, 經度: 121.5644）
        val latKeyRegex = Regex("""(?i)(?:緯度|纬度|lat(?:itude)?)\s*[:：=]?\s*([+-]?\d{1,3}(?:\.\d+)?)""")
        val lngKeyRegex = Regex("""(?i)(?:經度|经度|long(?:itude)?|lon|lng)\s*[:：=]?\s*([+-]?\d{1,3}(?:\.\d+)?)""")
        val latMatch = latKeyRegex.find(text)
        val lngMatch = lngKeyRegex.find(text)
        if (latMatch != null && lngMatch != null) {
            val lat = latMatch.groupValues[1].toDoubleOrNull()
            val lng = lngMatch.groupValues[1].toDoubleOrNull()
            if (lat != null && lng != null && lat in -90.0..90.0 && lng in -180.0..180.0) {
                val note = text
                    .replace(latMatch.value, "")
                    .replace(lngMatch.value, "")
                    .replace(Regex("""[,，;；\r\n\t]+"""), " ")
                    .trim()
                return ExtractedCoordinate(lat, lng, note)
            }
        }

        // 規則 3：同一行內以逗號明確分隔的整數/浮點數對（必須在同一行內且以逗號連接）
        val commaPairRegex = Regex("""([+-]?\d{1,3}(?:\.\d+)?)\s*[,，]\s*([+-]?\d{1,3}(?:\.\d+)?)""")
        for (i in lines.indices) {
            val line = lines[i]
            val match = commaPairRegex.find(line)
            if (match != null) {
                val val1 = match.groupValues[1].toDoubleOrNull()
                val val2 = match.groupValues[2].toDoubleOrNull()
                if (val1 != null && val2 != null && val1 in -90.0..90.0 && val2 in -180.0..180.0) {
                    val lineNote = line.replace(match.value, "").trim()
                    val otherLines = lines.filterIndexed { index, _ -> index != i }
                    val allNotes = (otherLines + listOf(lineNote)).filter { it.isNotBlank() }.joinToString(" ")
                    return ExtractedCoordinate(val1, val2, allNotes)
                }
            }
        }

        return null
    }
}

data class ExtractedCoordinate(
    val latitude: Double,
    val longitude: Double,
    val note: String = ""
)
