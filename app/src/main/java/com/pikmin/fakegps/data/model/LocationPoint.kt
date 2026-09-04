package com.pikmin.fakegps.data.model

import java.io.Serializable

/**
 * 座標資料模型
 */
data class LocationPoint(
    val latitude: Double,
    val longitude: Double,
    val altitude: Double = 25.0,
    val accuracy: Float = 3.0f,
    val bearing: Float = 0.0f,
    val speed: Float = 0.0f, // 單位: 公尺/秒 (m/s)
    val timestamp: Long = System.currentTimeMillis()
) : Serializable

/**
 * 常用地點/書籤資料模型
 */
data class BookmarkPoint(
    val id: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val createdAt: Long = System.currentTimeMillis()
) : Serializable

/**
 * 歷史定位紀錄資料模型（最多保留最新 3 筆）
 */
data class LocationHistoryPoint(
    val id: String,
    val latitude: Double,
    val longitude: Double,
    val note: String = "",
    val timestamp: Long = System.currentTimeMillis()
) : Serializable
