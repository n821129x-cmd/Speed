package tw.tcri.speedcam

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset
import kotlin.math.floor

data class Cam(
    val id: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    val limit: Int?,
    val bearing: Float?   // 取締方向（車輛行進方位角），null = 不判方向
)

/**
 * 測速點資料庫。
 * 以 0.01 度（約 1.1 km）為單位建立網格索引，查詢時只掃描周圍 3x3 格，
 * 全台數千點在手機上 1 秒查一次完全無壓力。
 */
object CameraRepo {

    private val grid = HashMap<Long, MutableList<Cam>>()
    var count: Int = 0; private set
    var sourceNote: String = "未載入"; private set

    private const val CELL = 0.01
    private const val FILE_NAME = "cameras.csv"

    private fun key(lat: Double, lon: Double): Long {
        val a = floor(lat / CELL).toLong() + 100000L
        val b = floor(lon / CELL).toLong() + 100000L
        return a * 1000000L + b
    }

    @Synchronized
    fun load(ctx: Context) {
        val local = File(ctx.filesDir, FILE_NAME)
        val bytes = if (local.exists()) {
            sourceNote = "本機更新檔"
            local.readBytes()
        } else {
            sourceNote = "內建資料"
            ctx.assets.open(FILE_NAME).use { it.readBytes() }
        }
        ingest(decode(bytes))
    }

    /** 從網路下載 CSV，成功才覆蓋本機檔並重載 */
    @Synchronized
    fun update(ctx: Context, urlStr: String): Int {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 30000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "SpeedCam/1.0")
        }
        val bytes = conn.inputStream.use { it.readBytes() }
        val text = decode(bytes)
        val parsed = parse(text)
        if (parsed.isEmpty()) throw IllegalStateException("解析後 0 筆，請確認欄位名稱")
        File(ctx.filesDir, FILE_NAME).writeBytes(text.toByteArray(Charsets.UTF_8))
        sourceNote = "本機更新檔"
        ingest(text)
        return parsed.size
    }

    private fun ingest(text: String) {
        val list = parse(text)
        grid.clear()
        for (c in list) grid.getOrPut(key(c.lat, c.lon)) { mutableListOf() }.add(c)
        count = list.size
    }

    /** 取出目前位置周圍 3x3 格內的測速點 */
    @Synchronized
    fun near(lat: Double, lon: Double): List<Cam> {
        val out = ArrayList<Cam>()
        for (dy in -1..1) for (dx in -1..1) {
            grid[key(lat + dy * CELL, lon + dx * CELL)]?.let { out.addAll(it) }
        }
        return out
    }

    // ---------- CSV ----------

    /** 政府開放資料常見 Big5(MS950) 編碼，UTF-8 解不開就換 */
    private fun decode(bytes: ByteArray): String {
        var s = String(bytes, Charsets.UTF_8)
        if (s.contains('\uFFFD')) {
            s = try { String(bytes, Charset.forName("MS950")) } catch (e: Exception) { s }
        }
        return s.removePrefix("\uFEFF")
    }

    private fun splitCsv(line: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var q = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                ch == '"' && q && i + 1 < line.length && line[i + 1] == '"' -> { sb.append('"'); i++ }
                ch == '"' -> q = !q
                ch == ',' && !q -> { out.add(sb.toString().trim()); sb.setLength(0) }
                else -> sb.append(ch)
            }
            i++
        }
        out.add(sb.toString().trim())
        return out
    }

    private fun idxOf(header: List<String>, vararg keys: String): Int {
        for (k in keys) {
            val i = header.indexOfFirst { it.contains(k, ignoreCase = true) }
            if (i >= 0) return i
        }
        return -1
    }

    private val DIRS = listOf(
        "東北" to 45f, "東南" to 135f, "西南" to 225f, "西北" to 315f,
        "北" to 0f, "東" to 90f, "南" to 180f, "西" to 270f
    )

    /** 「南往北」「往東」「180」都吃得下；看不懂就回 null（不做方向過濾） */
    private fun parseBearing(raw: String?): Float? {
        if (raw.isNullOrBlank()) return null
        raw.trim().toFloatOrNull()?.let { return ((it % 360f) + 360f) % 360f }
        val t = if (raw.contains("往")) raw.substringAfterLast("往") else raw
        for ((k, v) in DIRS) if (t.contains(k)) return v
        return null
    }

    fun parse(text: String): List<Cam> {
        val lines = text.split('\n').map { it.trimEnd('\r') }.filter { it.isNotBlank() }
        if (lines.size < 2) return emptyList()
        val header = splitCsv(lines[0])

        val iLat = idxOf(header, "緯度", "lat")
        val iLon = idxOf(header, "經度", "lon", "lng")
        if (iLat < 0 || iLon < 0) return emptyList()
        val iName = idxOf(header, "設置地點", "設置位置", "地點", "位置", "name", "location")
        val iLimit = idxOf(header, "速限", "limit", "speed")
        val iDir = idxOf(header, "拍攝方向", "取締方向", "方向", "bearing", "direction")
        val iId = idxOf(header, "設備編號", "編號", "id")

        val out = ArrayList<Cam>(lines.size)
        for (n in 1 until lines.size) {
            val c = splitCsv(lines[n])
            if (c.size <= maxOf(iLat, iLon)) continue
            val lat = c[iLat].toDoubleOrNull() ?: continue
            val lon = c[iLon].toDoubleOrNull() ?: continue
            if (lat < 21.0 || lat > 26.5 || lon < 118.0 || lon > 123.0) continue  // 台澎金馬範圍
            out.add(
                Cam(
                    id = if (iId >= 0 && iId < c.size && c[iId].isNotBlank()) c[iId] else "$n",
                    name = if (iName >= 0 && iName < c.size) c[iName] else "測速照相",
                    lat = lat,
                    lon = lon,
                    limit = if (iLimit >= 0 && iLimit < c.size) Regex("\\d+").find(c[iLimit])?.value?.toIntOrNull() else null,
                    bearing = if (iDir >= 0 && iDir < c.size) parseBearing(c[iDir]) else null
                )
            )
        }
        return out
    }
}
