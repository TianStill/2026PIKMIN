package com.pikmin.fakegps.data.model

/**
 * 移動模式與對應速度
 */
enum class MovementMode(
    val title: String,
    val speedKmh: Double,
    val speedMps: Float
) {
    WALK("步行", 4.0, (4.0 / 3.6).toFloat()),
    RUN("跑步", 10.0, (10.0 / 3.6).toFloat()),
    BIKE("騎車", 25.0, (25.0 / 3.6).toFloat()),
    DRIVE("開車", 50.0, (50.0 / 3.6).toFloat());

    companion object {
        fun fromSpeedKmh(speed: Double): MovementMode {
            return entries.minByOrNull { kotlin.math.abs(it.speedKmh - speed) } ?: WALK
        }
    }
}
