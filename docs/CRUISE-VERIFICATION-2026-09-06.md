# 巡航與大型元素蘑菇診斷（本地修正，未發布）

## 已確認

- vivo V2436A 的螢幕逾時設定為 60 秒。舊程式使用 SCREEN_DIM_WAKE_LOCK，允許變暗，並在 30 分鐘後到期。
- 改為 SCREEN_BRIGHT_WAKE_LOCK，巡航中每 60 秒續租 120 秒；停止、完成、錯誤時取消續租並釋放。此 API 已棄用，但遊戲在前景時，僅對本 APP Activity 設定 KEEP_SCREEN_ON 無法保亮遊戲。仍需確認裝置省電策略是否遵守喚醒鎖。
- 航點原本會在固定截止時間移動，即使已有尚待三幀確認的候選。改為有候選或不足三幀時，最多延長 3 秒，保持原本三個不同新畫面的確認要求。
- 增加航點、分析幀數、候選類型及確認結果的 Logcat 診斷，未記錄截圖或座標。
- 五張實機參考圖在原始解析度及 720px 巡航解析度均通過：LARGE_ELECTRIC、LARGE_POISON、LARGE_FIRE、LARGE_WATER、LARGE_CRYSTAL。
- 水菇實機截圖 1172×2748 經 Android 上的 MushroomDetector 分析，得到 LARGE_WATER，位置 (536,1275)，半徑 114，色票純度 0.9409222。此數值不是辨識正確率；大小仍為未知。
- 本地 Debug 編譯、23 項 JVM 測試及 Lint 通過。Android 實機 7 項測試通過，其中包含五種大型元素蘑菇在原始解析度及 720px 巡航解析度的辨識。
- Android 14+ 授權保留系統選擇器，讓使用者明確選擇 PIKMIN；授權完成後 APP 也會主動開啟 `com.nianticlabs.pikmin`，避免選擇整個螢幕時留在工具畫面。
- 增加連續黑畫面防護：開始時即為黑畫面，或巡航中連續三幀黑畫面，會停止並提示重新授權整個螢幕。
- vivo Android 16 的 `disable_screen_share_protections_for_apps_and_notifications` 為 0 時，Pikmin 畫面會被系統分享保護遮黑。測試裝置切為 1 並重新授權後，MediaProjection 已可取得實際遊戲畫面。
- 實機串流在第 1 個航點連續三個新畫面偵測到 `LARGE_POISON` 後確認，狀態切為 `FOUND` 並停止巡航。8 秒後航點仍為 1、座標不變；MediaProjection 與螢幕保亮鎖均已釋放，Pikmin 保持前景。
- 實機截圖中的紫色斑點、藍綠底座大型毒蘑菇與偵測類型一致。證據保存在 `app/build/cruise-found-large-poison.png`（建置產物，不納入版本控制）。

## 尚待驗證

- 已通過遊戲前景 92 秒、跨過 60 秒螢幕逾時的保亮；仍待超過 30 分鐘驗證。
- 不同距離、縮放、遮擋與地圖背景下的五種大型元素蘑菇召回率；目前每種類型各有一張參考圖，且實際串流只確認過大型毒蘑菇，不能據此宣稱所有場景皆能辨識。

本次不修改色域或降低面積門檻，以免在沒有漏判樣本證據時增加河流、地形等誤判。
