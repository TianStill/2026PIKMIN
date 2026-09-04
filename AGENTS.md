# Project Guidelines & Rules

## 1. 版本發布規則 (Strict Release Policy)
- **不可自動發布新版本**：平時進行程式碼調整、除錯、UI 調整或功能修改時，僅進行本地編譯驗證及一般程式碼變更。
- **發布前必須先詢問使用者**：除非使用者有明確指示「發布新版本」或「更新 Release」，否則每次修改完成後**必須先詢問使用者是否需要發布新版本 (Release)**。
- 只有在使用者確認需要發布新版本時，才可執行 `release.ps1` 推進版本號、打 Tag 並發布 GitHub Release。

## 2. 環境與路徑注意事項
- **主要開發與編譯路徑**：`D:\2026PIKMIN`（純英數字路徑，避免 Android Gradle Plugin 非 ASCII 路徑警示）。
- **JDK 17 設定**：
  `$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot"`
- **工具路徑**：
  `$env:PATH = "C:\Program Files\Git\cmd;C:\Program Files\GitHub CLI;C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot\bin;" + $env:PATH`
- **雲端同步**：修改完成後，同步複製至 `G:\我的雲端硬碟\2026PIKMIN`。
