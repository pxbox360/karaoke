package com.bydseal.batterypro

import android.content.Context
import android.os.Looper
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

object BydHalDaemon {

    private const val PORT = 19325

    @JvmStatic
    fun main(args: Array<String>) {
        val listenPort = if (args.isNotEmpty()) args[0].toIntOrNull() ?: PORT else PORT
        if (Looper.getMainLooper() == null) {
            Looper.prepareMainLooper()
        }

        val appContext = obtainSystemContext()
        val serverSocket = ServerSocket()
        serverSocket.reuseAddress = true
        serverSocket.bind(InetSocketAddress("127.0.0.1", listenPort))

        while (true) {
            try {
                val client: Socket = serverSocket.accept()
                Thread { handleClient(client, appContext) }.start()
            } catch (_: Exception) {}
        }
    }

    private fun obtainSystemContext(): Context? {
        return try {
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val systemMain = activityThreadClass.getMethod("systemMain").invoke(null)
            val getSystemContext = activityThreadClass.getMethod("getSystemContext")
            getSystemContext.invoke(systemMain) as? Context
        } catch (_: Exception) {
            null
        }
    }

    private fun handleClient(client: Socket, context: Context?) {
        client.use { sock ->
            val reader = BufferedReader(InputStreamReader(sock.getInputStream()))
            val writer = PrintWriter(sock.getOutputStream(), true)
            val cmd = reader.readLine()?.trim() ?: "READ"

            if (cmd == "SCAN") {
                writer.println(scanAllBydDevices(context))
            } else {
                writer.println(readBydTelemetryJson(context))
            }
        }
    }

    private fun readBydTelemetryJson(context: Context?): String {
        val json = JSONObject()
        json.put("daemonUid", android.os.Process.myUid())
        var anyLive = false

        if (context != null) {
            try {
                val statClass = Class.forName("android.hardware.bydauto.statistic.BYDAutoStatisticDevice")
                val statDev = statClass.getMethod("getInstance", Context::class.java).invoke(null, context)
                if (statDev != null) {
                    val soc = invokeNumber(statClass, statDev, "getElecPercentageValue")
                    val totalMileage = invokeNumber(statClass, statDev, "getTotalMileageValue")
                    val evMileage = invokeNumber(statClass, statDev, "getEVMileageValue")
                    val instantElec = invokeNumber(statClass, statDev, "getInstantElecConValue")

                    if (soc != null) { json.put("soc", soc); anyLive = true }
                    if (totalMileage != null) json.put("mileage", totalMileage)
                    if (evMileage != null) json.put("evMileage", evMileage)
                    if (instantElec != null) json.put("instantKw", instantElec)
                }
            } catch (_: Exception) {}

            try {
                val energyClass = Class.forName("android.hardware.bydauto.energy.BYDAutoEnergyDevice")
                val energyDev = energyClass.getMethod("getInstance", Context::class.java).invoke(null, context)
                if (energyDev != null) {
                    val packVolt = invokeNumber(energyClass, energyDev, "getBatteryVoltage")
                    val packCurr = invokeNumber(energyClass, energyDev, "getBatteryCurrent")
                    val battTemp = invokeNumber(energyClass, energyDev, "getBatteryTemperature")
                    val maxTemp = invokeNumber(energyClass, energyDev, "getMaxCellTemperature")
                    val minTemp = invokeNumber(energyClass, energyDev, "getMinCellTemperature")
                    val soh = invokeNumber(energyClass, energyDev, "getBatterySOH")

                    if (packVolt != null) { json.put("packVolt", packVolt); anyLive = true }
                    if (packCurr != null) json.put("packCurr", packCurr)
                    if (battTemp != null) json.put("battTemp", battTemp)
                    if (maxTemp != null) json.put("maxTemp", maxTemp)
                    if (minTemp != null) json.put("minTemp", minTemp)
                    if (soh != null) json.put("soh", soh)
                }
            } catch (_: Exception) {}

            try {
                val bodyClass = Class.forName("android.hardware.bydauto.bodywork.BYDAutoBodyworkDevice")
                val bodyDev = bodyClass.getMethod("getInstance", Context::class.java).invoke(null, context)
                if (bodyDev != null) {
                    val v12 = invokeNumber(bodyClass, bodyDev, "getBatteryVoltage")
                    if (v12 != null) { json.put("batt12v", v12); anyLive = true }
                }
            } catch (_: Exception) {}

            try {
                val chgClass = Class.forName("android.hardware.bydauto.charging.BYDAutoChargingDevice")
                val chgDev = chgClass.getMethod("getInstance", Context::class.java).invoke(null, context)
                if (chgDev != null) {
                    val chgState = invokeNumber(chgClass, chgDev, "getChargingState")
                    val chgPower = invokeNumber(chgClass, chgDev, "getChargingPower")
                    if (chgState != null) json.put("chgState", chgState)
                    if (chgPower != null) json.put("chgPower", chgPower)
                }
            } catch (_: Exception) {}
        }

        json.put("isLive", anyLive)
        return json.toString()
    }

    private fun invokeNumber(clazz: Class<*>, instance: Any, methodName: String): Double? {
        return try {
            val m = clazz.getMethod(methodName)
            val v = m.invoke(instance)
            when (v) {
                is Number -> v.toDouble()
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun scanAllBydDevices(context: Context?): String {
        val sb = StringBuilder()
        sb.append("=== PRIVILEGED DAEMON SCAN (UID=${android.os.Process.myUid()}) ===\n")
        val targets = listOf(
            "android.hardware.bydauto.statistic.BYDAutoStatisticDevice",
            "android.hardware.bydauto.energy.BYDAutoEnergyDevice",
            "android.hardware.bydauto.charging.BYDAutoChargingDevice",
            "android.hardware.bydauto.bodywork.BYDAutoBodyworkDevice",
            "android.hardware.bydauto.instrument.BYDAutoInstrumentDevice",
            "android.hardware.bydauto.ac.BYDAutoAcDevice"
        )
        for (clsName in targets) {
            try {
                val clazz = Class.forName(clsName)
                val inst = if (context != null) {
                    try { clazz.getMethod("getInstance", Context::class.java).invoke(null, context) } catch (_: Exception) { null }
                } else null
                sb.append("\n[FOUND] $clsName (instance=${inst != null})\n")
                clazz.declaredMethods
                    .filter { it.parameterCount == 0 && (it.name.startsWith("get") || it.name.startsWith("is")) }
                    .sortedBy { it.name }
                    .forEach { m ->
                        val valStr = if (inst != null) {
                            try { m.invoke(inst)?.toString() ?: "null" } catch (ex: Exception) { "ERR:${ex.cause?.javaClass?.simpleName}" }
                        } else "no-inst"
                        sb.append("  • ${m.name}() => $valStr\n")
                    }
            } catch (e: Exception) {
                sb.append("\n[MISS] $clsName (${e.javaClass.simpleName})\n")
            }
        }
        return sb.toString()
    }
}
