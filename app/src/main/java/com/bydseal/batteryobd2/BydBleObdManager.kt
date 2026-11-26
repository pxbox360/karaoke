package com.bydseal.batteryobd2

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * BLE OBD2 Manager for BYD Seal Performance (2023) - 82.5 kWh / 176 Cells
 * Connects directly via BluetoothGatt without requiring system-level Android OS pairing,
 * bypassing BYD DiLink Bluetooth Classic restrictions and OS SecurityExceptions.
 */
@SuppressLint("MissingPermission")
class BydBleObdManager(private val context: Context) {

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private val mainHandler = Handler(Looper.getMainLooper())

    private var bluetoothGatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var notifyChar: BluetoothGattCharacteristic? = null

    private val rxQueue = LinkedBlockingQueue<String>()
    private val rxBuffer = StringBuilder()

    @Volatile
    var isScanning = false
    @Volatile
    var isConnected = false
    @Volatile
    var connectedDeviceName = ""
    @Volatile
    var statusMessage = "พร้อมสแกนหา OBD2 BLE"

    val discoveredDevices = ConcurrentHashMap<String, BluetoothDevice>()
    private val rawLogs = ArrayDeque<String>()

    // Live Telemetry State
    @Volatile var soc: Double = 86.5
    @Volatile var soh: Double = 93.0
    @Volatile var packVolt: Double = 574.2
    @Volatile var packCurr: Double = 1.4
    @Volatile var maxTemp: Int = 35
    @Volatile var minTemp: Int = 33
    @Volatile var avgTemp: Int = 34
    @Volatile var batt12v: Double = 13.8
    @Volatile var hasLiveData: Boolean = false
    val cellVoltages = IntArray(176) { i -> 3315 + ((i * 7) % 12) }

    // Common OBD2 BLE Service & Characteristic UUIDs (Vgate iCar Pro, OBDLink CX, Veepeak, ELM327 BLE)
    private val KNOWN_SERVICE_UUIDS = listOf(
        UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb"), // Vgate / ELM327 BLE standard
        UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb"), // HM-10 / JDY / Veepeak
        UUID.fromString("e7810a71-73ae-499d-8c15-faa9aef0c3f2"), // OBDLink CX / STN BLE
        UUID.fromString("000018f0-0000-1000-8000-00805f9b34fb")  // Generic Serial BLE
    )
    private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private fun log(msg: String) {
        synchronized(rawLogs) {
            if (rawLogs.size > 60) rawLogs.removeFirst()
            rawLogs.addLast(msg)
        }
    }

    fun getLogsText(): String {
        synchronized(rawLogs) {
            return rawLogs.joinToString("\n")
        }
    }

    fun startScan(onUpdate: () -> Unit) {
        val scanner = bluetoothAdapter?.bluetoothLeScanner
        if (scanner == null) {
            statusMessage = "ไม่พบตัวรับสัญญาณ Bluetooth LE บนเครื่อง"
            onUpdate()
            return
        }
        if (isScanning) return

        discoveredDevices.clear()
        isScanning = true
        statusMessage = "กำลังสแกนหา OBD2 BLE รอบตัวรถ..."
        log("[SCAN] Started BLE scan...")
        onUpdate()

        scanner.startScan(scanCallback)
        mainHandler.postDelayed({
            stopScan()
            onUpdate()
        }, 8000)
    }

    fun stopScan() {
        if (!isScanning) return
        try {
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (_: Exception) {}
        isScanning = false
        if (!isConnected) {
            statusMessage = "พบอุปกรณ์ ${discoveredDevices.size} รายการ"
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val dev = result.device ?: return
            val name = dev.name ?: result.scanRecord?.deviceName ?: return
            if (name.isNotBlank()) {
                discoveredDevices[dev.address] = dev
                // Auto-connect if recognizable OBD2 dongle name
                val lower = name.lowercase()
                if (!isConnected && (lower.contains("obd") || lower.contains("vgate") || lower.contains("icar") || lower.contains("veepeak") || lower.contains("elm") || lower.contains("vlink"))) {
                    log("[SCAN] Auto-matching OBD2 device: $name (${dev.address})")
                    stopScan()
                    connectToDevice(dev.address)
                }
            }
        }
    }

    fun getDiscoveredDevicesJson(): String {
        val arr = JSONArray()
        for ((addr, dev) in discoveredDevices) {
            val obj = JSONObject()
            obj.put("address", addr)
            obj.put("name", dev.name ?: "Unknown BLE")
            arr.put(obj)
        }
        return arr.toString()
    }

    fun connectToDevice(address: String) {
        stopScan()
        val dev = discoveredDevices[address] ?: bluetoothAdapter?.getRemoteDevice(address) ?: return
        disconnect()
        connectedDeviceName = dev.name ?: address
        statusMessage = "กำลังเชื่อมต่อ $connectedDeviceName..."
        log("[BLE] Connecting to $connectedDeviceName ($address)...")
        bluetoothGatt = dev.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnect() {
        isConnected = false
        writeChar = null
        notifyChar = null
        try {
            bluetoothGatt?.disconnect()
            bluetoothGatt?.close()
        } catch (_: Exception) {}
        bluetoothGatt = null
        statusMessage = "ตัดการเชื่อมต่อ OBD2 แล้ว"
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                statusMessage = "เชื่อมต่อแล้ว กำลังค้นหา Service..."
                log("[BLE] Connected. Discovering services...")
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                isConnected = false
                statusMessage = "หลุดการเชื่อมต่อ OBD2 (สถานะ $status)"
                log("[BLE] Disconnected (status=$status)")
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                statusMessage = "ค้นหา Service ล้มเหลว"
                return
            }

            var foundWrite: BluetoothGattCharacteristic? = null
            var foundNotify: BluetoothGattCharacteristic? = null

            // Prioritize known OBD2 BLE service UUIDs
            for (svc in gatt.services) {
                log("[GATT] Service: ${svc.uuid}")
                if (KNOWN_SERVICE_UUIDS.contains(svc.uuid)) {
                    for (ch in svc.characteristics) {
                        val props = ch.properties
                        if ((props and BluetoothGattCharacteristic.PROPERTY_WRITE != 0 ||
                                    props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) && foundWrite == null) {
                            foundWrite = ch
                        }
                        if ((props and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0 ||
                                    props and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) && foundNotify == null) {
                            foundNotify = ch
                        }
                    }
                }
            }

            // Fallback: pick any writable + notifiable characteristic pair
            if (foundWrite == null || foundNotify == null) {
                for (svc in gatt.services) {
                    if (svc.uuid.toString().startsWith("00001800") || svc.uuid.toString().startsWith("00001801")) continue
                    for (ch in svc.characteristics) {
                        val props = ch.properties
                        if ((props and BluetoothGattCharacteristic.PROPERTY_WRITE != 0 ||
                                    props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) && foundWrite == null) {
                            foundWrite = ch
                        }
                        if ((props and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0 ||
                                    props and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) && foundNotify == null) {
                            foundNotify = ch
                        }
                    }
                }
            }

            if (foundWrite != null && foundNotify != null) {
                writeChar = foundWrite
                notifyChar = foundNotify
                gatt.setCharacteristicNotification(foundNotify, true)
                val desc = foundNotify.getDescriptor(CCCD_UUID)
                if (desc != null) {
                    desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    gatt.writeDescriptor(desc)
                } else {
                    onReadyToInitialize()
                }
            } else {
                statusMessage = "ไม่พบช่องสื่อสาร Serial บนอุปกรณ์นี้"
                log("[ERR] No suitable write/notify characteristics found.")
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            onReadyToInitialize()
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val bytes = characteristic.value ?: return
            handleIncomingBytes(bytes)
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            handleIncomingBytes(value)
        }
    }

    private fun handleIncomingBytes(bytes: ByteArray) {
        val text = String(bytes, Charsets.US_ASCII)
        synchronized(rxBuffer) {
            rxBuffer.append(text)
            if (rxBuffer.contains(">")) {
                val fullResp = rxBuffer.toString()
                rxBuffer.clear()
                rxQueue.offer(fullResp)
            }
        }
    }

    private fun onReadyToInitialize() {
        isConnected = true
        statusMessage = "เชื่อมต่อ $connectedDeviceName สำเร็จ! กำลังตั้งค่า UDS..."
        log("[OBD] Starting BYD Seal BMS UDS initialization...")
        Thread {
            initializeBydBms()
            pollLoop()
        }.start()
    }

    private fun sendAtOrUdsCommand(cmd: String, timeoutMs: Long = 1500): String {
        val gatt = bluetoothGatt ?: return ""
        val wChar = writeChar ?: return ""
        rxQueue.clear()
        synchronized(rxBuffer) { rxBuffer.clear() }

        val payload = (cmd + "\r").toByteArray(Charsets.US_ASCII)
        wChar.writeType = if ((wChar.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }
        wChar.value = payload
        gatt.writeCharacteristic(wChar)

        val raw = rxQueue.poll(timeoutMs, TimeUnit.MILLISECONDS) ?: ""
        val cleaned = raw.replace("\r", " ").replace("\n", " ").replace(">", "").trim()
        log("TX: $cmd  |  RX: $cleaned")
        return cleaned
    }

    private fun initializeBydBms() {
        val initCmds = listOf(
            "ATZ",          // Reset ELM327/STN
            "ATE0",         // Echo Off
            "ATL0",         // Linefeeds Off
            "ATS0",         // Spaces Off
            "ATH1",         // Headers On (Crucial for ISO-TP multi-frame)
            "ATSP6",        // ISO 15765-4 CAN (11-bit ID, 500 kbaud)
            "ATSH 7E7",     // Transmit Header -> BYD BMS ECU
            "ATCRA 7EF",    // Receive Filter -> BYD BMS Response
            "ATFCSH 7E7",   // Flow Control Header -> 7E7
            "ATFCSD 300000",// Flow Control Data (Continue, BS=0, STmin=0)
            "ATFCSM 1"      // Enable custom Flow Control
        )
        for (c in initCmds) {
            sendAtOrUdsCommand(c, 1200)
            Thread.sleep(80)
        }
        statusMessage = "เชื่อมต่อ BYD BMS (7E7) พร้อมอ่านค่าสด"
    }

    private fun pollLoop() {
        var cycle = 0
        while (isConnected) {
            try {
                // 1. Read 12V Auxiliary Battery via ATRV
                val rv = sendAtOrUdsCommand("ATRV", 800)
                val v12Parsed = rv.replace("V", "", ignoreCase = true).trim().toDoubleOrNull()
                if (v12Parsed != null && v12Parsed in 9.0..16.5) {
                    batt12v = v12Parsed
                }

                // 2. UDS 22 00 02 -> BMS Real SOC
                val socResp = sendAtOrUdsCommand("220002", 1000)
                parseUdsSingleValue(socResp, "620002")?.let { bytes ->
                    if (bytes.isNotEmpty()) {
                        val rawSoc = if (bytes.size >= 2) ((bytes[0] shl 8) or bytes[1]) / 10.0 else bytes[0].toDouble()
                        if (rawSoc in 0.0..100.0) {
                            soc = rawSoc
                            hasLiveData = true
                        }
                    }
                }

                // 3. UDS 22 00 05 -> Battery State of Health (SOH)
                val sohResp = sendAtOrUdsCommand("220005", 1000)
                parseUdsSingleValue(sohResp, "620005")?.let { bytes ->
                    if (bytes.isNotEmpty()) {
                        val rawSoh = if (bytes.size >= 2) ((bytes[0] shl 8) or bytes[1]) / 10.0 else bytes[0].toDouble()
                        if (rawSoh in 50.0..100.0) {
                            soh = rawSoh
                            hasLiveData = true
                        }
                    }
                }

                // 4. UDS 22 00 01 -> Pack Voltage & Current
                val packResp = sendAtOrUdsCommand("220001", 1000)
                parseUdsSingleValue(packResp, "620001")?.let { bytes ->
                    if (bytes.size >= 4) {
                        val vRaw = ((bytes[0] shl 8) or bytes[1]) / 10.0
                        val cRaw = (((bytes[2] shl 8) or bytes[3]).toShort().toInt()) / 10.0
                        if (vRaw in 300.0..700.0) {
                            packVolt = vRaw
                            packCurr = cRaw
                            hasLiveData = true
                        }
                    }
                }

                // 5. UDS 22 00 06 -> Max / Min / Avg Battery Temperature
                val tempResp = sendAtOrUdsCommand("220006", 1000)
                parseUdsSingleValue(tempResp, "620006")?.let { bytes ->
                    if (bytes.size >= 2) {
                        val t1 = bytes[0] - 40
                        val t2 = bytes[1] - 40
                        if (t1 in -20..80 && t2 in -20..80) {
                            maxTemp = maxOf(t1, t2)
                            minTemp = minOf(t1, t2)
                            avgTemp = (maxTemp + minTemp) / 2
                            hasLiveData = true
                        }
                    }
                }

                // 6. Every 3rd cycle: Query Cell Voltages (Multi-frame ISO-TP 22 01 01 .. 22 01 04)
                if (cycle % 3 == 0) {
                    queryAll176Cells()
                }

                cycle++
                Thread.sleep(600)
            } catch (e: Exception) {
                log("[POLL ERR] ${e.message}")
                Thread.sleep(1000)
            }
        }
    }

    private fun queryAll176Cells() {
        val dids = listOf("220101" to 0, "220102" to 44, "220103" to 88, "220104" to 132)
        for ((did, offset) in dids) {
            val resp = sendAtOrUdsCommand(did, 1500)
            val payloadBytes = extractIsoTpPayload(resp, "62" + did.substring(2))
            if (payloadBytes.size >= 2) {
                var idx = 0
                var cellCursor = offset
                while (idx + 1 < payloadBytes.size && cellCursor < 176) {
                    val mv = (payloadBytes[idx] shl 8) or payloadBytes[idx + 1]
                    if (mv in 2500..4200) {
                        cellVoltages[cellCursor] = mv
                        hasLiveData = true
                    }
                    idx += 2
                    cellCursor++
                }
            }
        }
    }

    private fun parseUdsSingleValue(raw: String, expectedHeader: String): List<Int>? {
        val clean = raw.replace(" ", "").uppercase()
        val pos = clean.indexOf(expectedHeader)
        if (pos == -1) return null
        val hexData = clean.substring(pos + expectedHeader.length)
        val out = mutableListOf<Int>()
        var i = 0
        while (i + 1 < hexData.length) {
            val b = hexData.substring(i, i + 2).toIntOrNull(16) ?: break
            out.add(b)
            i += 2
        }
        return out
    }

    private fun extractIsoTpPayload(raw: String, expectedSidDid: String): List<Int> {
        // Reassemble ISO-TP frames starting with 7EF
        val tokens = raw.split(" ").filter { it.isNotBlank() }
        val assembled = StringBuilder()
        for (tok in tokens) {
            if (tok.startsWith("7EF") && tok.length > 5) {
                val frameBody = tok.substring(3)
                val pciType = frameBody[0]
                when (pciType) {
                    '0' -> assembled.append(frameBody.substring(2)) // Single Frame
                    '1' -> assembled.append(frameBody.substring(4)) // First Frame (skip 1xxx len)
                    '2' -> assembled.append(frameBody.substring(2)) // Consecutive Frame (skip 2x seq)
                }
            }
        }
        val fullHex = assembled.toString()
        val pos = fullHex.indexOf(expectedSidDid)
        if (pos == -1) return emptyList()
        val payloadHex = fullHex.substring(pos + expectedSidDid.length)
        val bytes = mutableListOf<Int>()
        var i = 0
        while (i + 1 < payloadHex.length) {
            val v = payloadHex.substring(i, i + 2).toIntOrNull(16) ?: break
            bytes.add(v)
            i += 2
        }
        return bytes
    }

    fun buildTelemetryJson(forceDemo: Boolean): String {
        val data = JSONObject()
        val live = hasLiveData && !forceDemo

        var curSoc = soc
        var curSoh = soh
        var curVolt = packVolt
        var curCurr = packCurr
        var curMaxT = maxTemp
        var curMinT = minTemp
        var cur12v = batt12v

        val cellsArray = JSONArray()
        var maxMv = 0
        var minMv = 5000

        if (!live) {
            curVolt = 574.2 + Random.nextDouble(-0.4, 0.4)
            curCurr = 1.4 + Random.nextDouble(-0.2, 0.2)
            curMaxT = 35
            curMinT = 33
            for (i in 1..176) {
                val mv = 3315 + ((i * 7) % 14) + Random.nextInt(-1, 2)
                if (mv > maxMv) maxMv = mv
                if (mv < minMv) minMv = mv
                cellsArray.put(mv)
            }
        } else {
            for (i in 0 until 176) {
                val mv = cellVoltages[i]
                if (mv > maxMv) maxMv = mv
                if (mv < minMv) minMv = mv
                cellsArray.put(mv)
            }
        }

        val powerW = Math.round(curVolt * curCurr)
        val energyLeftKwh = String.format("%.1f", (curSoc / 100.0) * 82.5 * (curSoh / 100.0))
        val rangeKm = Math.round((curSoc / 100.0) * 520 * (curSoh / 100.0))

        data.put("obdConnected", isConnected)
        data.put("obdDevice", connectedDeviceName)
        data.put("obdStatus", statusMessage)
        data.put("isLive", live)
        data.put("soc", String.format("%.1f", curSoc))
        data.put("soh", String.format("%.2f", curSoh))
        data.put("packVolt", String.format("%.1f", curVolt))
        data.put("packCurr", String.format("%.1f", curCurr))
        data.put("powerW", powerW)
        data.put("rangeKm", rangeKm)
        data.put("energyLeft", energyLeftKwh)
        data.put("maxTemp", curMaxT)
        data.put("minTemp", curMinT)
        data.put("maxMv", maxMv)
        data.put("minMv", minMv)
        data.put("diffMv", maxMv - minMv)
        data.put("batt12v", String.format("%.1f", cur12v))
        data.put("cells", cellsArray)

        return data.toString()
    }
}
