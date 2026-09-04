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
    GIANT_EVENT("巨大活動特殊菇", "活動菇", MushroomCategory.GIANT_EVENT, 0xFFF59E0B),

    // 🌈 2. 大顏色菇 (每一種顏色)
    LARGE_RED("大紅蘑菇", "大紅菇", MushroomCategory.LARGE_COLOR, 0xFFEF4444),
    LARGE_YELLOW("大黃蘑菇", "大黃菇", MushroomCategory.LARGE_COLOR, 0xFFEAB308),
    LARGE_BLUE("大藍蘑菇", "大藍菇", MushroomCategory.LARGE_COLOR, 0xFF3B82F6),
    LARGE_PURPLE("大紫蘑菇", "大紫菇", MushroomCategory.LARGE_COLOR, 0xFF9333EA),
    LARGE_WHITE("大白蘑菇", "大白菇", MushroomCategory.LARGE_COLOR, 0xFFF1F5F9),
    LARGE_PINK("大粉羽蘑菇", "大粉菇", MushroomCategory.LARGE_COLOR, 0xFFEC4899),
    LARGE_GRAY("大灰岩蘑菇", "大灰菇", MushroomCategory.LARGE_COLOR, 0xFF64748B),

    // ⚡ 3. 大元素菇 (每一種元素)
    LARGE_FIRE("火蘑菇 🔥", "火菇", MushroomCategory.LARGE_ELEMENT, 0xFFFF4500),
    LARGE_WATER("水蘑菇 💧", "水菇", MushroomCategory.LARGE_ELEMENT, 0xFF06B6D4),
    LARGE_ELECTRIC("電蘑菇 ⚡", "電菇", MushroomCategory.LARGE_ELEMENT, 0xFFFACC15),
    LARGE_CRYSTAL("水晶蘑菇 💎", "水晶菇", MushroomCategory.LARGE_ELEMENT, 0xFFA5F3FC),
    LARGE_POISON("毒蘑菇 🧪", "毒菇", MushroomCategory.LARGE_ELEMENT, 0xFFA855F7);

    companion object {
        val ALL_TARGETS = entries.toSet()
        val EVENT_TARGETS = setOf(GIANT_EVENT)
        val COLOR_TARGETS = entries.filter { it.category == MushroomCategory.LARGE_COLOR }.toSet()
        val ELEMENT_TARGETS = entries.filter { it.category == MushroomCategory.LARGE_ELEMENT }.toSet()

        /**
         * 取得對外顯示完整標籤
         */
        fun getDisplayName(type: MushroomType): String {
            return when (type.category) {
                MushroomCategory.GIANT_EVENT -> "👑 ${type.title}"
                MushroomCategory.LARGE_ELEMENT -> "⚡ 大元素菇【${type.title}】"
                MushroomCategory.LARGE_COLOR -> "🍄 大顏色菇【${type.title}】"
            }
        }
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

    // 色彩家族分組 (同一家族色彩才連通聚合，徹底防止白色道路與藍色河流串接誤判)
    private const val FAM_FIRE_RED = 1
    private const val FAM_ELECTRIC_YELLOW = 2
    private const val FAM_WATER_BLUE = 3
    private const val FAM_CRYSTAL = 4
    private const val FAM_POISON = 5
    private const val FAM_PURPLE_PINK = 6
    private const val FAM_EVENT = 7
    private const val FAM_WHITE = 8
    private const val FAM_GRAY = 9

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

        // 僅分析螢幕中段 (排除頂部 12% 狀態列與底部 12% 按鈕導航)
        val startY = (scaledH * 0.12f).toInt()
        val endY = (scaledH * 0.88f).toInt()

        val hsv = FloatArray(3)
        // 家族標記矩陣：0=非目標背景，>0=色彩家族編號
        val familyMask = IntArray(scaledW * scaledH)
        // 具體蘑菇種類標記：0=無，>0=MushroomType.ordinal + 1
        val typeMask = IntArray(scaledW * scaledH)

        // 僅排除玩家正中心微小角色圖標 (~14px 半徑)，避免盲區過大吞噬周圍蘑菇
        val centerPlayerX = scaledW / 2
        val centerPlayerY = (scaledH * 0.58f).toInt()
        val playerExclusionRadius = 14

        for (y in startY until endY) {
            val rowOffset = y * scaledW
            for (x in 0 until scaledW) {
                if (abs(x - centerPlayerX) <= playerExclusionRadius && abs(y - centerPlayerY) <= playerExclusionRadius) {
                    continue
                }

                val pixel = pixels[rowOffset + x]
                Color.colorToHSV(pixel, hsv)

                val classification = classifyHsvPixel(hsv)
                if (classification != null) {
                    val idx = rowOffset + x
                    familyMask[idx] = classification.first
                    typeMask[idx] = classification.second.ordinal + 1
                }
            }
        }

        // 區塊聚類 (以同色彩家族連通分量聚合提取整顆蘑菇實體)
        val detected = mutableListOf<DetectedMushroom>()

        // 🌟 引擎 1：Pikmin Bloom 專屬 2D UI 計時圓鐘徽章定位 (Badge Anchor)
        // 每顆可挑戰蘑菇正上方均懸浮紅扇形圓鐘與人數標籤，不受草地、河流、道路等地形雜訊干擾
        val badgeMushrooms = detectBadgeAnchoredMushrooms(pixels, scaledW, scaledH, scale, targetTypes)
        detected.addAll(badgeMushrooms)

        val visited = BooleanArray(scaledW * scaledH)

        // 實體大小過濾門檻：寬高不可超過畫面特定比例 (排除河流、整條馬路、大片湖泊)
        val maxBboxW = (scaledW * 0.35f).toInt()
        val maxBboxH = (scaledH * 0.18f).toInt()
        val minClusterSize = 18
        val maxClusterSize = 2200

        val queue = IntArray(maxClusterSize * 2)

        for (y in startY until endY) {
            val rowOffset = y * scaledW
            for (x in 0 until scaledW) {
                val idx = rowOffset + x
                val fam = familyMask[idx]
                if (fam > 0 && !visited[idx]) {
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

                        // 8 鄰域泛洪擴散：嚴格限制只能在「同一色彩家族」內擴散
                        val neighbors = intArrayOf(
                            curr - 1, curr + 1,
                            curr - scaledW, curr + scaledW,
                            curr - scaledW - 1, curr - scaledW + 1,
                            curr + scaledW - 1, curr + scaledW + 1
                        )

                        for (n in neighbors) {
                            if (n in 0 until (scaledW * scaledH)) {
                                if (!visited[n] && familyMask[n] == fam) {
                                    visited[n] = true
                                    queue[tail++] = n
                                }
                            }
                        }
                    }

                    val bboxW = maxX - minX + 1
                    val bboxH = maxY - minY + 1
                    val aspectRatio = bboxW.toFloat() / bboxH.toFloat()

                    // 地形特徵過濾：蘑菇不可為超長條馬路或跨越半屏的大河流
                    if (bboxW > maxBboxW || bboxH > maxBboxH) {
                        continue
                    }

                    // 幾何形狀過濾：排除細長長條雜訊 (長寬比 0.35 ~ 2.85, 寬度 >= 5px)
                    if (count in minClusterSize..maxClusterSize && aspectRatio in 0.35f..2.85f && bboxW >= 5) {
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
                                val origX = ((sumX / count) / scale).toInt()
                                val origY = ((sumY / count) / scale).toInt()
                                val radius = (max(bboxW, bboxH) / (2f * scale)).toInt().coerceAtLeast(18)
                                val isGiant = count >= 150 || dominantType.category == MushroomCategory.LARGE_ELEMENT || dominantType == MushroomType.GIANT_EVENT

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

        // 依聚類半徑/信心度降序排序，使最顯眼的蘑菇排在最前
        detected.sortByDescending { it.radius * it.confidence }

        // 去除重疊之鄰近檢測點 (非極大值抑制 NMS)
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
     * 真機校準色域分類器：結合色彩家族防污染機制
     */
    private fun classifyHsvPixel(hsv: FloatArray): Pair<Int, MushroomType>? {
        val h = hsv[0] // 0..360
        val s = hsv[1] // 0..1
        val v = hsv[2] // 0..1

        // 1. 嚴格過濾遊戲草地綠色地形 (Hue 75..140 且飽和度 >= 0.22)
        if (h in 75f..140f && s >= 0.22f) {
            return null
        }

        return when {
            // 🔥 2. 火蘑菇 (LARGE_FIRE) - 高飽和鮮明橘紅火焰 (S >= 0.52，排除普通紅菇高光)
            h in 12f..36f && s >= 0.52f && v >= 0.45f -> Pair(FAM_FIRE_RED, MushroomType.LARGE_FIRE)

            // ⚡ 3. 電蘑菇 (LARGE_ELECTRIC) - 晶亮金黃電弧 (高明度電弧黃)
            h in 40f..68f && s in 0.28f..0.65f && v >= 0.60f -> Pair(FAM_ELECTRIC_YELLOW, MushroomType.LARGE_ELECTRIC)

            // 🟡 4. 普通大黃菇 (LARGE_YELLOW)
            h in 38f..65f && s >= 0.25f && v in 0.35f..0.98f -> Pair(FAM_ELECTRIC_YELLOW, MushroomType.LARGE_YELLOW)

            // 🔴 5. 普通大紅菇 (LARGE_RED)
            ((h in 345f..360f) || (h in 0f..20f)) && s >= 0.28f && v >= 0.26f -> Pair(FAM_FIRE_RED, MushroomType.LARGE_RED)

            // 🧪 6. 毒蘑菇 (LARGE_POISON) - 碧青/薄荷綠毒霧霧氣 (Hue 140..175 且 S >= 0.30) 或 劇毒紫紅傘蓋 (Hue 265..335)
            (h in 140f..175f && s >= 0.30f && v >= 0.35f) || (h in 265f..335f && s >= 0.20f && v >= 0.20f) -> Pair(FAM_POISON, MushroomType.LARGE_POISON)

            // 💧 7. 水蘑菇 (LARGE_WATER) - 飽滿水潤青藍水滴 (Hue 180..215 且 S >= 0.28)
            h in 180f..215f && s >= 0.28f && v in 0.32f..0.96f -> Pair(FAM_WATER_BLUE, MushroomType.LARGE_WATER)

            // 💎 8. 水晶蘑菇 (LARGE_CRYSTAL) - 冰透低飽和微藍反光晶面 (高明度極淡透冰藍，S in 0.08..0.26)
            h in 175f..235f && s in 0.08f..0.26f && v >= 0.65f -> Pair(FAM_CRYSTAL, MushroomType.LARGE_CRYSTAL)

            // 🔵 9. 普通大藍菇 (LARGE_BLUE)
            h in 215f..255f && s >= 0.25f && v >= 0.22f -> Pair(FAM_WATER_BLUE, MushroomType.LARGE_BLUE)

            // 👑 10. 巨大活動特殊菇 (GIANT_EVENT)
            h in 18f..48f && s >= 0.38f && v >= 0.50f -> Pair(FAM_EVENT, MushroomType.GIANT_EVENT)

            // 🟣 11. 普通大紫菇 (LARGE_PURPLE)
            h in 255f..305f && s >= 0.22f && v in 0.20f..0.92f -> Pair(FAM_PURPLE_PINK, MushroomType.LARGE_PURPLE)

            // 🌸 12. 普通大粉菇 (LARGE_PINK)
            h in 312f..348f && s >= 0.20f && v >= 0.35f -> Pair(FAM_PURPLE_PINK, MushroomType.LARGE_PINK)

            // ⚪ 13. 普通大白菇 (LARGE_WHITE) - 高純度白傘 (避免道路低飽和地皮)
            s <= 0.15f && v >= 0.85f -> Pair(FAM_WHITE, MushroomType.LARGE_WHITE)

            // 🪨 14. 普通大灰菇 (LARGE_GRAY)
            s <= 0.25f && v in 0.22f..0.60f -> Pair(FAM_GRAY, MushroomType.LARGE_GRAY)

            else -> null
        }
    }

    /**
     * 👑 引擎 1：Pikmin Bloom 專屬 2D UI 計時圓鐘徽章定位核心 (Badge Anchor)
     * 在遊戲畫面中，每顆可挑戰的蘑菇正上方均懸浮一枚「紅扇形計時圓鐘 (⏰) + 人數標籤」
     * 徽章為純 2D UI 貼圖，不受光照、草皮、河流與地形雜訊影響，精準度極高！
     */
    private fun detectBadgeAnchoredMushrooms(
        pixels: IntArray,
        scaledW: Int,
        scaledH: Int,
        scale: Float,
        targetTypes: Set<MushroomType>
    ): List<DetectedMushroom> {
        val detected = mutableListOf<DetectedMushroom>()
        val startY = (scaledH * 0.10f).toInt()
        val endY = (scaledH * 0.90f).toInt()

        val redMask = BooleanArray(scaledW * scaledH)
        val hsv = FloatArray(3)

        // 1. 標記計時圓鐘上的高對比亮紅扇形像素
        for (y in startY until endY) {
            val offset = y * scaledW
            for (x in 0 until scaledW) {
                val c = pixels[offset + x]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF

                if (r > 165 && g < 115 && b < 115 && (r - max(g, b) > 50)) {
                    redMask[offset + x] = true
                }
            }
        }

        // 2. 聚類扇形紅標
        val visited = BooleanArray(scaledW * scaledH)
        val queue = IntArray(1000)

        for (y in startY until endY) {
            val offset = y * scaledW
            for (x in 0 until scaledW) {
                val idx = offset + x
                if (redMask[idx] && !visited[idx]) {
                    var head = 0
                    var tail = 0
                    queue[tail++] = idx
                    visited[idx] = true

                    var sumX = 0L
                    var sumY = 0L
                    var count = 0

                    while (head < tail && tail < queue.size - 4) {
                        val curr = queue[head++]
                        val cx = curr % scaledW
                        val cy = curr / scaledW
                        sumX += cx
                        sumY += cy
                        count++

                        val neighbors = intArrayOf(curr - 1, curr + 1, curr - scaledW, curr + scaledW)
                        for (n in neighbors) {
                            if (n in 0 until (scaledW * scaledH) && !visited[n] && redMask[n]) {
                                visited[n] = true
                                queue[tail++] = n
                            }
                        }
                    }

                    // 扇形紅標尺寸限制 (直徑約 3..14px)
                    if (count in 4..140) {
                        val bx = (sumX / count).toInt()
                        val by = (sumY / count).toInt()

                        // 驗證周遭是否有白色圓鐘鐘面底色 (8px 範圍內)
                        var hasWhiteCircle = false
                        for (dy in -8..8) {
                            for (dx in -8..8) {
                                val wx = bx + dx
                                val wy = by + dy
                                if (wx in 0 until scaledW && wy in 0 until scaledH) {
                                    val wc = pixels[wy * scaledW + wx]
                                    val wr = (wc shr 16) and 0xFF
                                    val wg = (wc shr 8) and 0xFF
                                    val wb = wc and 0xFF
                                    if (wr > 210 && wg > 210 && wb > 210) {
                                        hasWhiteCircle = true
                                        break
                                    }
                                }
                            }
                            if (hasWhiteCircle) break
                        }

                        if (hasWhiteCircle) {
                            // 3. 採樣圓鐘正下方實體 (Y + 8px 至 Y + 45px，寬 ±22px)
                            val bodyY1 = min(scaledH - 1, by + 8)
                            val bodyY2 = min(scaledH - 1, by + 45)
                            val bodyX1 = max(0, bx - 22)
                            val bodyX2 = min(scaledW - 1, bx + 22)

                            val typeVotes = IntArray(MushroomType.entries.size)
                            var validPixels = 0

                            for (sy in bodyY1..bodyY2) {
                                val sOffset = sy * scaledW
                                for (sx in bodyX1..bodyX2) {
                                    val sc = pixels[sOffset + sx]
                                    Color.colorToHSV(sc, hsv)
                                    val classification = classifyHsvPixel(hsv)
                                    if (classification != null) {
                                        typeVotes[classification.second.ordinal]++
                                        validPixels++
                                    }
                                }
                            }

                            if (validPixels >= 8) {
                                var bestIndex = -1
                                var maxV = 0
                                for (i in typeVotes.indices) {
                                    if (typeVotes[i] > maxV) {
                                        maxV = typeVotes[i]
                                        bestIndex = i
                                    }
                                }

                                if (bestIndex >= 0) {
                                    val targetType = MushroomType.entries[bestIndex]
                                    if (targetTypes.contains(targetType)) {
                                        val origX = (bx / scale).toInt()
                                        val origY = ((by + 24) / scale).toInt()
                                        detected.add(
                                            DetectedMushroom(
                                                type = targetType,
                                                x = origX,
                                                y = origY,
                                                radius = (32 / scale).toInt().coerceAtLeast(24),
                                                confidence = 0.96f,
                                                isGiant = targetType == MushroomType.GIANT_EVENT
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        return detected
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

            val label = MushroomType.getDisplayName(m.type)
            canvas.drawText(label, m.x.toFloat() - (drawRadius * 0.8f), m.y.toFloat() - drawRadius - 8f, textPaint)
        }

        return result
    }
}
