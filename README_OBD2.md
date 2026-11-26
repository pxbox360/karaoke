# เพิ่ม OBD2 ให้ BYD Seal Battery Pro

สถานะ: ยังไม่ได้ build เป็น APK และยังไม่ได้ทดสอบกับรถ

## อุปกรณ์
อแดปเตอร์ ELM327 แบบ **Bluetooth Classic** ที่ชื่อมีคำว่า OBD (โค้ดนี้ไม่รองรับ BLE)

## วิธีใส่ในโปรเจกต์เดิม
1. สำรองโปรเจกต์ก่อน
2. คัดลอกโฟลเดอร์ `app/` ไปทับในโปรเจกต์ (เพิ่ม 2 ไฟล์ .java และแทน index.html)
   ถ้าเคยแก้ index.html หลังสร้าง v4 ให้ย้ายเฉพาะส่วน OBD2 ไปใส่เอง
3. เพิ่มใน AndroidManifest.xml
```xml
<uses-permission android:name="android.permission.BLUETOOTH" android:maxSdkVersion="30" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
```
4. ใน MainActivity.kt เพิ่มตัวแปรในคลาส
```kotlin
private var obd: com.bydseal.batterypro.obd.ObdSession? = null

private fun startObd() {
    if (obd == null) obd = com.bydseal.batterypro.obd.ObdSession(webView).also { it.start() }
}

override fun onRequestPermissionsResult(code: Int, p: Array<out String>, r: IntArray) {
    super.onRequestPermissionsResult(code, p, r)
    if (code == 42 && r.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED) startObd()
}
```
5. ท้าย `onCreate` (หลังโหลด index.html) เพิ่ม
```kotlin
webView.webViewClient = object : android.webkit.WebViewClient() {
    override fun onPageFinished(view: android.webkit.WebView?, url: String?) {
        if (android.os.Build.VERSION.SDK_INT >= 31 &&
            checkSelfPermission("android.permission.BLUETOOTH_CONNECT") != android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf("android.permission.BLUETOOTH_CONNECT"), 42)
        else startObd()
    }
}
```
6. ใน `onDestroy` เพิ่ม `obd?.shutdown()`
7. จับคู่อแดปเตอร์ในเมนู Bluetooth ของจอรถ แล้ว build และติดตั้ง

## ค่าที่อ่าน (UDS 0x22, ส่ง 7E7 รับ 7EF)
| ค่า | DID | สูตร (B4 = ไบต์ข้อมูลแรก) |
|---|---|---|
| SOC % | 1FFC | (B5*256+B4)/100 |
| แรงดันแพ็ก V | 0008 | B5*256+B4 |
| กระแส A | 0009 | (B5*256+B4-5000)/10 (รายงานจาก Dolphin) |
| เซลล์ต่ำสุด/สูงสุด mV | 002B / 002D | B5*256+B4 |
| อุณหภูมิต่ำสุด/สูงสุด °C | 002F / 0031 | B4-40 |

ที่มา (ข้อมูลจากผู้ใช้ ไม่ใช่ของ BYD):
- https://github.com/meatpiHQ/wican-fw/discussions/122
- https://github.com/loryanstrant/BYD-PID-list
