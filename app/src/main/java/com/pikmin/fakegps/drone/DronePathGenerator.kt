package com.pikmin.fakegps.drone

import com.pikmin.fakegps.data.model.LocationPoint
import kotlin.math.*

/**
 * 無人機網格/螺旋巡弋航點生成器
 */
object DronePathGenerator {

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
        val waypoints = mutableListOf<LocationPoint>()

        // 1. 第 0 點為中心起點
        waypoints.add(LocationPoint(latitude = centerLat, longitude = centerLng))

        val maxRadiusMeters = radiusKm * 1000.0
        val metersPerLat = 111132.954 // 每緯度約公尺數
        val metersPerLng = 111132.954 * cos(Math.toRadians(centerLat))

        // 阿基米德螺旋參數
        // r = a + b * theta
        var currentRadius = stepMeters
        val b = stepMeters / (2 * Math.PI) // 每轉一圈半徑增加 stepMeters

        var theta = currentRadius / b

        while (currentRadius <= maxRadiusMeters) {
            // 計算目前角度與半徑下的相對位移 (公尺)
            val dx = currentRadius * sin(theta) // 東西向 (經度)
            val dy = currentRadius * cos(theta) // 南北向 (緯度)

            val pointLat = centerLat + (dy / metersPerLat)
            val pointLng = centerLng + (dx / metersPerLng)

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
