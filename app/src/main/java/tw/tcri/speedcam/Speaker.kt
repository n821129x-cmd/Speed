package tw.tcri.speedcam

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * 語音播報器。
 *
 * 優先使用 Google 語音服務（com.google.android.tts）——它的中文（臺灣）
 * 語音自然度遠優於各家 ROM 內建引擎（三星、小米自帶的中文常常是機械音或根本沒裝）。
 * 找不到或初始化失敗時，自動退回系統預設引擎。
 *
 * 音訊屬性設為 USAGE_ASSISTANCE_NAVIGATION_GUIDANCE，行為與導航語音一致：
 * 播報時只會把音樂「壓低」而不是整首停掉，藍牙車機也會走導航聲道。
 */
class Speaker(private val ctx: Context) {

    companion object {
        const val GOOGLE_TTS = "com.google.android.tts"
    }

    private var tts: TextToSpeech? = null
    var engineInUse: String = "初始化中"; private set
    var ready = false; private set

    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    fun init(onReady: (() -> Unit)? = null) {
        if (isInstalled(GOOGLE_TTS)) {
            tts = TextToSpeech(ctx, { st ->
                if (st == TextToSpeech.SUCCESS && configure()) {
                    engineInUse = "Google 語音服務"
                    ready = true
                    onReady?.invoke()
                } else {
                    tts?.shutdown()
                    initDefault(onReady)
                }
            }, GOOGLE_TTS)
        } else {
            initDefault(onReady)
        }
    }

    private fun initDefault(onReady: (() -> Unit)?) {
        tts = TextToSpeech(ctx) { st ->
            if (st == TextToSpeech.SUCCESS && configure()) {
                engineInUse = tts?.defaultEngine ?: "系統預設引擎"
                ready = true
                onReady?.invoke()
            } else {
                engineInUse = "無可用語音引擎"
                ready = false
            }
        }
    }

    /** 設定語言與音訊屬性；中文不可用就回 false */
    private fun configure(): Boolean {
        val t = tts ?: return false
        t.setAudioAttributes(attrs)
        val r = t.setLanguage(Locale.TAIWAN)
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            if (t.setLanguage(Locale.CHINESE) == TextToSpeech.LANG_NOT_SUPPORTED) return false
        }
        t.setSpeechRate(1.1f)   // 行車中稍快一點比較不擋事
        t.setPitch(1.0f)
        return true
    }

    private fun isInstalled(pkg: String): Boolean = try {
        ctx.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (e: Exception) {
        false
    }

    /** urgent = true 會插隊蓋掉正在播的內容（超速警告用） */
    fun say(text: String, urgent: Boolean = false) {
        if (!ready) return
        tts?.speak(
            text,
            if (urgent) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD,
            null,
            if (urgent) "urgent" else "normal"
        )
    }

    fun shutdown() {
        tts?.stop(); tts?.shutdown(); tts = null; ready = false
    }

    /** 引導使用者去 Play 商店安裝 Google 語音服務 */
    fun installGoogleTtsIntent(): Intent =
        Intent(Intent.ACTION_VIEW).apply {
            data = android.net.Uri.parse("market://details?id=$GOOGLE_TTS")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    /** 開啟系統「文字轉語音輸出」設定頁（可切換引擎、下載語音包） */
    fun ttsSettingsIntent(): Intent =
        Intent("com.android.settings.TTS_SETTINGS").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
}
