package com.bydseal.batterypro

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.random.Random

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private val handler = Handler(Looper.getMainLooper())
    private var useDemoData = false
    private var autoWakeAttempted = false
    private var obd: com.bydseal.batterypro.obd.ObdSession? = null

    private fun startObd() {
        if (obd == null) obd = com.bydseal.batterypro.obd.ObdSession(webView).also { it.start() }
    }

    override fun onRequestPermissionsResult(code: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(code, p, r)
        if (code == 42 && r.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED) startObd()
    }

    private val updateRunnable = object : Runnable {
        override fun run() {
            Thread {
                val jsonPayload = fetchTelemetryPayload()
                runOnUiThread {
                    webView.evaluateJavascript("updateDashboard($jsonPayload);", null)
                }
            }.start()
            handler.postDelayed(this, 1500)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )

        webView = WebView(this)
        setContentView(webView)

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.webChromeClient = WebChromeClient()
        webView.addJavascriptInterface(AndroidBridge(), "AndroidBridge")
        webView.webViewClient = object : android.webkit.WebViewClient() {
            override fun onPageFinished(view: android.webkit.WebView?, url: String?) {
                if (android.os.Build.VERSION.SDK_INT >= 31 &&
                    checkSelfPermission("android.permission.BLUETOOTH_CONNECT") != android.content.pm.PackageManager.PERMISSION_GRANTED)
                    requestPermissions(arrayOf("android.permission.BLUETOOTH_CONNECT"), 42)
                else startObd()
            }
        }
        webView.loadUrl("file:///android_asset/index.html")

        SelfAdbWaker.wakeDaemonAsync(this)
        autoWakeAttempted = true

        handler.postDelayed(updateRunnable, 900)
    }

    inner class AndroidBridge {
        @JavascriptInterface
        fun toggleDemoMode(): Boolean {
            useDemoData = !useDemoData
            return useDemoData
        }

        @JavascriptInterface
        fun triggerAutoWake(): String {
            val (_, msg) = SelfAdbWaker.wakeDaemonBlocking(this@MainActivity)
            return msg
        }

        @JavascriptInterface
        fun scanApiLogs(): String {
            var daemonResp = queryDaemon("SCAN")
            if (daemonResp == null || daemonResp.isEmpty()) {
                val (ok, wakeMsg) = SelfAdbWaker.wakeDaemonBlocking(this@MainActivity)
                if (ok) {
                    daemonResp = queryDaemon("SCAN")
                } else {
                    return "=== AUTO-WAKE STATUS: $wakeMsg ===\n(หากมีหน้าต่าง 'Allow USB debugging?' เด้งบนจอกลางรถ ให้กดติ๊ก Always allow แล้วกด Allow จากนั้นกด Scan อีกครั้งครับ)\n"
                }
            }
            return daemonResp ?: "ไม่พบข้อมูลตอบกลับจาก Daemon"
        }
    }

    private fun queryDaemon(command: String): String? {
        return try {
            Socket().use { sock ->
                sock.connect(InetSocketAddress("127.0.0.1", 19325), 350)
                sock.soTimeout = 500
                val writer = PrintWriter(sock.getOutputStream(), true)
                val reader = BufferedReader(InputStreamReader(sock.getInputStream()))
                writer.println(command)
                if (command == "SCAN") {
                    reader.readText()
                } else {
                    reader.readLine()
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun fetchTelemetryPayload(): String {
        val data = JSONObject()
        var daemonConnected = false
        var liveSuccess = false

        var soc = 86.5
        var soh = 93.0
        var packVolt = 574.2
        var packCurr = 1.4
        var maxTemp = 35
        var minTemp = 33
        var mileage = 100000
        var batt12v = 13.8

        if (!useDemoData) {
            val rawJson = queryDaemon("READ")
            if (rawJson != null) {
                try {
                    val dObj = JSONObject(rawJson)
                    daemonConnected = true
                    if (dObj.optBoolean("isLive", false)) liveSuccess = true
                    if (dObj.has("soc")) soc = dObj.getDouble("soc")
                    if (dObj.has("soh")) soh = dObj.getDouble("soh")
                    if (dObj.has("packVolt")) packVolt = dObj.getDouble("packVolt")
                    if (dObj.has("packCurr")) packCurr = dObj.getDouble("packCurr")
                    if (dObj.has("maxTemp")) maxTemp = dObj.getInt("maxTemp")
                    if (dObj.has("minTemp")) minTemp = dObj.getInt("minTemp")
                    if (dObj.has("mileage")) mileage = dObj.getInt("mileage")
                    if (dObj.has("batt12v")) batt12v = dObj.getDouble("batt12v")
                } catch (_: Exception) {}
            } else if (!autoWakeAttempted) {
                autoWakeAttempted = true
                SelfAdbWaker.wakeDaemonAsync(this)
            }

            if (!liveSuccess) {
                try {
                    val statClass = Class.forName("android.hardware.bydauto.statistic.BYDAutoStatisticDevice")
                    val statDev = statClass.getMethod("getInstance", Context::class.java).invoke(null, this)
                    val rawSoc = statClass.getMethod("getElecPercentageValue").invoke(statDev) as? Int
                    if (rawSoc != null && rawSoc in 0..100) {
                        soc = rawSoc.toDouble()
                        liveSuccess = true
                    }
                    val rawMile = statClass.getMethod("getTotalMileageValue").invoke(statDev) as? Int
                    if (rawMile != null) mileage = rawMile
                } catch (_: Exception) {}
            }
        }

        if (!liveSuccess || useDemoData) {
            packVolt = 574.2 + Random.nextDouble(-0.4, 0.4)
            packCurr = 1.4 + Random.nextDouble(-0.2, 0.2)
            maxTemp = 35
            minTemp = 33
        }

        val powerW = Math.round(packVolt * packCurr)
        val energyLeftKwh = String.format("%.1f", (soc / 100.0) * 82.5 * (soh / 100.0))
        val rangeKm = Math.round((soc / 100.0) * 520 * (soh / 100.0))

        val cellsArray = JSONArray()
        var maxMv = 0
        var minMv = 5000
        for (i in 1..176) {
            val baseMv = 3315 + ((i * 7) % 14) + Random.nextInt(-1, 2)
            if (baseMv > maxMv) maxMv = baseMv
            if (baseMv < minMv) minMv = baseMv
            cellsArray.put(baseMv)
        }

        data.put("daemonConnected", daemonConnected)
        data.put("wakeStatus", SelfAdbWaker.lastStatus)
        data.put("isLive", liveSuccess && !useDemoData)
        data.put("soc", String.format("%.1f", soc))
        data.put("soh", String.format("%.2f", soh))
        data.put("packVolt", String.format("%.1f", packVolt))
        data.put("packCurr", String.format("%.1f", packCurr))
        data.put("powerW", powerW)
        data.put("rangeKm", rangeKm)
        data.put("energyLeft", energyLeftKwh)
        data.put("maxTemp", maxTemp)
        data.put("minTemp", minTemp)
        data.put("maxMv", maxMv)
        data.put("minMv", minMv)
        data.put("diffMv", maxMv - minMv)
        data.put("mileage", mileage)
        data.put("batt12v", String.format("%.1f", batt12v))
        data.put("cells", cellsArray)

        return data.toString()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(updateRunnable)
        obd?.shutdown()
    }
}
