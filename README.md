# Fake GPS Pro - Android 原生模擬定位應用程式

一款基於 **Android 原生 (Kotlin + Jetpack Compose)** 開發的虛擬定位（Fake GPS）應用程式，專為地圖定位測試、虛擬行走與遊戲模擬設計。

---

## 🌟 核心特色功能

1. **底層模擬定位引擎 (`MockLocationEngine`)**：
   - 註冊 Android 系統測試提供者 (`GPS_PROVIDER` 與 `NETWORK_PROVIDER`)。
   - 注入完整衛星訊號資訊（緯度、經度、精準度、速度、方位角、時間戳記、高度）。
   - **擬真隨機微幅飄移 (Realistic Jitter)**：內建高斯雜訊機制（$\pm 0.4\text{ m}$），模擬真實 GPS 天線的微幅訊號波動。

2. **背景常駐前台服務 (`MockLocationService`)**：
   - 透過 Android 前台服務 (`Foreground Service`) 與狀態列通知，確保切換到遊戲或其他 App 時模擬定位不中斷。

3. **全域懸浮窗虛擬搖桿 (`JoystickOverlayService`)**：
   - 支援 `SYSTEM_ALERT_WINDOW`，可在任何 App 或遊戲上層顯示懸浮控制盤。
   - 支援 360 度觸控搖桿推移，依設定速度與航向即時計算並注入下一個座標。
   - 四段速度快速切換：**步行 (4 km/h)**、**跑步 (10 km/h)**、**騎車 (25 km/h)**、**開車 (50 km/h)**。

4. **地圖與搜尋介面 (Jetpack Compose + OpenStreetMap)**：
   - 整合開源 **OpenStreetMap (osmdroid)**，免申請付費 Google Maps API Key 開箱即用。
   - 支援中文地名/地址關鍵字搜尋與經緯度直接輸入。
   - 常用地點收藏（書籤）管理。

5. **切換 APP 自動貼上執行 (`Auto-Clipboard Detection`)**：
   - **切換回 APP 自動辨識**：在任何聊天群組 (LINE/Discord) 複製座標文字後，只要切換回本 APP，系統立即自動解析、傳送並啟動定位。
   - **智慧座標過濾**：自動過濾「`60.4469650, 23.2501560 華麗 免3`」等混雜文字與地名備註。
   - **防重複與切換開關**：具備防重複觸發機制，並提供專屬開關按鈕與懸浮窗快速貼上按鈕（📋）。

---

## 📱 使用與設定指引

### 步驟 1：開啟手機「開發人員選項」並指定模擬 App
1. 進入手機 **「設定」** $\to$ **「關於手機」** $\to$ 連續點擊 **「版本號碼」** 7 次直到提示開啟開發人員模式。
2. 返回設定，進入 **「系統」** $\to$ **「開發人員選項」**。
3. 找到 **「選取模擬位置資訊應用程式」**（Select mock location app），點選並指定為 **「Fake GPS Pro」**。

### 步驟 2：開啟懸浮窗權限（搖桿功能需要）
* 首次點擊搖桿按鈕時，App 會引導前往系統設定開啟 **「顯示在其他應用程式上層」** 權限。

### 步驟 3：開始使用
1. 在地圖上拖曳準心或搜尋目的地。
2. 點擊右下角綠色 **「開始模擬」** 按鈕。
3. 點擊右側手把圖示開啟 **「懸浮搖桿」**。
4. 切換至目標應用程式（例如 Pikmin Bloom / 寶可夢 / 地圖導航），即可透過懸浮搖桿即時控制移動！

---

## 📂 專案架構概覽

```
app/src/main/
├── AndroidManifest.xml                  // 系統權限與 Service 宣告
├── java/com/pikmin/fakegps/
│   ├── FakeGpsApplication.kt            // 全域 Application (通知管道與 OSM 設定)
│   ├── data/
│   │   ├── model/LocationPoint.kt       // 座標與書籤模型
│   │   ├── model/MovementMode.kt        // 移動速度模式
│   │   └── repository/PreferencesRepo.kt// 偏好設定與書籤儲存
│   ├── service/
│   │   ├── MockLocationEngine.kt        // 核心 LocationManager 注入邏輯
│   │   ├── MockLocationService.kt       // 前台常駐服務 (Notification)
│   │   └── JoystickOverlayService.kt    // WindowManager 懸浮窗搖桿
│   ├── utils/
│   │   ├── GeoUtils.kt                  // 球面航位推算、方位角與距離演算法
│   │   └── PermissionHelper.kt          // 權限與開發者選項檢測
│   └── ui/
│       ├── MainActivity.kt              // 主畫面進入點 (Jetpack Compose)
│       ├── viewmodel/MainViewModel.kt   // UI 狀態與服務控制器
│       ├── components/
│       │   ├── MapViewContainer.kt      // osmdroid Compose 封裝
│       │   ├── SpeedControlBar.kt       // 速度模式選擇器
│       │   ├── LocationSearchBar.kt     // 地名與經緯度搜尋欄
│       │   └── FavoritesSheet.kt        // 常用地點收藏抽屜
│       └── theme/                       // Material 3 主題配置
```

---

## 🛠️ 編譯與建置 (Build & Run)

1. 使用 **Android Studio (Giraffe / Hedgehog / Iguana / Jellyfish 或更新版本)** 開啟此專案資料夾 `2026PIKMIN`。
2. 等待 Gradle Sync 完成。
3. 連接 Android 實體裝置 (開啟 USB 偵錯) 或啟動 Android 模擬器。
4. 點擊 Android Studio 上方綠色的 **Run (Shift + F10)** 即可安裝執行。
