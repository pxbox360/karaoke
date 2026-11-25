package com.example.sealbattery   // ต้องตรงกับ package name ของโปรเจกต์

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.io.File

/** อ่านค่าจาก API ของ BYD ผ่าน reflection: ลองทุก getter ที่ไม่มีพารามิเตอร์ */
object CarData {
    private val CLASSES = listOf(
        "android.hardware.bydauto.statistic.BYDAutoStatisticDevice",
        "android.hardware.bydauto.energy.BYDAutoEnergyDevice",
        "android.hardware.bydauto.charging.BYDAutoChargingDevice",
        "android.hardware.bydauto.bodywork.BYDAutoBodyworkDevice",
        "android.hardware.bydauto.engine.BYDAutoEngineDevice",
        "android.hardware.bydauto.speed.BYDAutoSpeedDevice",
        "android.hardware.bydauto.ac.BYDAutoAcDevice",
        "android.hardware.bydauto.gearbox.BYDAutoGearboxDevice",
        "android.hardware.bydauto.tyre.BYDAutoTyreDevice",
        "android.hardware.bydauto.instrument.BYDAutoInstrumentDevice"
    )

    fun read(ctx: Context): String {
        val out = JSONObject()
        for (cn in CLASSES) {
            val o = JSONObject()
            try {
                val c = Class.forName(cn)
                val inst = c.getMethod("getInstance", Context::class.java).invoke(null, ctx)
                if (inst == null) {
                    o.put("_error", "getInstance returned null")
                } else {
                    for (m in c.methods) {
                        if (m.parameterTypes.isNotEmpty() || !m.name.startsWith("get")) continue
                        if (m.name == "getClass" || m.name == "getInstance") continue
                        val rt = m.returnType
                        if (!(rt.isPrimitive || rt == String::class.java)) continue
                        try {
                            o.put(m.name, m.invoke(inst).toString())
                        } catch (t: Throwable) {
                            o.put(m.name, "ERR:" + (t.cause?.javaClass?.simpleName ?: t.javaClass.simpleName))
                        }
                    }
                }
            } catch (t: ClassNotFoundException) {
                o.put("_error", "class not found")
            } catch (t: Throwable) {
                o.put("_error", t.javaClass.simpleName + ": " + (t.cause?.message ?: t.message))
            }
            out.put(cn.substringAfterLast('.'), o)
        }
        return out.toString()
    }
}

class MainActivity : AppCompatActivity() {

    inner class Bridge {
        @JavascriptInterface fun read(): String = CarData.read(applicationContext)

        @JavascriptInterface fun copy(text: String) {
            runOnUiThread {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("probe", text))
            }
        }

        // เซฟไว้ที่ /sdcard/Android/data/<package>/files/probe.txt เอามาด้วย adb pull ได้
        @JavascriptInterface fun save(text: String): String {
            val f = File(getExternalFilesDir(null), "probe.txt")
            f.writeText(text)
            return f.absolutePath
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.webViewClient = WebViewClient()
        web.setBackgroundColor(0xFF070B14.toInt())
        web.addJavascriptInterface(Bridge(), "Car")
        web.loadUrl("file:///android_asset/index.html")
        setContentView(web)

        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
    }
}
