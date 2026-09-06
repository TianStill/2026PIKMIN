# Fake GPS Pro - Android 原生模擬定位與皮克敏巡航應用程式

一款基於 **Android 原生 (Kotlin + Jetpack Compose)** 開發的高可靠度虛擬定位（Fake GPS）應用程式，專為地圖定位測試、Pikmin Bloom (皮克敏) 巡弋、特殊元素蘑菇自動辨識與遊戲模擬設計。最低支援 Android 8.0（API 26），最新版本資訊以 `app/build.gradle.kts` 為準。

---

## 🌟 核心特色功能清單

### 1. 底層模擬定位引擎 (`MockLocationEngine`)
- **雙測試提供者同步注入**：註冊並同步管理 Android 系統 `GPS_PROVIDER` 與 `NETWORK_PROVIDER`，提供每秒高頻率精準定位。
- **完整訊號模擬**：注入包含經度、緯度、精準度 (Accuracy)、速度 (Speed)、方位角 (Bearing)、海拔高度 (Altitude) 與時間戳記。
- **擬真微幅漂移 (Realistic Jitter)**：內建隨機微幅漂移演算法（$\le 0.4\text{ m}$，可自由於介面開啟/關閉），模擬真實手機 GPS 天線的物理波動。
- **定位持久化與恢復**：自動記錄最後活躍位置，在前景服務因系統排程重建時無縫恢復定位。

### 2. 背景常駐前台服務 (`MockLocationService`)
- **前台服務 (Foreground Service)**：綁定 `location | mediaProjection` 系統類型，搭配狀態列常駐通知與快捷控制按鈕。
- **SessionGate 啟停安全防護**：統一服務生命週期控管，防止連按或競態條件導致的多重重複啟動或非預期殘留。
- **智慧螢幕喚醒鎖 (WakeLock)**：在自動巡航執行期間動態續租亮屏喚醒鎖，避免切換至 Pikmin Bloom 遊戲時因系統逾時休眠黑屏而中斷巡航。

### 3. 無人機自動巡航與五大元素蘑菇辨識 (`DroneScanner` & `MushroomDetector`)
- **螺旋航路擴散飛行 (`DronePathGenerator`)**：自設定中心點依設定步長與搜尋半徑，由內向外螺旋推算最佳無人機掃描航點。
- **五大特殊元素蘑菇精準辨識**：
  - 支援 **大型火 (Large Fire)**、**大型水 (Large Water)**、**大型電 (Large Electric)**、**大型毒 (Large Poison)**、**大型水晶 (Large Crystal)** 五大特殊元素蘑菇。
  - 結合 HSV 色彩空間特徵、色彩純度與連通幾何輪廓分析。
- **多影格一致性確認 (`FrameConfirmation`)**：
  - 必須連續取得 **3 個獨立不同新影格** 且判定一致方確認為目標蘑菇，杜絕瞬間畫面噪點或背景地形誤判。
- **候選感知延時推進與自動煞車**：
  - 航點推進時若偵測到疑似目標或尚待三幀確認，自動最多延長停留 3 秒以確保分析完整性。
  - 一旦確認發現目標蘑菇，無人機立即自動煞車停在目前觀測航點，並發出狀態通知提醒使用者確認。
- **連續黑畫面防護**：
  - 即時監測 `MediaProjection` 串流，若遇系統分享保護遮黑或連續黑畫面，自動停止巡航並提示重新授權。

### 4. 多圖資切換地圖介面 (`MapViewContainer` + `osmdroid`)
- **整合開源 OpenStreetMap (osmdroid)**，免 Google Play 服務依賴。
- **四種地圖圖資自由切換 (`GoogleMapTileSources`)**：
  - 🌐 OpenStreetMap 標準街道圖
  - 🗺️ Google 街道圖 (Roadmap)
  - 🛰️ Google 衛星空照圖 (Satellite)
  - ⛰️ Google 地形圖 (Terrain)
- **地圖生命週期防護**：重構 View 生命週期繫結，防止快速切換或 Activity 重建時的崩潰問題；自動記憶地圖中心座標與縮放等級 (Zoom Level)。

### 5. 智慧搜尋與座標擷取 (`LocationSearchBar` & `InputCoordinatesDialog`)
- **地址與地標搜尋**：整合 Nominatim 線上地理編碼服務，支援中文地址、地標名稱即時模糊搜尋。
- **彈窗精準數值微調**：提供專屬對話框直接輸入經緯度，支援微調按鈕快速校正。
- **智慧剪貼簿座標過濾 (`Auto-Clipboard Detection`)**：
  - 自動從 LINE、Discord 聊天群組的混雜中文對話中擷取有效經緯度（例如「`60.4469650, 23.2501560 華麗 免3`」自動過濾出座標）。
  - 切換回本 App 自動辨識並提供一鍵載入與跳轉（巡航期間自動暫停偵測，避免誤觸）。

### 6. 書籤收藏與歷史紀錄管理 (`FavoritesSheet` & `HistorySheet`)
- **地點書籤收藏 (`FavoritesSheet`)**：一鍵儲存常去景點或特定打菇點，支援快速切換與自訂名稱。
- **移動軌跡紀錄 (`HistorySheet`)**：自動保存最近傳送位置歷史紀錄，方便快速回溯。

### 7. 安全版本更新檢查 (`AppUpdateManager`)
- **GitHub Release 自動/手動檢查**：比對語意化版本號（Semantic Versioning），有新版本時於介面彈窗提示。
- **安全下載完整性校驗 (`DownloadIntegrity`)**：下載後自動比對 Release 的 SHA-256 雜湊值與 APK 簽章同源性，由使用者在前景確認後安全安裝。

---

## 📱 使用與設定步驟

### 步驟 1：開啟「開發人員選項」並指定模擬定位 App
1. 開啟手機 **「設定」** $\to$ **「關於手機」** $\to$ 連續點擊 **「版本號碼」** 7 次啟用開發人員選項。
2. 返回設定，進入 **「系統」** $\to$ **「開發人員選項」**。
3. 找到 **「選取模擬位置資訊應用程式」**（Select mock location app），指定為 **「Fake GPS Pro」**。

### 步驟 2：開始模擬定位
1. 開啟 App，在地圖上拖曳準心、搜尋目的地或輸入經緯度。
2. 點選 **「開始模擬」**，狀態列將顯示常駐前台通知。
3. 切換至目標應用程式（例如 Pikmin Bloom、Google Maps 等），即可套用模擬位置。

### 步驟 3：啟用無人機自動巡航（選擇性）
1. 點擊巡航圖示開啟 **「無人機巡弋」** 對話框，設定搜尋半徑與步長。
2. 點選 **「啟動巡弋」** 並授予螢幕擷取權限（Android 14+ 建議選擇 Pikmin Bloom 應用程式或整個螢幕）。
3. 保持遊戲直向顯示與適當縮放，巡航過程中無人機將自動依螺旋航線移動，並於偵測到特殊元素蘑菇時自動停下提醒。
4. 點選 **「停止無人機」** 保持目前航點定位，或點選 **「停止全部」** 一併結束定位與背景服務。

---

## 📂 專案架構概覽

```
app/src/main/
├── AndroidManifest.xml                  // 系統權限 (Location, MediaProjection) 與 Service 宣告
├── java/com/pikmin/fakegps/
│   ├── FakeGpsApplication.kt            // 全域 Application (通知管道與 OSM 設定)
│   ├── cv/                              // 電腦視覺與蘑菇辨識模組
│   │   ├── DetectedMushroom.kt          // 偵測到的蘑菇實體與座標資料結構
│   │   ├── MushroomCategory.kt          // 蘑菇分類 (特殊/元素蘑菇)
│   │   ├── MushroomDetector.kt          // 像素色彩、純度與輪廓演算法
│   │   └── MushroomType.kt              // 五大元素蘑菇類型定義
│   ├── data/                            // 資料模型與儲存層
│   │   ├── model/BookmarkPoint.kt       // 書籤收藏資料模型
│   │   ├── model/LocationHistoryPoint.kt// 歷史紀錄資料模型
│   │   ├── model/LocationPoint.kt       // 經緯度基礎模型
│   │   ├── model/MovementMode.kt        // 移動速度模式
│   │   └── repository/PreferencesRepo.kt// DataStore 偏好設定、書籤與歷史持久化
│   ├── drone/                           // 無人機自動巡弋模組
│   │   ├── DronePathGenerator.kt        // 螺旋航線產生演算法
│   │   ├── DroneScannerManager.kt       // 巡弋排程、ImageReader 擷取與確認流程
│   │   ├── DroneScanStatus.kt           // 巡航狀態管理
│   │   └── FrameConfirmation.kt         // 三影格一致性驗證機制
│   ├── service/                         // 核心背景定位服務
│   │   ├── MockLocationEngine.kt        // Android LocationManager 底層注入 (含 Jitter)
│   │   ├── MockLocationService.kt       // 前台常駐服務 (Foreground Service)
│   │   └── SessionGate.kt               // 服務啟停原子防護
│   ├── ui/                              // Jetpack Compose 使用者介面
│   │   ├── MainActivity.kt              // 主畫面進入點與狀態聯動
│   │   ├── viewmodel/MainViewModel.kt   // UI State 狀態管理與業務邏輯
│   │   ├── components/                  // Compose UI 元件
│   │   │   ├── DroneScannerDialog.kt    // 無人機巡航與掃描控制彈窗
│   │   │   ├── FavoritesSheet.kt        // 常用地點收藏底部抽屜
│   │   │   ├── HistorySheet.kt          // 歷史傳送紀錄底部抽屜
│   │   │   ├── InputCoordinatesDialog.kt// 座標精確輸入彈窗
│   │   │   ├── LocationSearchBar.kt     // 地名/地址關鍵字搜尋欄
│   │   │   ├── MapViewContainer.kt      // osmdroid Compose 封裝與地圖圖層
│   │   │   └── UpdateDialog.kt          // 版本升級提示對話框
│   │   └── theme/                       // Material 3 主題、色彩與字體配置
│   ├── update/                          // 版本檢查與安全更新
│   │   ├── AppUpdateManager.kt          // GitHub Release API 檢查與下載器
│   │   ├── DownloadIntegrity.kt         // SHA-256 雜湊與套件同源驗證
│   │   ├── UpdateModel.kt               // 版本資訊資料結構
│   │   └── VersionOrder.kt              // 語意化版本大小比對演算法
│   └── utils/                           // 通用工具類
│       ├── ExtractedCoordinate.kt       // 剪貼簿群組文字智慧過濾正規表達式
│       ├── GeoUtils.kt                  // 球面大圓公式、方位角與距離航位推算
│       ├── GoogleMapTileSources.kt      // Google 衛星/街道/地形圖資定義
│       ├── MapType.kt                   // 圖資模式列舉
│       └── PermissionHelper.kt          // 系統權限與開發者選項檢測
```

---

## 🛠️ 開發與驗證

- **主要開發與編譯路徑**：`D:\2026PIKMIN`
- **JDK 版本**：JDK 17 (`C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot`)

```powershell
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot'
.\gradlew.bat assembleDebug testDebugUnitTest lintDebug
```

完整修正細節、實機驗收記錄與更新規範請參閱 [docs/VERIFICATION.md](docs/VERIFICATION.md) 及 [docs/CRUISE-VERIFICATION-2026-09-06.md](docs/CRUISE-VERIFICATION-2026-09-06.md)。

> [!NOTE]
> 依照專案規範，日常開發與文件調整僅提交一般 Commit；只有在使用者明確確認需要發布新版本時，才會執行 `release.ps1` 推進版本號並發布 GitHub Release。

---

## ⚠️ 免責聲明與使用注意事項

1. 本專案僅供 Android 定位系統架構研究、電腦視覺色彩辨識技術驗證與個人學術交流使用。
2. 使用模擬定位可能違反部分第三方遊戲或服務的服務條款（Terms of Service）。使用本工具所產生之任何風險、帳號處分或爭議，均由使用者自行承擔。
3. 畫面色彩辨識依賴特定遊戲介面色彩特徵，在不同的手機螢幕解析度、色彩風格設定或遊戲天候/季節地圖變化下可能有所差異。
