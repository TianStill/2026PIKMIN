package com.pikmin.fakegps.cv

import android.graphics.*
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 蘑菇分類大項 (僅鎖定：大元素菇、大顏色菇)
 */
enum class MushroomCategory(val title: String) {
    LARGE_ELEMENT("大元素菇"),
    LARGE_COLOR("大顏色菇")
}

/**
 * 蘑菇目標種類枚舉 (僅鎖定：大元素菇、大顏色菇)
 */
enum class MushroomType(
    val title: String,
    val shortName: String,
    val category: MushroomCategory,
    val colorHex: Long
) {
    // ⚡ 1. 大元素菇 (每一種元素)
    LARGE_ELECTRIC("大電蘑菇 ⚡", "大電菇", MushroomCategory.LARGE_ELEMENT, 0xFFFACC15),
    LARGE_FIRE("火蘑菇 🔥", "火菇", MushroomCategory.LARGE_ELEMENT, 0xFFFF4500),
    LARGE_WATER("水蘑菇 💧", "水菇", MushroomCategory.LARGE_ELEMENT, 0xFF06B6D4),
    LARGE_CRYSTAL("水晶蘑菇 💎", "水晶菇", MushroomCategory.LARGE_ELEMENT, 0xFFA5F3FC),
    LARGE_POISON("毒蘑菇 🧪", "毒菇", MushroomCategory.LARGE_ELEMENT, 0xFFA855F7),

    // 🌈 2. 大顏色菇 (每一種顏色)
    LARGE_RED("大紅蘑菇", "大紅菇", MushroomCategory.LARGE_COLOR, 0xFFEF4444),
    LARGE_YELLOW("大黃蘑菇", "大黃菇", MushroomCategory.LARGE_COLOR, 0xFFEAB308),
    LARGE_BLUE("大藍蘑菇", "大藍菇", MushroomCategory.LARGE_COLOR, 0xFF3B82F6),
    LARGE_PURPLE("大紫蘑菇", "大紫菇", MushroomCategory.LARGE_COLOR, 0xFF9333EA),
    LARGE_WHITE("大白蘑菇", "大白菇", MushroomCategory.LARGE_COLOR, 0xFFF1F5F9),
    LARGE_PINK("大粉羽蘑菇", "大粉菇", MushroomCategory.LARGE_COLOR, 0xFFEC4899),
    LARGE_GRAY("大灰岩蘑菇", "大灰菇", MushroomCategory.LARGE_COLOR, 0xFF64748B);

    companion object {
        val ALL_TARGETS = entries.toSet()
        val ELEMENT_TARGETS = entries.filter { it.category == MushroomCategory.LARGE_ELEMENT }.toSet()
        val COLOR_TARGETS = entries.filter { it.category == MushroomCategory.LARGE_COLOR }.toSet()

        /**
         * 取得對外顯示完整標籤
         */
        fun getDisplayName(type: MushroomType): String {
            return when (type.category) {
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
    private const val FAM_WHITE = 7
    private const val FAM_GRAY = 8

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

        // 僅分析螢幕中段 (排除頂部 8% 狀態列與底部控制底欄)
        val startY = (scaledH * 0.08f).toInt()
        val endY = (scaledH * 0.88f).toInt()

        val hsv = FloatArray(3)
        // 家族標記矩陣：0=非目標背景，>0=色彩家族編號
        val familyMask = IntArray(scaledW * scaledH)
        // 具體蘑菇種類標記：0=無，>0=MushroomType.ordinal + 1
        val typeMask = IntArray(scaledW * scaledH)

        // 僅排除玩家正中心微小角色標記 (~3.5% 寬高範圍)，避免盲區過大吞噬周圍正常蘑菇
        val centerPlayerX = scaledW / 2
        val centerPlayerY = (scaledH * 0.58f).toInt()
        val playerExclusionRadiusX = (scaledW * 0.035f).toInt()
        val playerExclusionRadiusY = (scaledH * 0.035f).toInt()

        for (y in startY until endY) {
            val rowOffset = y * scaledW
            for (x in 0 until scaledW) {
                if (abs(x - centerPlayerX) <= playerExclusionRadiusX && abs(y - centerPlayerY) <= playerExclusionRadiusY) {
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
        val visited = BooleanArray(scaledW * scaledH)

        // 實體大小過濾門檻：寬高不可超過畫面特定比例 (排除河流、整條馬路、大片湖泊)
        val maxBboxW = (scaledW * 0.35f).toInt()
        val maxBboxH = (scaledH * 0.20f).toInt()
        val minLargeClusterSize = 280
        val maxClusterSize = 3500

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
                    val bboxArea = bboxW * bboxH

                    // 🌟 嚴格區分「大蘑菇/大元素菇」與「一般普通/小型蘑菇」：
                    // 一般普通菇之聚類通常僅約 70~265px、外接高僅 7~17px、面積僅 160~450px
                    // 真正的大蘑菇（大元素菇、大顏色菇）體積與傘蓋龐大：聚類需 >= 280px，寬 >= 26px，高 >= 18px，面積 >= 600px
                    if (count < minLargeClusterSize || count > maxClusterSize) {
                        continue
                    }

                    // 地形特徵過濾：排除過大區塊 (整條馬路、大片湖泊)
                    if (bboxW > maxBboxW || bboxH > maxBboxH) {
                        continue
                    }

                    // 幾何尺寸與面積過濾：排除一般小型/普通蘑菇
                    if (bboxW < 26 || bboxH < 18 || bboxArea < 600) {
                        continue
                    }

                    // 長寬比過濾：蘑菇頂部在 3D 視角下為扁圓形 (長寬比 0.40 ~ 3.80)
                    val aspectRatio = bboxW.toFloat() / bboxH.toFloat()
                    if (aspectRatio !in 0.40f..3.80f) {
                        continue
                    }

                    // 實心度/填充率過濾：排除散落碎花瓣與蜿蜒軌跡
                    val fillRatio = count.toFloat() / bboxArea.toFloat()
                    if (fillRatio < 0.22f) {
                        continue
                    }

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
                            val purity = maxVotes.toFloat() / count.toFloat()
                            if (purity < 0.35f) {
                                continue
                            }

                            val origX = ((sumX / count) / scale).toInt()
                            val origY = ((sumY / count) / scale).toInt()

                            // 排除左上角玩家個人頭像與文字標籤區域
                            if (origX < (width * 0.16f) && origY < (height * 0.22f)) {
                                continue
                            }

                            val radius = (max(bboxW, bboxH) / (1.8f * scale)).toInt().coerceAtLeast(24)
                            val isGiant = count >= 200 || dominantType.category == MushroomCategory.LARGE_ELEMENT

                            val confidence = min(1.0f, max(0.60f, purity * 0.9f + 0.1f))

                            detected.add(
                                DetectedMushroom(
                                    type = dominantType,
                                    x = origX,
                                    y = origY,
                                    radius = radius,
                                    confidence = confidence,
                                    isGiant = isGiant
                                )
                            )
                        }
                    }
                }
            }
        }

        // 依聚類半徑與信心度降序排序
        detected.sortByDescending { it.radius * it.confidence }

        // 類別感知型歐幾里得幾何距離非極大值抑制 (Type-Aware Euclidean NMS)
        // 同種類蘑菇進行光圈聚合（防止同顆菇多圈）；不同種類蘑菇則允許緊鄰並存（例如活動菇與大紅菇並排）
        val merged = mutableListOf<DetectedMushroom>()
        for (m in detected) {
            val duplicate = merged.find { accepted ->
                val dx = (accepted.x - m.x).toDouble()
                val dy = (accepted.y - m.y).toDouble()
                val dist = kotlin.math.hypot(dx, dy)
                val thresh = if (accepted.type == m.type) {
                    max(max(accepted.radius, m.radius) * 1.4, 60.0)
                } else {
                    min(accepted.radius, m.radius) * 0.75
                }
                dist < thresh
            }
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

        // 1. 嚴格過濾遊戲草地綠色地形 (Hue 70..140 且飽和度 >= 0.20)
        if (h in 70f..140f && s >= 0.20f) {
            return null
        }

        return when {
            // ⚡ 1. 大電蘑菇 (LARGE_ELECTRIC) - 晶亮金黃電弧 (高明度電弧黃)
            h in 42f..66f && s in 0.35f..0.85f && v >= 0.70f -> Pair(FAM_ELECTRIC_YELLOW, MushroomType.LARGE_ELECTRIC)

            // 🟡 2. 普通大黃菇 (LARGE_YELLOW)
            h in 36f..66f && s >= 0.35f && v in 0.40f..0.98f -> Pair(FAM_ELECTRIC_YELLOW, MushroomType.LARGE_YELLOW)

            // 🔥 3. 火蘑菇 (LARGE_FIRE) - 高飽和鮮明橘紅火焰 (S >= 0.55，排除普通紅菇高光)
            h in 12f..36f && s >= 0.55f && v >= 0.45f -> Pair(FAM_FIRE_RED, MushroomType.LARGE_FIRE)

            // 🔴 4. 普通大紅菇 (LARGE_RED)
            ((h in 346f..360f) || (h in 0f..20f)) && s >= 0.38f && v >= 0.30f -> Pair(FAM_FIRE_RED, MushroomType.LARGE_RED)

            // 🧪 5. 毒蘑菇 (LARGE_POISON) - 青碧毒霧 (Hue 142..180, S >= 0.15) 與 劇毒紫斑 (Hue 280..350, S >= 0.26)
            ((h in 142f..180f && s in 0.15f..0.85f && v >= 0.35f) || (h in 280f..350f && s >= 0.26f && v >= 0.35f)) -> Pair(FAM_POISON, MushroomType.LARGE_POISON)

            // 💧 6. 水蘑菇 (LARGE_WATER) - 清透水藍水滴 (Hue 181..220 且 S in 0.22..0.85, V >= 0.35)
            h in 181f..220f && s in 0.22f..0.85f && v >= 0.35f -> Pair(FAM_WATER_BLUE, MushroomType.LARGE_WATER)

            // 💎 7. 水晶蘑菇 (LARGE_CRYSTAL) - 冰透低飽和微藍反光晶面 (高明度冰藍，S in 0.12..0.22 避開水菇透光水泡)
            h in 180f..230f && s in 0.12f..0.22f && v >= 0.75f -> Pair(FAM_CRYSTAL, MushroomType.LARGE_CRYSTAL)

            // 🔵 8. 普通大藍菇 (LARGE_BLUE)
            h in 220f..250f && s >= 0.38f && v >= 0.30f -> Pair(FAM_WATER_BLUE, MushroomType.LARGE_BLUE)

            // 🟣 9. 普通大紫菇 (LARGE_PURPLE)
            h in 255f..300f && s >= 0.30f && v in 0.25f..0.90f -> Pair(FAM_PURPLE_PINK, MushroomType.LARGE_PURPLE)

            // 🌸 10. 普通大粉菇 (LARGE_PINK)
            h in 315f..345f && s >= 0.28f && v >= 0.38f -> Pair(FAM_PURPLE_PINK, MushroomType.LARGE_PINK)

            // ⚪ 11. 普通大白菇 (LARGE_WHITE) - 高純度白傘 (避免道路低飽和地皮)
            s <= 0.08f && v >= 0.95f -> Pair(FAM_WHITE, MushroomType.LARGE_WHITE)

            // 🪨 12. 普通大灰菇 (LARGE_GRAY)
            s <= 0.15f && v in 0.25f..0.50f -> Pair(FAM_GRAY, MushroomType.LARGE_GRAY)

            else -> null
        }
    }

    /**
     * 在原始 Bitmap 上繪製高亮光圈與標註 (用於預覽與視覺確認)
     */
    fun drawDetectionPreview(source: Bitmap, detected: List<DetectedMushroom>): Bitmap {
        val result = source.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(result)

        val baseDim = max(source.width, source.height)
        val uiScale = (baseDim / 1080f).coerceIn(0.8f, 2.5f)

        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 6f * uiScale
        }

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 28f * uiScale
            typeface = Typeface.DEFAULT_BOLD
            setShadowLayer(6f * uiScale, 0f, 0f, Color.BLACK)
        }

        for (m in detected) {
            strokePaint.color = m.type.colorHex.toInt()
            val drawRadius = (m.radius * 1.25f).coerceAtLeast(35f * uiScale)
            canvas.drawCircle(m.x.toFloat(), m.y.toFloat(), drawRadius, strokePaint)

            val label = MushroomType.getDisplayName(m.type)
            canvas.drawText(label, m.x.toFloat() - (drawRadius * 0.8f), m.y.toFloat() - drawRadius - (8f * uiScale), textPaint)
        }

        return result
    }
}
