package com.pikmin.fakegps.drone

import com.pikmin.fakegps.data.model.LocationPoint
import kotlin.math.*

/**
 * 無人機網格/螺旋巡弋航點生成器
 */
object DronePathGenerator {
    const val MIN_RADIUS_KM = 0.1
    const val MAX_RADIUS_KM = 20.0

    /**
     * 以中心點為基準，生成向外擴散的螺旋巡弋座標序列
     *
     * @param centerLat 中心緯度
     * @param centerLng 中心經度
     * @param radiusKm 搜索半徑 (公里)
     * @param stepMeters 步進距離 (公尺，預設 360m 黃金航距，兼顧遊戲 450m 視界無死角與極速覆蓋)
     */
    fun generateSpiralWaypoints(
        centerLat: Double,
        centerLng: Double,
        radiusKm: Double,
        stepMeters: Double = 360.0
    ): List<LocationPoint> {
        require(centerLat.isFinite() && centerLat in -85.0..85.0) { "巡航緯度需介於 -85 與 85 度" }
        require(centerLng.isFinite() && centerLng in -180.0..180.0) { "經度超出範圍" }
        require(radiusKm.isFinite() && radiusKm in MIN_RADIUS_KM..MAX_RADIUS_KM) {
            "半徑需介於 $MIN_RADIUS_KM 與 $MAX_RADIUS_KM 公里"
        }
        require(stepMeters.isFinite() && stepMeters in 100.0..1000.0) { "航點間距需介於 100 與 1000 公尺" }
        val waypoints = mutableListOf<LocationPoint>()

        // 1. 第 0 點為中心起點
        waypoints.add(LocationPoint(latitude = centerLat, longitude = centerLng))

        val maxRadiusMeters = radiusKm * 1000.0

        // 阿基米德螺旋參數
        // r = a + b * theta
        var currentRadius = stepMeters
        val b = stepMeters / (2 * Math.PI) // 每轉一圈半徑增加 stepMeters

        var theta = currentRadius / b

        while (currentRadius <= maxRadiusMeters) {
            // 計算目前角度與半徑下的相對位移 (公尺)

            val (pointLat, pointLng) = com.pikmin.fakegps.utils.GeoUtils.calculateNextCoordinate(
                centerLat, centerLng, currentRadius.toFloat(), Math.toDegrees(theta).toFloat(), 1.0
            )

            waypoints.add(
                LocationPoint(
                    latitude = pointLat,
                    longitude = pointLng
                )
            )

            // 推進下一弧長點 (弧長 ds ≈ r * dtheta = stepMeters)
            val dTheta = stepMeters / currentRadius
            theta += dTheta
            currentRadius = b * theta
        }

        return waypoints
    }
}
