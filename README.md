# 測速提醒 App（Android 原生 / Kotlin）

GPS 即時時速顯示 + 測速照相接近語音警示 + 可拖曳懸浮視窗。
無任何第三方相依套件（不需 Google Play Services、不需 Maps SDK），
只用 Android 內建的 `LocationManager` 與 `TextToSpeech`。

---

## 一、如何編譯出 APK

1. 安裝 Android Studio（Koala 2024.1 以上）。
2. `File → Open` 選擇本資料夾 `speedcam`，等待 Gradle 同步（首次會自動下載 SDK 與 Gradle Wrapper）。
3. 產生可安裝的測試檔：
   `Build → Build Bundle(s) / APK(s) → Build APK(s)`
   輸出位置：`app/build/outputs/apk/debug/app-debug.apk`
4. 手機開啟「允許安裝未知來源」後直接安裝；或用 `adb install app-debug.apk`。

命令列版本（已安裝 Android SDK 並設好 `ANDROID_HOME`）：

```bash
cd speedcam
gradle wrapper          # 第一次執行，產生 gradlew
./gradlew assembleDebug
```

> 正式發佈版需自行建立 keystore 後 `./gradlew assembleRelease`。

---

## 二、首次使用要給的權限

| 權限 | 用途 | 授權方式 |
|---|---|---|
| 精確位置 | 取得座標與時速 | 開啟 App 後彈窗 |
| 通知 | 前景服務常駐（Android 13+ 必要） | 開啟 App 後彈窗 |
| 懸浮視窗 | 在其他 App 上方顯示時速 | 按「開啟懸浮視窗權限」跳系統設定 |
| 電池最佳化排除 | 避免鎖屏後被殺掉 | 手動到 設定→電池→App 耗電管理 設為「不受限制」 |

---

## 三、測速點資料

內建 `app/src/main/assets/cameras.csv` **只是格式示範的假資料**，務必換成真實資料。

### 資料來源
- 警政署「測速執法設置點」：https://data.gov.tw/dataset/7320
- 警政署「國道公路固定式測速照相地點」：https://data.gov.tw/dataset/13940 （含經緯度、拍攝方向、速限，品質最好）
- 新北市開放資料 API：https://data.ntpc.gov.tw/api/v1/openapi/units/1250000
- 各縣市警察局資料集（臺北、桃園、臺中、高雄等）

### CSV 欄位辨識規則
解析器用「欄位名稱包含關鍵字」來對應，所以政府原始 CSV 大多可直接丟進去：

| 程式需要 | 會辨識的欄位名稱（擇一） | 必要 |
|---|---|---|
| 緯度 | `座標緯度`、`緯度`、`lat` | ✅ |
| 經度 | `座標經度`、`經度`、`lon`、`lng` | ✅ |
| 地點 | `設置地點`、`設置位置`、`地點`、`location` | |
| 速限 | `速限`、`limit`、`speed` | |
| 方向 | `拍攝方向`、`取締方向`、`方向` | |

- 方向可吃 `南往北`、`往東`、`東北`、或數字方位角（0~360）。看不懂就不做方向過濾。
- 自動處理 Big5(MS950) 與 UTF-8 BOM。
- 自動濾掉座標不在台澎金馬範圍的髒資料。

### 線上更新
在 App 下方「資料來源 CSV 網址」貼上一個可直接下載 CSV 的網址，按「更新測速點資料」。
下載成功才會覆蓋本機檔，失敗會保留舊資料。

> 警政署那份全國資料常常缺經緯度（只有文字地點描述），必須先自行地理編碼再上傳到你自己的空間（GitHub Raw、Google Cloud Storage 都可以）。

---

## 三之二、用 tools/build_cameras.py 產生資料

```bash
cd tools
mkdir raw                      # 把各機關下載的 csv / json 全部丟進去
python build_cameras.py                 # 只做合併與清理
python build_cameras.py --geocode       # 對缺座標者跑地理編碼（1 req/sec，很慢）
```

產出：
- `cameras.csv` — 直接覆蓋 `app/src/main/assets/cameras.csv`，或上傳到 GitHub Raw 給 App 線上更新
- `unresolved.csv` — 缺座標、需人工補的清單
- `geocode_cache.json` — 地理編碼快取，**不要刪**，下次跑可省下大量請求

腳本會做的事：
1. 編碼自動判斷（UTF-8 / BOM / Big5-CP950）
2. 欄位名稱模糊比對，各縣市不同命名都吃得下
3. **TWD97 二度分帶自動轉 WGS84**（純 Python 實作，不需安裝 pyproj）——很多縣市資料給的是投影座標而非經緯度
4. 方向文字正規化：`南往北`→`0`、`往東`→`90`
5. 台澎金馬範圍外的髒資料自動剔除
6. 去重：40 公尺內且方向差 45° 內視為同一支桿，合併時互補缺漏欄位

地理編碼預設用 Nominatim（免費但對「國道1號北向52K」這種里程描述幾乎無效）。
實務上建議：國道類用交通部里程樁座標表對照，市區地址型才丟地理編碼。
腳本已預留 `--tgos-key` 參數，之後接內政部 TGOS 定位服務準確度會高很多。

---

## 四、警示邏輯（`SpeedService.onLocationChanged`）

1. **網格索引**：以 0.01°（約 1.1 km）分格，每秒只掃周圍 3×3 格，全台上萬點也不卡。
2. **動態預警距離**：`max(250 m, 時速 × 8)`。時速 100 → 800 m、時速 40 → 320 m。
3. **前方判定**：車速 > 15 km/h 時，測速點必須落在車頭方向 ±55° 內，避免剛過的點又叫。
4. **取締方向判定**：資料有方向時，行進方位角須與取締方向差 ±60° 內，避免對向誤報。
5. **一次性提醒**：每個點在一次接近中只響一次，離開 1.8 倍預警距離後重置。
6. **接近提示**：單聲嗶 + 語音「前方 500 公尺 測速照相，速限 60」。
7. **超速警告**（獨立於接近提示）：在預警範圍內且時速 > 速限 + 3 km/h 容許值時觸發，
   雙聲急促嗶 + 震動 + 插隊語音「超速，目前時速 78，速限 60，請減速」，
   每 8 秒重複直到減速；懸浮視窗數字同步每秒閃紅。

### 語音引擎（Speaker.kt）

優先使用 **Google 語音服務 `com.google.android.tts`**，中文（臺灣）自然度遠優於各家 ROM
內建引擎——三星、小米自帶的中文常常是機械音，部分機型根本沒裝中文資料。
找不到或初始化失敗會自動退回系統預設引擎，主畫面狀態列會顯示目前實際使用哪一個。

三個關鍵設定：
- `AndroidManifest.xml` 的 `<queries>` 必須宣告 `TTS_SERVICE` 與 `com.google.android.tts`，
  否則 Android 11 以上查不到這個套件，一定會退回系統引擎。
- `AudioAttributes` 用 `USAGE_ASSISTANCE_NAVIGATION_GUIDANCE`，行為與導航語音一致：
  播報時只把音樂壓低不會整首停掉，接藍牙車機會走導航聲道。
- 超速警告用 `QUEUE_FLUSH` 插隊，蓋掉正在播的接近提示，避免警告排在隊伍後面。

App 內「語音引擎設定 / 安裝 Google 語音」按鈕：沒裝就導去 Play 商店，
裝了就開系統語音設定頁，可在那裡下載更高品質的中文語音包並設為預設引擎。

要調整靈敏度改這幾個常數即可（都在 `onLocationChanged` 裡）。

---

## 五、已知限制

- `Location.getSpeed()` 是 GPS 都卜勒速度，靜止時會有 1~3 km/h 漂移；隧道、高架橋下會失準。
- 只支援固定式測速點，移動式（手持、車載）本質上無法預測。
- Google 語音服務在無 GMS 的機型（部分 Redmi 海外版、鴻蒙）無法安裝，只能用系統引擎。
- Android 各家 ROM（小米、OPPO、vivo）背景管理兇，需手動加白名單否則鎖屏會斷線。
- 沒有做地圖顯示。要加的話建議用 osmdroid（免金鑰）而非 Google Maps SDK。

---

## 六、後續可加的功能

- 超速時懸浮視窗閃紅 + 震動（`updateOverlay` 已預留顏色切換）
- 行車記錄整合、路線速度統計
- 以 SQLite + R-tree 取代記憶體網格（資料量超過 10 萬點時才需要）
- 依速限自動切換提醒距離（國道 vs 市區）
