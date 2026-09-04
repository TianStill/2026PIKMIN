package com.pikmin.fakegps.utils

import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.MapTileIndex

enum class MapType(val title: String) {
    GOOGLE_ROADMAP("Google 道路地圖 (高清)"),
    GOOGLE_SATELLITE("Google 衛星空照圖"),
    GOOGLE_TERRAIN("Google 地形圖"),
    OPEN_STREET_MAP("OpenStreetMap 標準圖")
}

object GoogleMapTileSources {

    // Google 繁體中文道路地圖 (超清晰、台灣高速 Google CDN 節點、秒讀取)
    val GOOGLE_ROADMAP = object : OnlineTileSourceBase(
        "Google-Roadmap-TW",
        0, 20, 256, ".png",
        arrayOf(
            "https://mt0.google.com/vt/lyrs=m&hl=zh-TW&",
            "https://mt1.google.com/vt/lyrs=m&hl=zh-TW&",
            "https://mt2.google.com/vt/lyrs=m&hl=zh-TW&",
            "https://mt3.google.com/vt/lyrs=m&hl=zh-TW&"
        )
    ) {
        override fun getTileURLString(pMapTileIndex: Long): String {
            val zoom = MapTileIndex.getZoom(pMapTileIndex)
            val x = MapTileIndex.getX(pMapTileIndex)
            val y = MapTileIndex.getY(pMapTileIndex)
            return "${baseUrl}x=$x&y=$y&z=$zoom"
        }
    }

    // Google 混合衛星空照圖 (包含清晰道路與繁體中文地名標籤)
    val GOOGLE_SATELLITE = object : OnlineTileSourceBase(
        "Google-Satellite-TW",
        0, 20, 256, ".jpg",
        arrayOf(
            "https://mt0.google.com/vt/lyrs=y&hl=zh-TW&",
            "https://mt1.google.com/vt/lyrs=y&hl=zh-TW&",
            "https://mt2.google.com/vt/lyrs=y&hl=zh-TW&",
            "https://mt3.google.com/vt/lyrs=y&hl=zh-TW&"
        )
    ) {
        override fun getTileURLString(pMapTileIndex: Long): String {
            val zoom = MapTileIndex.getZoom(pMapTileIndex)
            val x = MapTileIndex.getX(pMapTileIndex)
            val y = MapTileIndex.getY(pMapTileIndex)
            return "${baseUrl}x=$x&y=$y&z=$zoom"
        }
    }

    // Google 地形等高線圖
    val GOOGLE_TERRAIN = object : OnlineTileSourceBase(
        "Google-Terrain-TW",
        0, 20, 256, ".png",
        arrayOf(
            "https://mt0.google.com/vt/lyrs=p&hl=zh-TW&",
            "https://mt1.google.com/vt/lyrs=p&hl=zh-TW&",
            "https://mt2.google.com/vt/lyrs=p&hl=zh-TW&",
            "https://mt3.google.com/vt/lyrs=p&hl=zh-TW&"
        )
    ) {
        override fun getTileURLString(pMapTileIndex: Long): String {
            val zoom = MapTileIndex.getZoom(pMapTileIndex)
            val x = MapTileIndex.getX(pMapTileIndex)
            val y = MapTileIndex.getY(pMapTileIndex)
            return "${baseUrl}x=$x&y=$y&z=$zoom"
        }
    }
}
