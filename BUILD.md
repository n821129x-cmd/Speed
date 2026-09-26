# 怎麼把這包原始碼變成 APK

三條路，依你的狀況挑一條就好。

---

## 方法 A：Android Studio（最穩，推薦第一次用這個）

**需要：** 約 10 GB 硬碟空間、穩定網路（第一次要下載 SDK）

1. 下載安裝 Android Studio：https://developer.android.com/studio
   安裝精靈會問要不要下載 Android SDK，全部按預設同意即可。
2. 解壓 `speedcam-android.zip`。
3. Android Studio → `File` → `Open`，選擇 **`speedcam` 這層資料夾**
   （裡面要看得到 `settings.gradle.kts`，選錯層會開不起來）。
4. 右下角會跳「Gradle sync」進度條，第一次約 3~10 分鐘，它會自動下載
   Gradle 8.x、Android SDK 34、build-tools。
   - 若跳出 `Install missing SDK` 或授權條款，按 `Accept` → `Next`。
   - 若卡在下載，設定 Proxy 或換網路，公司網路常擋 `dl.google.com`。
5. 上方選單 `Build` → `Build Bundle(s) / APK(s)` → `Build APK(s)`。
6. 右下角出現 `APK(s) generated successfully` 通知，按 `locate` 開啟資料夾。
   檔案在：
   ```
   speedcam/app/build/outputs/apk/debug/app-debug.apk
   ```

### 裝到手機
- **有線**：手機開「開發人員選項 → USB 偵錯」，接上電腦，Android Studio 左上角
  選到你的手機，按綠色 ▶ 直接安裝執行。
- **無線**：把 `app-debug.apk` 用雲端硬碟或 LINE 傳到手機，點開安裝。
  Android 會擋，要在跳出的視窗按「設定」→ 允許這個來源安裝應用程式。

---

## 方法 B：GitHub Actions（電腦什麼都不用裝）

專案裡已經放好 `.github/workflows/build-apk.yml`。

1. 在 GitHub 建一個 repo（設 Private 也可以）。
2. 把整個 `speedcam` 資料夾內容推上去：
   ```bash
   cd speedcam
   git init
   git add .
   git commit -m "init"
   git branch -M main
   git remote add origin https://github.com/<你的帳號>/<repo名>.git
   git push -u origin main
   ```
3. 到 repo 的 `Actions` 分頁，會看到 Build APK 正在跑，約 5 分鐘。
4. 跑完點進該次執行，最下方 `Artifacts` 區塊下載 `speedcam-debug-apk.zip`，
   解壓就是 APK，直接傳到手機安裝。

之後每次改程式 push，APK 就自動重編一份。免費額度對私人專案綽綽有餘。

---

## 方法 C：純命令列（已有 JDK 與 Android SDK）

```bash
# 1. 確認 JDK 17
java -version

# 2. 設定 SDK 路徑（擇一）
export ANDROID_HOME=$HOME/Android/Sdk          # Linux
export ANDROID_HOME=$HOME/Library/Android/sdk  # macOS

# 3. 安裝需要的元件
sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0"
sdkmanager --licenses            # 一路按 y

# 4. 編譯
cd speedcam
gradle wrapper --gradle-version 8.7   # 只需第一次
./gradlew assembleDebug

# 5. 安裝到已連線的手機
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

沒有 SDK 的話，下載 command line tools 即可，不必裝整套 Android Studio：
https://developer.android.com/studio#command-line-tools-only

---

## Debug 版 vs Release 版

上面產出的是 **debug APK**，用系統內建的除錯金鑰簽章。可以正常安裝使用，
但不能上架 Play 商店，也不建議長期散布。

要做正式版：

```bash
# 產生金鑰（妥善保管，遺失就無法更新已發佈的 App）
keytool -genkey -v -keystore speedcam.jks -keyalg RSA \
        -keysize 2048 -validity 10000 -alias speedcam
```

在 `app/build.gradle.kts` 的 `android { }` 內加上：

```kotlin
signingConfigs {
    create("release") {
        storeFile = file("../speedcam.jks")
        storePassword = System.getenv("KS_PASS")
        keyAlias = "speedcam"
        keyPassword = System.getenv("KEY_PASS")
    }
}
buildTypes {
    release {
        signingConfig = signingConfigs.getByName("release")
        isMinifyEnabled = false
    }
}
```

然後 `./gradlew assembleRelease`，輸出在
`app/build/outputs/apk/release/app-release.apk`。

---

## 常見錯誤

| 訊息 | 原因與解法 |
|---|---|
| `SDK location not found` | 建立 `local.properties`，內容 `sdk.dir=/你的/Android/Sdk` |
| `Failed to find Build Tools revision 34.0.0` | `sdkmanager "build-tools;34.0.0"` |
| `Unsupported class file major version` | JDK 版本不對，必須是 17 |
| Gradle 下載一直失敗 | 公司網路擋 `dl.google.com` / `services.gradle.org`，換網路或設 Proxy |
| 手機顯示「應用程式未安裝」 | 之前裝過同名但不同簽章的版本，先解除安裝舊的 |
| 安裝後閃退 | 多半是 `cameras.csv` 格式問題，先用 `adb logcat` 看錯誤 |
