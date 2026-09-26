package tw.tcri.speedcam

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import kotlin.math.max
import kotlin.math.roundToInt

class SpeedService : Service(), LocationListener {

    companion object {
        const val CH_ID = "speedcam"

        /** 超速容許值：GPS 誤差 + 儀表誤差，低於此值不叫 */
        const val TOLERANCE_KMH = 3

        /** 超速持續中，每隔多久再唸一次 */
        const val OVERSPEED_REPEAT_MS = 8000L

        var running = false; private set
        var ttsEngineName = ""; private set

        /** 給 Activity 顯示用：速度 km/h、狀態文字、最近測速點文字、是否超速 */
        var listener: ((Int, String, String, Boolean) -> Unit)? = null
    }

    private lateinit var lm: LocationManager
    private lateinit var speaker: Speaker
    private var tone: ToneGenerator? = null
    private var vib: Vibrator? = null

    private var wm: WindowManager? = null
    private var overlay: View? = null
    private var ovSpeed: TextView? = null
    private var ovInfo: TextView? = null

    private val alerted = HashSet<String>()
    private var lastOverspeedSpeak = 0L
    private var overspeedActive = false
    private var flash = false

    override fun onBind(p0: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        speaker = Speaker(this)
        speaker.init { ttsEngineName = speaker.engineInUse }

        // 提示音走導航聲道，才不會被音樂蓋掉
        tone = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ToneGenerator(AudioManager.STREAM_MUSIC, 100)
        } else {
            ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100)
        }

        vib = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        createChannel()
        CameraRepo.load(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(1, buildNotification("定位中…"))
        running = true
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this)
        } catch (e: SecurityException) {
            stopSelf()
        }
        showOverlay()
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        try { lm.removeUpdates(this) } catch (_: Exception) {}
        hideOverlay()
        speaker.shutdown()
        tone?.release()
        super.onDestroy()
    }

    // ---------- 定位回呼 ----------

    override fun onLocationChanged(loc: Location) {
        val kmh = if (loc.hasSpeed()) (loc.speed * 3.6f) else 0f
        val speed = kmh.roundToInt()

        // 依速度動態調整預警距離：時速 100 → 800 m，時速 40 → 320 m，最低 250 m
        val warnDist = max(250.0, kmh * 8.0)
        val heading = if (loc.hasBearing() && kmh > 15f) loc.bearing.toDouble() else null

        var best: Cam? = null
        var bestDist = Double.MAX_VALUE

        for (cam in CameraRepo.near(loc.latitude, loc.longitude)) {
            val d = Geo.distance(loc.latitude, loc.longitude, cam.lat, cam.lon)
            if (d > warnDist * 1.8) { alerted.remove(cam.id); continue }

            if (heading != null) {
                // 必須在前方（車頭方向 ±55°）
                val toCam = Geo.bearing(loc.latitude, loc.longitude, cam.lat, cam.lon)
                if (d > 60 && Geo.angleDiff(heading, toCam) > 55.0) continue
                // 取締方向須與行進方向一致（±60°）
                val cb = cam.bearing
                if (cb != null && Geo.angleDiff(heading, cb.toDouble()) > 60.0) continue
            }
            if (d < bestDist) { bestDist = d; best = cam }
        }

        var nearText = ""
        var over = false

        if (best != null) {
            val lim = best.limit
            nearText = "${bestDist.roundToInt()} m　${best.name}" + (lim?.let { "　限速 $it" } ?: "")

            // 1. 接近提醒（每個點一次）
            if (bestDist <= warnDist && alerted.add(best.id)) {
                approachWarn(bestDist.roundToInt(), lim)
            }

            // 2. 超速警告（在預警範圍內且超過速限才啟動，會重複提醒直到減速）
            if (lim != null && bestDist <= warnDist && speed > lim + TOLERANCE_KMH) {
                over = true
                nearText += "　⚠ 超速 ${speed - lim}"
                overspeedWarn(speed, lim)
            }
        }
        if (!over) overspeedActive = false

        val status = "GPS 精度 ±${loc.accuracy.roundToInt()} m　${CameraRepo.count} 點　語音：${speaker.engineInUse}"
        updateOverlay(speed, best, bestDist, over)
        listener?.invoke(speed, status, nearText, over)
        updateNotification("$speed km/h" + if (nearText.isEmpty()) "" else "　前方 $nearText")
    }

    override fun onProviderDisabled(provider: String) {
        listener?.invoke(0, "GPS 已關閉，請開啟定位", "", false)
    }
    override fun onProviderEnabled(provider: String) {}
    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

    // ---------- 警示 ----------

    /** 接近測速點：單聲嗶 + 語音一次 */
    private fun approachWarn(dist: Int, limit: Int?) {
        tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 300)
        val msg = "前方 $dist 公尺 測速照相" + (limit?.let { "，速限 $it" } ?: "")
        speaker.say(msg)
    }

    /** 超速中：雙聲急促嗶 + 震動 + 插隊語音，每 8 秒重複 */
    private fun overspeedWarn(speed: Int, limit: Int) {
        val now = System.currentTimeMillis()
        val first = !overspeedActive
        overspeedActive = true
        if (!first && now - lastOverspeedSpeak < OVERSPEED_REPEAT_MS) return
        lastOverspeedSpeak = now

        tone?.startTone(ToneGenerator.TONE_CDMA_ABBR_ALERT, 600)
        vibrate()
        speaker.say("超速，目前時速 $speed，速限 $limit，請減速", urgent = true)
    }

    private fun vibrate() {
        val v = vib ?: return
        if (!v.hasVibrator()) return
        val pattern = longArrayOf(0, 200, 120, 200)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            v.vibrate(
                VibrationEffect.createWaveform(pattern, -1),
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .build()
            )
        } else {
            @Suppress("DEPRECATION") v.vibrate(pattern, -1)
        }
    }

    // ---------- 懸浮視窗 ----------

    private fun showOverlay() {
        if (overlay != null) return
        if (!Settings.canDrawOverlays(this)) return
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val v = LayoutInflater.from(this).inflate(R.layout.overlay_speed, null)
        ovSpeed = v.findViewById(R.id.ovSpeed)
        ovInfo = v.findViewById(R.id.ovInfo)

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 32; y = 240
        }

        // 可拖曳
        var dx = 0; var dy = 0; var tx = 0f; var ty = 0f
        v.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { dx = lp.x; dy = lp.y; tx = e.rawX; ty = e.rawY; true }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = dx + (e.rawX - tx).toInt()
                    lp.y = dy + (e.rawY - ty).toInt()
                    wm?.updateViewLayout(v, lp); true
                }
                else -> false
            }
        }
        wm?.addView(v, lp)
        overlay = v
    }

    private fun hideOverlay() {
        overlay?.let { runCatching { wm?.removeView(it) } }
        overlay = null
    }

    private fun updateOverlay(speed: Int, cam: Cam?, dist: Double, over: Boolean) {
        val sv = ovSpeed ?: return
        sv.text = speed.toString()
        flash = !flash
        sv.setTextColor(
            when {
                over && flash -> Color.parseColor("#FF3B30")   // 超速時每秒閃紅
                over -> Color.parseColor("#FFD166")
                else -> Color.parseColor("#33C1FF")
            }
        )
        ovInfo?.text = when {
            cam == null -> "km/h"
            over && cam.limit != null -> "超速 ${speed - cam.limit} / 限${cam.limit}"
            else -> "${dist.roundToInt()}m" + (cam.limit?.let { " / 限$it" } ?: "")
        }
    }

    // ---------- 通知 ----------

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CH_ID, "測速偵測", NotificationManager.IMPORTANCE_LOW)
            ch.setShowBadge(false)
            (getSystemService(NotificationManager::class.java)).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CH_ID)
            .setContentTitle("測速提醒執行中")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        (getSystemService(NotificationManager::class.java)).notify(1, buildNotification(text))
    }
}
