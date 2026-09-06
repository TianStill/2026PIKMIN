# 可靠性修正與驗收

本文件記錄程式碼修正、可重跑的驗證，以及目前無法以本機測試證實的功能。

## 改善順序與完成範圍

| 順序 | 已實作 | 尚待外部驗收 |
| --- | --- | --- |
| 1 | 定位服務統一啟停、停止使排隊啟動失效、航點更新不能重啟服務 | 真機連按開始/停止、系統通知停止 |
| 1 | 授權後啟動擷取；取消授權不巡航；影格過期或不足時停止；續航重新授權 | Android 10–16 擷取、鎖屏、旋轉、單一 APP 分享 |
| 1 | 停止/錯誤/完成釋放 ImageReader、MediaProjection、執行緒、WakeLock；服務銷毀取消 scope | 30–60 分鐘記憶體及耗電量測 |
| 1 | 還原抖動設定；持久化活躍定位以處理 START_STICKY；注入錯誤回報 UI | 系統殺程序後的服務重建，不包含使用者 force-stop |
| 2 | 三張不同影格確認、連通邊界與大色塊處理、大小標示未知、符合度不稱機率 | 已標註真實截圖的 precision/recall 與誤停率 |
| 2 | 發現目標停在觀測航點，估算座標不自動跳轉；移除未校正地理去重 | 螢幕尺度/透視校正及座標誤差量測 |
| 2 | 歷史與收藏即時刷新、定位狀態同步、搜尋取消/HTTP 錯誤、zoom 儲存、地圖生命週期 | 真機拖曳準心、小螢幕、大字體 |
| 3 | 更新大小、可用 SHA-256、套件、版本、同簽章驗證；下載進度通知；由前景 UI 安裝 | 真機未知來源授權與不同簽章拒絕 |
| 3 | 正式簽章環境設定、發布前測試/lint、乾淨工作目錄、只提交版本檔、失敗回復 | 提供正式金鑰及簽章遷移決策後才能簽署發布 |

## 已取得的驗證結果（2026-09-06）

- JVM 單元測試：22 項，0 失敗、0 錯誤、0 跳過。
- Debug APK：建置通過。
- Release APK：未簽署建置通過；未提供正式金鑰，未發布。
- Android instrumentation APK：編譯通過，因 adb 沒有連線裝置，未執行。
- Android lint：0 errors、64 warnings、4 hints。仍有依賴更新、未使用資源與 API/風格建議；沒有以 baseline 隱藏錯誤。
- release.ps1：PowerShell 語法解析通過；未執行發布腳本。

## 本機檢查

```powershell
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot'
.\gradlew.bat assembleDebug testDebugUnitTest lintDebug assembleDebugAndroidTest
```

JVM 測試涵蓋停止使舊操作失效、影格確認/失聯、語意化版本、下載完整性、螺旋航點與經度跨日界線、座標解析、抖動半徑。
Android 測試包含草地背景、單色候選大小未知、整片紅色背景排除。這些測試是演算法回歸檢查，不代表遊戲辨識準確率。

## 真實截圖評測入口

將有授權的遊戲截圖與人工標註放在 `app/src/androidTest/assets/cv-corpus/`。沒有資料集時，`evaluateLabelledCorpus` 會回報 skipped，不會假裝驗證成功。
`manifest.json` 格式如下，box 為原圖像素座標 `[left, top, right, bottom]`：

```json
[
  {"image":"red-example.png", "targets":[{"type":"LARGE_RED", "box":[100,200,180,260]}]},
  {"image":"negative-example.png", "targets":[]}
]
```

在 Android 裝置/模擬器執行 `gradlew.bat connectedDebugAndroidTest`。
結果寫入目標 APP 的 external files `cv-evaluation.json`，包含每張圖片與總計 TP/FP/FN、precision、recall。採種類相同且預測中心落在人工框內的一對一配對。背景、近遠距離、每種菇、各縮放/裝置比例需要各自涵蓋；以未用於調整閾值的圖片驗收。

目前未提供真實截圖，不能報告準確率；未做螢幕比例/透視實測，不能報告公尺誤差。估算仍假設畫面寬度 450m、玩家 y=58%、正北，介面明確標示未校正。無法可靠判斷同一實體時，不會按 180m/50m 範圍刪除其他候選，可能重複提醒相鄰航點看到的同一菇。

## 真機驗收清單

1. 開始定位、啟動掃描、按主畫面或通知「停止全部」，至少等待兩個航點週期，確認位置與服務都不再重啟。
2. 拒絕定位權限、拒絕擷取授權、撤銷擷取、鎖屏、轉向；確認不繼續無畫面巡航，不顯示「分析完成」。
3. 取消巡航保持目前定位；重新授權後從中斷航點繼續；發現候選後從下一航點繼續。
4. 關閉抖動後停止並重新啟動；驗證實際注入無抖動。系統回收服務後恢復最後有效定位，手動停止不恢復。
5. 巡航至少 30 分鐘，記錄 profiler 的記憶體、GC、CPU、電量；完成/暫停後擷取指示與工作執行緒消失。
6. 搜尋 A 緊接搜尋 B、清除搜尋、斷網；只显示最後一次查詢結果，失敗有提示。
7. 背景發現目標後返回主畫面，確認觀測航點、歷史標記一致；回到真實位置不接受 mock 或超過兩分鐘的舊位置。
8. 更新過程斷網、截斷 APK、錯誤 hash、錯誤套件、較舊版本、不同簽章，確認不進入安裝；授予未知來源後返回按安裝可重試。

## 發布設定與遷移

透過環境變數提供 `PIKMIN_RELEASE_STORE_FILE`、`PIKMIN_RELEASE_STORE_PASSWORD`、`PIKMIN_RELEASE_KEY_ALIAS`、`PIKMIN_RELEASE_KEY_PASSWORD`。金鑰及密碼不得提交版本控制。本次沒有產生或替換任何金鑰。

既有版本由同一把 Android Debug 憑證簽署。為維持舊版直接覆蓋安裝能力，後續版本在完成簽章遷移前必須沿用這把既有憑證。換成新正式 key 會讓舊版無法直接更新；發布前需另行規劃簽章與使用者收藏、歷史的遷移，不能關閉 APK 簽章驗證來迴避問題。只有使用者明確批准後才能執行 `release.ps1 -ConfirmRelease`。

腳本先要求乾淨 main 且不落後遠端，再修改版本，執行測試/lint/assembleRelease，僅提交版本檔並原子推送 main 與本次 tag，發布 APK 及 SHA-256 附件。提交前失敗還原版本；提交後若推送/發布失敗會中止並保留狀態供人工處理，不覆蓋已存在 Release。
