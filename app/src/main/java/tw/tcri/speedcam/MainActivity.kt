package tw.tcri.speedcam

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var tvSpeed: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvNearest: TextView
    private lateinit var tvData: TextView
    private lateinit var etUrl: EditText

    private val PREF = "speedcam"
    private val KEY_URL = "url"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvSpeed = findViewById(R.id.tvSpeed)
        tvStatus = findViewById(R.id.tvStatus)
        tvNearest = findViewById(R.id.tvNearest)
        tvData = findViewById(R.id.tvData)
        etUrl = findViewById(R.id.etUrl)

        val sp = getSharedPreferences(PREF, MODE_PRIVATE)
        etUrl.setText(sp.getString(KEY_URL, ""))

        CameraRepo.load(this)
        showDataInfo()

        findViewById<Button>(R.id.btnStart).setOnClickListener { ensurePermissionsThenStart() }

        findViewById<Button>(R.id.btnStop).setOnClickListener {
            stopService(Intent(this, SpeedService::class.java))
            tvStatus.text = "已停止"
            tvNearest.text = ""
            tvSpeed.text = "--"
        }

        findViewById<Button>(R.id.btnOverlay).setOnClickListener {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        }

        findViewById<Button>(R.id.btnUpdate).setOnClickListener {
            val url = etUrl.text.toString().trim()
            if (url.isEmpty()) { toast("請先填入 CSV 網址"); return@setOnClickListener }
            sp.edit().putString(KEY_URL, url).apply()
            tvData.text = "下載中…"
            thread {
                val msg = try {
                    val n = CameraRepo.update(this, url)
                    "更新完成，共 $n 點"
                } catch (e: Exception) {
                    "更新失敗：${e.message}"
                }
                runOnUiThread { tvData.text = msg; showDataInfo() }
            }
        }

        findViewById<Button>(R.id.btnTts).setOnClickListener { openTtsSetup() }

        SpeedService.listener = { speed, status, near, over ->
            runOnUiThread {
                tvSpeed.text = speed.toString()
                tvStatus.text = status
                tvNearest.text = near
                tvSpeed.setTextColor(
                    android.graphics.Color.parseColor(if (over) "#FF3B30" else "#33C1FF")
                )
            }
        }
    }

    override fun onDestroy() {
        SpeedService.listener = null
        super.onDestroy()
    }

    /** 沒裝 Google 語音服務就導去商店，裝了就開系統語音設定頁下載/切換語音包 */
    private fun openTtsSetup() {
        val sp = Speaker(this)
        val installed = try {
            packageManager.getPackageInfo(Speaker.GOOGLE_TTS, 0); true
        } catch (e: Exception) { false }
        val intent = if (installed) sp.ttsSettingsIntent() else sp.installGoogleTtsIntent()
        try {
            startActivity(intent)
        } catch (e: Exception) {
            toast("找不到設定頁，請手動至 設定→系統→語言與輸入→文字轉語音輸出")
        }
    }

    private fun showDataInfo() {
        tvData.text = "目前載入 ${CameraRepo.count} 個測速點（${CameraRepo.sourceNote}）"
    }

    private fun ensurePermissionsThenStart() {
        val need = ArrayList<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) need.add(Manifest.permission.ACCESS_FINE_LOCATION)

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) need.add(Manifest.permission.POST_NOTIFICATIONS)

        if (need.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, need.toTypedArray(), 100)
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            toast("尚未授權懸浮視窗，僅會在 App 內顯示")
        }
        ContextCompat.startForegroundService(this, Intent(this, SpeedService::class.java))
        tvStatus.text = "等待 GPS 訊號…（請到戶外或車上）"
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                ensurePermissionsThenStart()
            } else toast("需要定位權限才能運作")
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
