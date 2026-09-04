package com.pikmin.fakegps.cv

import android.graphics.*
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 蘑菇分類大項
 */
enum class MushroomCategory(val title: String) {
    GIANT_EVENT("巨大活動特殊菇"),
    LARGE_COLOR("大顏色菇"),
    LARGE_ELEMENT("大元素菇")
}

/**
 * 蘑菇目標種類枚舉 (僅鎖定：巨大活動特殊菇、大顏色菇、大元素菇)
 */
enum class MushroomType(
    val title: String,
    val shortName: String,
    val category: MushroomCategory,
    val colorHex: Long
) {
    // 👑 1. 巨大活動特殊菇
    GIANT_EVENT("巨大活動特殊菇", "巨大活動菇", MushroomCategory.GIANT_EVENT, 0xFFF59E0B),

    // 🌈 2. 大顏色菇 (每一種顏色)
    LARGE_RED("大型紅蘑菇", "大紅菇", MushroomCategory.LARGE_COLOR, 0xFFEF4444),
    LARGE_YELLOW("大型黃蘑菇", "大黃菇", MushroomCategory.LARGE_COLOR, 0xFFEAB308),
    LARGE_BLUE("大型藍蘑菇", "大藍菇", MushroomCategory.LARGE_COLOR, 0xFF3B82F6),
    LARGE_PURPLE("大型紫蘑菇", "大紫菇", MushroomCategory.LARGE_COLOR, 0xFF9333EA),
    LARGE_WHITE("大型白蘑菇", "大白菇", MushroomCategory.LARGE_COLOR, 0xFFF1F5F9),
    LARGE_PINK("大型粉羽蘑菇", "大粉菇", MushroomCategory.LARGE_COLOR, 0xFFEC4899),
    LARGE_GRAY("大型灰岩蘑菇", "大灰菇", MushroomCategory.LARGE_COLOR, 0xFF64748B),

    // ⚡ 3. 大元素菇 (每一種元素)
    LARGE_FIRE("大型火蘑菇 🔥", "大火菇", MushroomCategory.LARGE_ELEMENT, 0xFFFF4500),
    LARGE_WATER("大型水蘑菇 💧", "大水菇", MushroomCategory.LARGE_ELEMENT, 0xFF06B6D4),
    LARGE_ELECTRIC("大型電蘑菇 ⚡", "大電菇", MushroomCategory.LARGE_ELEMENT, 0xFFFACC15),
    LARGE_CRYSTAL("大型水晶蘑菇 💎", "水晶菇", MushroomCategory.LARGE_ELEMENT, 0xFFA5F3FC),
    LARGE_POISON("大型毒蘑菇 🧪", "大毒菇", MushroomCategory.LARGE_ELEMENT, 0xFFA855F7);

    companion object {
        val ALL_TARGETS = entries.toSet()
        val EVENT_TARGETS = setOf(GIANT_EVENT)
        val COLOR_TARGETS = entries.filter { it.category == MushroomCategory.LARGE_COLOR }.toSet()
        val ELEMENT_TARGETS = entries.filter { it.category == MushroomCategory.LARGE_ELEMENT }.toSet()
    }
}

/**
 * 偵測到的蘑菇目標
 */
data class DetectedMushroom(
    val type: MushroomType,
    val x: Int,
    val y: Int,
    val radius: Int,
    val confidence: Float,
    val isGiant: Boolean = false
)

/**
 * Pikmin Bloom 地圖蘑菇電腦視覺 (CV) 檢測核心 (高靈敏度連通群集 + 色票直方圖投票架構)
 */
object MushroomDetector {

    /**
     * 分析遊戲畫面 Bitmap，支援多距離動態尺度與色票聚合投票
     */
    fun detectMushrooms(
        sourceBitmap: Bitmap,
        targetTypes: Set<MushroomType> = MushroomType.ALL_TARGETS
    ): List<DetectedMushroom> {
        val width = sourceBitmap.width
        val height = sourceBitmap.height

        // 將長邊採樣至 640px，兼顧高解析度微小蘑菇與 30ms 極速運算
        val maxDim = 640
        val scale = if (max(width, height) > maxDim) {
            maxDim.toFloat() / max(width, height)
        } else {
            1.0f
        }

        val scaledW = (width * scale).toInt()
        val scaledH = (height * scale).toInt()
        val scaledBitmap = if (scale < 1.0f) {
            Bitmap.createScaledBitmap(sourceBitmap, scaledW, scaledH, true)
        } else {
            sourceBitmap
        }

        val pixels = IntArray(scaledW * scaledH)
        scaledBitmap.getPixels(pixels, 0, scaledW, 0, 0, scaledW, scaledH)

        // 僅分析螢幕中段 (排除頂部 12% 狀態列與底部 14% 按鈕導航)
        val startY = (scaledH * 0.12f).toInt()
        val endY = (scaledH * 0.86f).toInt()

        val hsv = FloatArray(3)
        // 標記矩陣：0=非目標背景，>0=MushroomType.ordinal + 1，255=實體亮白反光
        val typeMask = IntArray(scaledW * scaledH)

        // 排除玩家角色正中心足底區域 (預防自身皮克敏隊伍衣服誤判)
        val avatarMinX = (scaledW * 0.40f).toInt()
        val avatarMaxX = (scaledW * 0.60f).toInt()
        val avatarMinY = (scaledH * 0.52f).toInt()
        val avatarMaxY = (scaledH * 0.68f).toInt()

        for (y in startY until endY) {
            val rowOffset = y * scaledW
            for (x in 0 until scaledW) {
                // 排除正中心玩家本體
                if (x in avatarMinX..avatarMaxX && y in avatarMinY..avatarMaxY) {
                    continue
                }

                val pixel = pixels[rowOffset + x]
                Color.colorToHSV(pixel, hsv)

                // 排除草地綠色地形 (Hue 70..160)
                if (hsv[0] in 70f..160f && hsv[1] >= 0.18f) {
                    continue
                }

                val type = classifyHsvToTarget(hsv)
                if (type != null) {
                    typeMask[rowOffset + x] = type.ordinal + 1
                }
            }
        }

        // 區塊聚類 (連通分量聚合提取整顆蘑菇實體)
        val detected = mutableListOf<DetectedMushroom>()
        val visited = BooleanArray(scaledW * scaledH)

        // 🌟 最佳化靈敏度門檻：放寬至 18 像素 (支援 80m~350m 遠距蘑菇)，巨大菇 >= 80 像素
        val minClusterSize = 18
        val minGiantClusterSize = 85
        val maxClusterSize = 25000

        val queue = IntArray(maxClusterSize * 2)

        for (y in startY until endY) {
            val rowOffset = y * scaledW
            for (x in 0 until scaledW) {
                val idx = rowOffset + x
                val rawTypeVal = typeMask[idx]
                if (rawTypeVal > 0 && !visited[idx]) {
                    var head = 0
                    var tail = 0
                    queue[tail++] = idx
                    visited[idx] = true

                    var sumX = 0L
                    var sumY = 0L
                    var count = 0
                    var minX = x
                    var maxX = x
                    var minY = y
                    var maxY = y

                    // 記錄該聚類內部各色票投票數
                    val typeHistogram = IntArray(MushroomType.entries.size)

                    while (head < tail && tail < queue.size - 8) {
                        val curr = queue[head++]
                        val cx = curr % scaledW
                        val cy = curr / scaledW
                        sumX += cx
                        sumY += cy
                        count++

                        val tVal = typeMask[curr]
                        if (tVal in 1..MushroomType.entries.size) {
                            typeHistogram[tVal - 1]++
                        }

                        if (cx < minX) minX = cx
                        if (cx > maxX) maxX = cx
                        if (cy < minY) minY = cy
                        if (cy > maxY) maxY = cy

                        // 8 鄰域泛洪擴散 (防止斑點與光影斷裂)
                        val n1 = curr - 1
                        val n2 = curr + 1
                        val n3 = curr - scaledW
                        val n4 = curr + scaledW
                        val n5 = curr - scaledW - 1
                        val n6 = curr - scaledW + 1
                        val n7 = curr + scaledW - 1
                        val n8 = curr + scaledW + 1

                        val neighbors = intArrayOf(n1, n2, n3, n4, n5, n6, n7, n8)
                        for (n in neighbors) {
                            if (n in 0 until (scaledW * scaledH)) {
                                if (!visited[n] && typeMask[n] > 0) {
                                    visited[n] = true
                                    queue[tail++] = n
                                }
                            }
                        }
                    }

                    val bboxW = maxX - minX + 1
                    val bboxH = maxY - minY + 1
                    val aspectRatio = bboxW.toFloat() / bboxH.toFloat()

                    // 尋找此聚類中佔比最高的蘑菇種類 (Dominant Color Voting)
                    var bestTypeIndex = -1
                    var maxVotes = 0
                    for (i in typeHistogram.indices) {
                        if (typeHistogram[i] > maxVotes) {
                            maxVotes = typeHistogram[i]
                            bestTypeIndex = i
                        }
                    }

                    if (bestTypeIndex >= 0) {
                        val dominantType = MushroomType.entries[bestTypeIndex]

                        // 判斷是否為使用者要尋找的目標種類
                        if (targetTypes.contains(dominantType)) {
                            // 幾何形狀過濾：排除細長道路與長條雜訊 (長寬比 0.35 ~ 2.80, 寬度 >= 5px)
                            if (count >= minClusterSize && count <= maxClusterSize && aspectRatio in 0.35f..2.85f && bboxW >= 5) {
                                val origX = ((sumX / count) / scale).toInt()
                                val origY = ((sumY / count) / scale).toInt()
                                val radius = (max(bboxW, bboxH) / (2f * scale)).toInt().coerceAtLeast(18)
                                val isGiant = count >= minGiantClusterSize || dominantType == MushroomType.GIANT_EVENT

                                val confidence = min(1.0f, (maxVotes.toFloat() / count.toFloat()) * (count.toFloat() / (minClusterSize * 2f)))

                                detected.add(
                                    DetectedMushroom(
                                        type = dominantType,
                                        x = origX,
                                        y = origY,
                                        radius = radius,
                                        confidence = confidence.coerceIn(0.40f, 1.0f),
                                        isGiant = isGiant
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }

        // 去除重疊之鄰近檢測點 (非極大值抑制 NMS)
        val merged = mutableListOf<DetectedMushroom>()
        for (m in detected) {
            val duplicate = merged.find { abs(it.x - m.x) < 50 && abs(it.y - m.y) < 50 }
            if (duplicate == null) {
                merged.add(m)
            }
        }

        return merged
    }

    /**
     * 精準 HSV 色域分類器：依 Pikmin Bloom 真機截圖動態光影校準
     */
    private fun classifyHsvToTarget(hsv: FloatArray): MushroomType? {
        val h = hsv[0] // 0..360
        val s = hsv[1] // 0..1
        val v = hsv[2] // 0..1

        return when {
            // 🔴 1. 紅色 (大紅菇)
            ((h in 345f..360f) || (h in 0f..20f)) && s >= 0.28f && v >= 0.24f -> MushroomType.LARGE_RED

            // 🔥 2. 火元素菇 (大火菇) - 明烈紅橘火光
            h in 12f..36f && s >= 0.40f && v >= 0.35f -> MushroomType.LARGE_FIRE

            // ⚡ 3. 電元素菇 (大電菇) - 高亮金黃電弧
            h in 42f..68f && s >= 0.32f && v >= 0.58f -> MushroomType.LARGE_ELECTRIC

            // 🟡 4. 黃色 (大黃菇)
            h in 38f..65f && s >= 0.28f && v in 0.35f..0.98f -> MushroomType.LARGE_YELLOW

            // 💧 5. 水元素菇 (大水菇) - 湛青流水水花
            h in 182f..222f && s >= 0.32f && v >= 0.30f -> MushroomType.LARGE_WATER

            // 🔵 6. 藍色 (大藍菇)
            h in 190f..248f && s >= 0.24f && v in 0.20f..0.96f -> MushroomType.LARGE_BLUE

            // 🟣 7. 紫色 (大紫菇)
            h in 255f..305f && s >= 0.22f && v in 0.20f..0.92f -> MushroomType.LARGE_PURPLE

            // 🧪 8. 毒元素菇 (大毒菇) - 劇毒紫紅或青碧毒霧
            ((h in 275f..330f && s >= 0.28f) || (h in 150f..182f && s >= 0.30f && v >= 0.40f)) -> MushroomType.LARGE_POISON

            // 🌸 9. 粉色 (大粉羽菇)
            h in 312f..348f && s >= 0.20f && v >= 0.35f -> MushroomType.LARGE_PINK

            // 👑 10. 巨大活動特殊菇 - 絢麗活動金光與特殊漸層
            h in 18f..48f && s >= 0.38f && v >= 0.50f -> MushroomType.GIANT_EVENT

            // 💎 11. 水晶元素菇 (大水晶菇) - 冰透高明度微藍晶面
            (h in 175f..225f && s in 0.08f..0.45f && v >= 0.65f) -> MushroomType.LARGE_CRYSTAL

            // ⚪ 12. 白色 (大白菇) - 純淨白傘 (需具備一定純淨度)
            s <= 0.18f && v >= 0.74f -> MushroomType.LARGE_WHITE

            // 🪨 13. 灰色 (大灰岩菇) - 粗糙深岩石面
            s <= 0.28f && v in 0.18f..0.62f -> MushroomType.LARGE_GRAY

            else -> null
        }
    }

    /**
     * 在原始 Bitmap 上繪製高亮光圈與標註 (用於預覽與視覺確認)
     */
    fun drawDetectionPreview(source: Bitmap, detected: List<DetectedMushroom>): Bitmap {
        val result = source.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(result)

        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 6f
        }

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 32f
            typeface = Typeface.DEFAULT_BOLD
            setShadowLayer(6f, 0f, 0f, Color.BLACK)
        }

        for (m in detected) {
            strokePaint.color = m.type.colorHex.toInt()
            val drawRadius = (m.radius * 1.3f).coerceAtLeast(35f)
            canvas.drawCircle(m.x.toFloat(), m.y.toFloat(), drawRadius, strokePaint)

            val label = if (m.isGiant) "👑 ${m.type.title}" else m.type.title
            canvas.drawText(label, m.x.toFloat() - (drawRadius * 0.8f), m.y.toFloat() - drawRadius - 8f, textPaint)
        }

        return result
    }
}
