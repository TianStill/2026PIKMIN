# Fake GPS Pro - Android 原生模擬定位與皮克敏巡航應用程式

一款基於 **Android 原生 (Kotlin + Jetpack Compose)** 開發的虛擬定位（Fake GPS）應用程式，專為地圖定位測試、Pikmin Bloom (皮克敏) 巡弋與遊戲模擬設計。

---

## 🌟 核心特色功能清單

1. **底層模擬定位引擎 (`MockLocationEngine`)**：
   - 註冊 Android 系統測試提供者 (`GPS_PROVIDER` 與 `NETWORK_PROVIDER`)。
   - 注入完整模擬衛星訊號（經度、緯度、精準度、速度、方位角 Bearing、時間戳記、海拔高度）。
   - **擬真微幅飄移 (Realistic Jitter)**：內建高斯雜訊演算法（$\pm 0.4\text{ m}$），模擬真實硬體天線訊號波動，避免被遊戲防作弊偵測判定為絕對靜止假訊號。

2. **背景常駐前台服務 (`MockLocationService`)**：
   - 透過 Android 系統前台服務 (`Foreground Service`)，綁定 `location | mediaProjection` 類型。
   - 搭配狀態列常駐通知與 WakeLock，確保切換至 Pikmin Bloom 遊戲或背景待機時定位永不中斷。

3. **無人機自動巡航與蘑菇辨識 (`DroneScanner` & `CV Detector`)**：
   - **無人機自動巡弋路徑 (`DronePathGenerator`)**：支援從中心點向外擴散的自動搜尋飛行航線。
   - **無人機巡弋控制器 (`DroneScannerManager`)**：透過 `MediaProjection` 螢幕捕捉技術結合航向推進，實現全自動巡航。
   - **畫面色彩與形狀辨識 (`MushroomDetector`)**：針對 Pikmin Bloom 地圖畫面特徵，自動分析偵測畫面中的蘑菇種類（紅、黃、藍、白、紫、羽、岩等）與所在座標。
   - **專屬掃描控制面板 (`DroneScannerDialog`)**：設定巡弋半徑、搜尋步長與即時狀態監控。

4. **多圖資切換地圖介面 (`MapViewContainer` + `osmdroid`)**：
   - 整合開源 **OpenStreetMap (osmdroid)**。
   - **多種圖資自由切換 (`GoogleMapTileSources`)**：支援標準 OpenStreetMap、Google 街道圖 (Roadmap)、Google 衛星空照圖 (Satellite) 與地形圖 (Terrain)。
   - 中心準心釘選座標、滑動與縮放即時聯動。

5. **搜尋與座標輸入 (`LocationSearchBar` & `InputCoordinatesDialog`)**：
   - **地址與地名搜尋**：整合 Nominatim 線上地理編碼，支援中文地址、地標模糊搜尋。
   - **精準數值輸入**：提供專屬座標彈窗，直接輸入或微調經度、緯度。

6. **切換 APP 自動剪貼簿辨識 (`Auto-Clipboard Detection`)**：
   - **智慧座標過濾 (`ExtractedCoordinate`)**：自動從 LINE、Discord 聊天群組的混雜文字中辨識座標（例如「`60.4469650, 23.2501560 華麗 免3`」自動萃取有效經緯度）。
   - **切換回 App 自動生效**：複製座標後切換回本 App 立即自動辨識並提供一鍵跳轉。

7. **書籤地點與歷史紀錄管理 (`FavoritesSheet` & `HistorySheet`)**：
   - **常用地點收藏 (`FavoritesSheet`)**：一鍵儲存喜愛景點或常打蘑菇點，支援快速切換。
   - **歷史移動紀錄 (`HistorySheet`)**：自動保留近期傳送點歷史軌跡。

---

## 📱 使用與設定指引

### 步驟 1：開啟手機「開發人員選項」並指定模擬 App
1. 進入手機 **「設定」** $\to$ **「關於手機」** $\to$ 連續點擊 **「版本號碼」** 7 次直到提示開啟開發人員模式。
2. 返回設定，進入 **「系統」** $\to$ **「開發人員選項」**。
3. 找到 **「選取模擬位置資訊應用程式」**（Select mock location app），點選並指定為 **「Fake GPS Pro」**。

### 步驟 2：開始模擬與巡航
1. 在地圖上拖曳準心、搜尋目的地或直接輸入經緯度。
2. 點擊畫面上的 **「開始模擬」** 按鈕，狀態列將顯示常駐通知。
3. 可開啟 **無人機巡弋（Drone Scanner）** 進行大範圍蘑菇偵測與自動航行。
4. 切換至目標應用程式（例如 Pikmin Bloom / 地圖導航），即可享受擬真的位置更新！

---

## 📂 專案架構概覽

```
app/src/main/
├── AndroidManifest.xml                  // 系統權限 (Location, MediaProjection) 與 Service 宣告
├── java/com/pikmin/fakegps/
│   ├── FakeGpsApplication.kt            // 全域 Application (通知管道與 OSM 設定)
│   ├── cv/                              // 電腦視覺與蘑菇辨識模組
│   │   ├── DetectedMushroom.kt          // 偵測到的蘑菇實體與座標
│   │   ├── MushroomCategory.kt          // 蘑菇分類
│   │   ├── MushroomDetector.kt          // 畫面像素色彩辨識演算法
│   │   └── MushroomType.kt              // 蘑菇顏色與種類定義
│   ├── data/                            // 資料層
│   │   ├── model/BookmarkPoint.kt       // 書籤收藏資料模型
│   │   ├── model/LocationHistoryPoint.kt// 歷史紀錄資料模型
│   │   ├── model/LocationPoint.kt       // 基礎經緯度模型
│   │   ├── model/MovementMode.kt        // 移動速度模式
│   │   └── repository/PreferencesRepo.kt// 偏好設定、書籤與歷史持久化儲存
│   ├── drone/                           // 無人機自動巡弋模組
│   │   ├── DronePathGenerator.kt        // 螺旋與網格巡航航線推算
│   │   ├── DroneScannerManager.kt       // 巡弋生命週期與截圖偵測整合
│   │   └── DroneScanStatus.kt           // 巡航狀態列舉
│   ├── service/                         // 核心背景服務
│   │   ├── MockLocationEngine.kt        // Android LocationManager 底層注入邏輯 (含 Jitter)
│   │   └── MockLocationService.kt       // 前台常駐服務 (Foreground Service)
│   ├── utils/                           // 通用工具類
│   │   ├── ExtractedCoordinate.kt       // 剪貼簿群組文字智慧過濾正規表達式
│   │   ├── GeoUtils.kt                  // 球面大圓公式、方位角與距離航位推算
│   │   ├── GoogleMapTileSources.kt      // Google 衛星/街道/地形圖資定義
│   │   ├── MapType.kt                   // 圖資模式列舉
│   │   └── PermissionHelper.kt          // 開發者選項模擬位置與通知權限檢測
│   └── ui/                              // Jetpack Compose 介面
│       ├── MainActivity.kt              // 主畫面進入點與狀態聯動
│       ├── viewmodel/MainViewModel.kt   // UI State、搜尋、巡航與服務控制器
│       ├── components/                  // UI 元件
│       │   ├── DroneScannerDialog.kt    // 無人機巡航與蘑菇掃描控制對話框
│       │   ├── FavoritesSheet.kt        // 常用地點收藏抽屜
│       │   ├── HistorySheet.kt          // 歷史傳送紀錄抽屜
│       │   ├── InputCoordinatesDialog.kt// 經緯度數值直接輸入彈窗
│       │   ├── LocationSearchBar.kt     // 地名/地址關鍵字搜尋欄
│       │   └── MapViewContainer.kt      // osmdroid Compose 封裝與準心圖層
│       └── theme/                       // Material 3 主題配色與字體配置
```

---

## 🛠️ 編譯與建置 (Build & Run)

1. 使用 **Android Studio (Giraffe / Hedgehog / Iguana / Jellyfish 或更新版本)** 開啟此專案資料夾 `2026PIKMIN`。
2. 等待 Gradle Sync 完成。
3. 連接 Android 實體裝置 (開啟 USB 偵錯) 或啟動 Android 模擬器。
4. 點擊 Android Studio 上方綠色的 **Run (Shift + F10)** 即可安裝執行。
