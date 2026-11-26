package com.bydseal.batteryobd2

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var obdManager: BydBleObdManager
    private val handler = Handler(Looper.getMainLooper())
    private var useDemoData = false

    private val updateRunnable = object : Runnable {
        override fun run() {
            val payload = obdManager.buildTelemetryJson(useDemoData)
            webView.evaluateJavascript("updateDashboard($payload);", null)
            handler.postDelayed(this, 1000)
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

        obdManager = BydBleObdManager(this)

        webView = WebView(this)
        setContentView(webView)

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.webChromeClient = WebChromeClient()
        webView.addJavascriptInterface(AndroidBridge(), "AndroidBridge")
        webView.loadUrl("file:///android_asset/index.html")

        requestBlePermissionsAndScan()
        handler.postDelayed(updateRunnable, 800)
    }

    private fun requestBlePermissionsAndScan() {
        val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_SCAN)
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        val missing = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 101)
        } else {
            obdManager.startScan { notifyDevicesUpdated() }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101) {
            obdManager.startScan { notifyDevicesUpdated() }
        }
    }

    private fun notifyDevicesUpdated() {
        runOnUiThread {
            val listJson = obdManager.getDiscoveredDevicesJson()
            webView.evaluateJavascript("updateBleDevices($listJson);", null)
        }
    }

    inner class AndroidBridge {
        @JavascriptInterface
        fun toggleDemoMode(): Boolean {
            useDemoData = !useDemoData
            return useDemoData
        }

        @JavascriptInterface
        fun startBleScan() {
            runOnUiThread {
                requestBlePermissionsAndScan()
            }
        }

        @JavascriptInterface
        fun connectBle(address: String) {
            obdManager.connectToDevice(address)
        }

        @JavascriptInterface
        fun disconnectBle() {
            obdManager.disconnect()
        }

        @JavascriptInterface
        fun getObdLogs(): String {
            return obdManager.getLogsText()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(updateRunnable)
        obdManager.disconnect()
    }
}
