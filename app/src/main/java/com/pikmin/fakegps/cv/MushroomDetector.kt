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
 * Pikmin Bloom 地圖蘑菇電腦視覺 (CV) 檢測核心
 */
object MushroomDetector {

    /**
     * 分析遊戲畫面 Bitmap，僅鎖定：巨大活動特殊菇、大顏色菇、大元素菇
     */
    fun detectMushrooms(
        sourceBitmap: Bitmap,
        targetTypes: Set<MushroomType> = MushroomType.ALL_TARGETS
    ): List<DetectedMushroom> {
        val width = sourceBitmap.width
        val height = sourceBitmap.height

        // 縮放取樣以達到極速分析 (將長邊限制在 640px，保證在 50ms 內完成)
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

        // 僅分析螢幕中段 (排除頂部通知列/狀態列與底部按鈕選單)
        val startY = (scaledH * 0.15f).toInt()
        val endY = (scaledH * 0.85f).toInt()

        val hsv = FloatArray(3)
        // 標記矩陣：0=未分類，>0=MushroomType.ordinal + 1
        val mask = ByteArray(scaledW * scaledH)

        for (y in startY until endY) {
            val rowOffset = y * scaledW
            for (x in 0 until scaledW) {
                val pixel = pixels[rowOffset + x]
                Color.colorToHSV(pixel, hsv)
                val type = classifyHsvToTarget(hsv)
                if (type != null && targetTypes.contains(type)) {
                    mask[rowOffset + x] = (type.ordinal + 1).toByte()
                }
            }
        }

        // 區塊聚類 (連通分量提取蘑菇中心與尺寸)
        val detected = mutableListOf<DetectedMushroom>()
        val visited = BooleanArray(scaledW * scaledH)

        // 🌟 嚴格大菇尺寸門檻：排除普通菇/小菇 (普通菇 <110px)，只鎖定大菇 (>=120px) 與巨大菇 (>=350px)
        val minLargeClusterSize = 120
        val minGiantClusterSize = 350
        val maxClusterSize = 18000

        val queue = IntArray(maxClusterSize * 2)

        for (y in startY until endY step 2) {
            val rowOffset = y * scaledW
            for (x in 0 until scaledW step 2) {
                val idx = rowOffset + x
                val typeOrd = mask[idx].toInt() - 1
                if (typeOrd >= 0 && !visited[idx]) {
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

                    while (head < tail && tail < queue.size - 4) {
                        val curr = queue[head++]
                        val cx = curr % scaledW
                        val cy = curr / scaledW
                        sumX += cx
                        sumY += cy
                        count++

                        if (cx < minX) minX = cx
                        if (cx > maxX) maxX = cx
                        if (cy < minY) minY = cy
                        if (cy > maxY) maxY = cy

                        val neighbors = intArrayOf(curr - 1, curr + 1, curr - scaledW, curr + scaledW)
                        for (n in neighbors) {
                            if (n in 0 until (scaledW * scaledH)) {
                                if (!visited[n] && (mask[n].toInt() - 1) == typeOrd) {
                                    visited[n] = true
                                    queue[tail++] = n
                                }
                            }
                        }
                    }

                    val bboxW = maxX - minX + 1
                    val bboxH = maxY - minY + 1
                    val aspectRatio = bboxW.toFloat() / bboxH.toFloat()

                    val mushroomType = MushroomType.entries[typeOrd]
                    val isGiantTarget = mushroomType == MushroomType.GIANT_EVENT
                    val minRequiredSize = if (isGiantTarget) minGiantClusterSize else minLargeClusterSize

                    // 僅保留符合「大 / 巨大」尺寸與圓頂實體長寬 (長寬比 0.35 ~ 3.00, 寬度 >= 14px)
                    if (count >= minRequiredSize && count <= maxClusterSize && aspectRatio in 0.35f..3.00f && bboxW >= 14) {
                        val origX = ((sumX / count) / scale).toInt()
                        val origY = ((sumY / count) / scale).toInt()
                        val radius = (max(bboxW, bboxH) / (2f * scale)).toInt().coerceAtLeast(20)
                        val isGiant = count >= minGiantClusterSize

                        detected.add(
                            DetectedMushroom(
                                type = mushroomType,
                                x = origX,
                                y = origY,
                                radius = radius,
                                confidence = min(1.0f, count.toFloat() / (minRequiredSize * 1.5f)),
                                isGiant = isGiant || isGiantTarget
                            )
                        )
                    }
                }
            }
        }

        // 去除重疊之鄰近檢測點 (NMS)
        val merged = mutableListOf<DetectedMushroom>()
        for (m in detected) {
            val duplicate = merged.find { abs(it.x - m.x) < 45 && abs(it.y - m.y) < 45 }
            if (duplicate == null) {
                merged.add(m)
            }
        }

        return merged
    }

    /**
     * HSV 色域分類器：依實機真機截圖像素光學校準 (先排除低彩黑灰岩，再匹配高彩電/火/水)
     */
    private fun classifyHsvToTarget(hsv: FloatArray): MushroomType? {
        val h = hsv[0] // 0..360
        val s = hsv[1] // 0..1
        val v = hsv[2] // 0..1

        return when {
            // 🪨 1. 大顏色菇：灰色 (岩石) - 低彩度灰黑岩頂 (S <= 0.30, V: 0.15..0.65)
            s <= 0.30f && v in 0.15f..0.65f -> MushroomType.LARGE_GRAY

            // ⚪ 2. 大顏色菇：白色 - 低彩高亮 (S <= 0.18, V >= 0.72)
            s <= 0.18f && v >= 0.72f -> MushroomType.LARGE_WHITE

            // ⚡ 3. 大元素菇：電 (Electric) - 鮮明金黃電弧 (H: 38..66, S >= 0.26, V >= 0.45)
            h in 38f..66f && s in 0.26f..0.85f && v >= 0.45f -> MushroomType.LARGE_ELECTRIC

            // 🔥 4. 大元素菇：火 (Fire) - 明烈紅橘火燄光 (H: 14..35)
            h in 14f..35f && s >= 0.35f && v >= 0.38f -> MushroomType.LARGE_FIRE

            // 💎 5. 大元素菇：水晶 (Crystal) - 冰透高明度低彩晶面 (H: 180..220, S <= 0.35, V >= 0.68 或 S <= 0.20, V >= 0.75)
            (h in 180f..220f && s <= 0.35f && v >= 0.68f) || (s <= 0.20f && v >= 0.75f) -> MushroomType.LARGE_CRYSTAL

            // 💧 6. 大元素菇：水 (Water) - 湛青流水波紋 (H: 195..238, S >= 0.36, V >= 0.35)
            h in 195f..238f && s >= 0.36f && v >= 0.35f -> MushroomType.LARGE_WATER

            // 🧪 7. 大元素菇：毒 (Poison) - 青碧毒霧氣體 (H: 155..192, S: 0.12..0.80, V >= 0.45) 或 毒斑紫紅 (H: 280..340)
            (h in 155f..192f && s in 0.12f..0.80f && v >= 0.45f) || (h in 280f..340f && s >= 0.22f && v in 0.25f..0.90f) -> MushroomType.LARGE_POISON

            // 👑 8. 巨大活動特殊菇 - 絢麗橙金漸層/活動光環
            (h in 15f..45f && s >= 0.30f && v in 0.45f..0.98f) || (h in 255f..325f && s >= 0.30f && v >= 0.45f) -> MushroomType.GIANT_EVENT

            // 🔴 9. 大顏色菇：紅色 - 深赤紅傘蓋 (H: 0..12 或 348..360, S >= 0.42 嚴格排除普通紅菇)
            (h in 0f..12f || h >= 348f) && s >= 0.42f && v in 0.30f..0.95f -> MushroomType.LARGE_RED

            // 🔵 10. 大顏色菇：藍色 - 純深藍
            h in 195f..245f && s >= 0.25f && v in 0.20f..0.95f -> MushroomType.LARGE_BLUE

            // 🟡 11. 大顏色菇：黃色 - 純正亮黃
            h in 42f..65f && s in 0.35f..0.85f && v >= 0.45f -> MushroomType.LARGE_YELLOW

            // 🟣 12. 大顏色菇：紫色 - 飽和紫
            h in 260f..300f && s >= 0.25f && v in 0.20f..0.90f -> MushroomType.LARGE_PURPLE

            // 🌸 13. 大顏色菇：粉色 (羽) - 柔嫩粉紅
            h in 315f..358f && s >= 0.25f && v >= 0.38f -> MushroomType.LARGE_PINK

            else -> null
        }
    }

    /**
     * 判斷是否為潛在實體/蘑菇像素 (只要不是背景綠地草皮)
     */
    private fun isPotentialMushroomPixel(hsv: FloatArray): Boolean {
        val h = hsv[0]
        val s = hsv[1]
        val v = hsv[2]

        // 排除遊戲地圖綠色草皮 (色相 72..155)
        if (h in 72f..155f) return false
        // 排除過暗陰影
        if (v < 0.20f) return false
        // 具有色彩 (S >= 0.15) 或是晶透亮白水晶 (V >= 0.75)
        return s >= 0.15f || v >= 0.75f
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

            val label = m.type.title
            canvas.drawText(label, m.x.toFloat() - (drawRadius * 0.8f), m.y.toFloat() - drawRadius - 8f, textPaint)
        }

        return result
    }
}
