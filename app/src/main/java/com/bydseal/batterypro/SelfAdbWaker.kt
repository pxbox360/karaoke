package com.bydseal.batterypro

import android.content.Context
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

object SelfAdbWaker {

    private const val CMD_CNXN = 0x4e584e43
    private const val CMD_AUTH = 0x48545541
    private const val CMD_OPEN = 0x4e45504f

    private const val AUTH_TYPE_TOKEN = 1
    private const val AUTH_TYPE_SIGNATURE = 2
    private const val AUTH_TYPE_RSAPUBLICKEY = 3

    @Volatile
    var lastStatus: String = "Idle"

    fun wakeDaemonAsync(context: Context, onComplete: ((Boolean, String) -> Unit)? = null) {
        Thread {
            val (ok, msg) = wakeDaemonBlocking(context)
            lastStatus = msg
            onComplete?.invoke(ok, msg)
        }.start()
    }

    fun wakeDaemonBlocking(context: Context): Pair<Boolean, String> {
        val apkPath = context.applicationInfo.sourceDir
        val shellCmd = "CLASSPATH=$apkPath setsid /system/bin/app_process64 / com.bydseal.batterypro.BydHalDaemon 19325 </dev/null >/dev/null 2>&1 &"

        val candidatePorts = listOf(5555, 5556)
        var lastErr = "ไม่สามารถเชื่อมต่อ ADB Localhost (โปรดเปิด USB/Wireless Debugging บนจอรถ)"

        for (port in candidatePorts) {
            try {
                Socket().use { sock ->
                    sock.connect(InetSocketAddress("127.0.0.1", port), 1200)
                    sock.soTimeout = 4000

                    val keyPair = loadOrGenerateKeyPair(context)
                    val out = sock.getOutputStream()
                    val inp = DataInputStream(sock.getInputStream())

                    val banner = "host::bydseal-batterypro\u0000".toByteArray(Charsets.UTF_8)
                    out.write(buildPacket(CMD_CNXN, 0x01000000, 4096, banner))
                    out.flush()

                    var sentSignature = false
                    var connected = false

                    while (!connected) {
                        val header = ByteArray(24)
                        inp.readFully(header)
                        val bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
                        val cmd = bb.int
                        val arg0 = bb.int
                        val arg1 = bb.int
                        val dataLen = bb.int
                        bb.int
                        bb.int

                        val payload = ByteArray(dataLen)
                        if (dataLen > 0) inp.readFully(payload)

                        when (cmd) {
                            CMD_CNXN -> {
                                connected = true
                            }
                            CMD_AUTH -> {
                                if (arg0 == AUTH_TYPE_TOKEN) {
                                    if (!sentSignature) {
                                        val sig = signAdbToken(keyPair, payload)
                                        out.write(buildPacket(CMD_AUTH, AUTH_TYPE_SIGNATURE, 0, sig))
                                        out.flush()
                                        sentSignature = true
                                    } else {
                                        val pubBytes = encodeAdbPublicKey(keyPair.public as RSAPublicKey)
                                        out.write(buildPacket(CMD_AUTH, AUTH_TYPE_RSAPUBLICKEY, 0, pubBytes))
                                        out.flush()
                                    }
                                }
                            }
                            else -> {
                                return Pair(false, "ADB Handshake ผิดพลาด (cmd=0x${cmd.toString(16)})")
                            }
                        }
                    }

                    val dest = "shell:$shellCmd\u0000".toByteArray(Charsets.UTF_8)
                    out.write(buildPacket(CMD_OPEN, 1, 0, dest))
                    out.flush()

                    try {
                        val respHdr = ByteArray(24)
                        inp.readFully(respHdr)
                    } catch (_: Exception) {}

                    Thread.sleep(600)
                    return Pair(true, "ปลุก HAL Daemon สำเร็จผ่านพอร์ต $port")
                }
            } catch (e: Exception) {
                lastErr = "พอร์ต $port: ${e.message}"
            }
        }
        return Pair(false, lastErr)
    }

    private fun buildPacket(cmd: Int, arg0: Int, arg1: Int, data: ByteArray): ByteArray {
        var crc = 0
        for (b in data) {
            crc += (b.toInt() and 0xFF)
        }
        val bb = ByteBuffer.allocate(24 + data.size).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(cmd)
        bb.putInt(arg0)
        bb.putInt(arg1)
        bb.putInt(data.size)
        bb.putInt(crc)
        bb.putInt(cmd xor -1)
        bb.put(data)
        return bb.array()
    }

    private fun loadOrGenerateKeyPair(context: Context): KeyPair {
        val privFile = File(context.filesDir, "adb_rsa_priv.key")
        val pubFile = File(context.filesDir, "adb_rsa_pub.key")
        if (privFile.exists() && pubFile.exists()) {
            try {
                val kf = KeyFactory.getInstance("RSA")
                val priv = kf.generatePrivate(PKCS8EncodedKeySpec(privFile.readBytes()))
                val pub = kf.generatePublic(X509EncodedKeySpec(pubFile.readBytes()))
                return KeyPair(pub, priv)
            } catch (_: Exception) {}
        }
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048)
        val kp = kpg.generateKeyPair()
        privFile.writeBytes(kp.private.encoded)
        pubFile.writeBytes(kp.public.encoded)
        return kp
    }

    private fun signAdbToken(kp: KeyPair, token: ByteArray): ByteArray {
        val sig = Signature.getInstance("SHA1withRSA")
        sig.initSign(kp.private)
        sig.update(token)
        return sig.sign()
    }

    private fun encodeAdbPublicKey(pubKey: RSAPublicKey): ByteArray {
        val mod = pubKey.modulus
        val r32 = BigInteger.ONE.shiftLeft(32)
        val n0inv = mod.remainder(r32).modInverse(r32).negate().toInt()
        val r = BigInteger.ONE.shiftLeft(2048)
        val rr = r.multiply(r).mod(mod)

        val buf = ByteBuffer.allocate(524).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(64)
        buf.putInt(n0inv)

        val modWords = IntArray(64)
        val rrWords = IntArray(64)
        var tmpMod = mod
        var tmpRr = rr
        for (i in 0 until 64) {
            modWords[i] = tmpMod.remainder(r32).toInt()
            tmpMod = tmpMod.shiftRight(32)
            rrWords[i] = tmpRr.remainder(r32).toInt()
            tmpRr = tmpRr.shiftRight(32)
        }
        for (w in modWords) buf.putInt(w)
        for (w in rrWords) buf.putInt(w)
        buf.putInt(pubKey.publicExponent.toInt())

        val b64 = Base64.encodeToString(buf.array(), Base64.NO_WRAP)
        val out = ByteArrayOutputStream()
        out.write("$b64 bydseal@dilink\u0000".toByteArray(Charsets.UTF_8))
        return out.toByteArray()
    }
}
