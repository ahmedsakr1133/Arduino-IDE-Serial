package com.serialmonitor

import java.text.SimpleDateFormat
import java.util.*

object SerialHelper {

    fun toHex(bytes: ByteArray): String {
        return bytes.joinToString(" ") { String.format("%02X", it) }
    }

    fun fromHex(hex: String): ByteArray {
        val cleanHex = hex.replace(" ", "")
        val result = ByteArray(cleanHex.length / 2)
        for (i in 0 until cleanHex.length step 2) {
            result[i / 2] = cleanHex.substring(i, i + 2).toInt(16).toByte()
        }
        return result
    }

    fun formatTimestamp(): String {
        return SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
    }

    fun filterNonPrintable(input: String): String {
        return input.filter { it.code in 32..126 || it == '\n' || it == '\r' || it == '\t' }
    }

    fun isValidHex(hex: String): Boolean {
        val cleanHex = hex.replace(" ", "")
        if (cleanHex.length % 2 != 0) return false
        return cleanHex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
    }

    fun getLocalIpAddress(context: android.content.Context): String {
        val prefs = context.getSharedPreferences("serial_settings", android.content.Context.MODE_PRIVATE)
        val manualIp = prefs.getString("manual_ip", "") ?: ""
        if (manualIp.isNotEmpty()) return manualIp

        try {
            val en = java.net.NetworkInterface.getNetworkInterfaces()
            var bestIp = "Unknown"
            while (en.hasMoreElements()) {
                val intf = en.nextElement()
                if (!intf.isUp || intf.isLoopback) continue
                val enumIpAddr = intf.inetAddresses
                while (enumIpAddr.hasMoreElements()) {
                    val inetAddress = enumIpAddr.nextElement()
                    if (inetAddress.isLoopbackAddress || inetAddress !is java.net.Inet4Address) continue
                    val name = intf.name.lowercase()
                    // Prefer wlan/wifi over other interfaces (vpn, tether, etc.)
                    if (name.contains("wlan") || name.contains("wifi") || name.contains("eth")) {
                        return inetAddress.hostAddress ?: "Unknown"
                    }
                    if (bestIp == "Unknown") {
                        bestIp = inetAddress.hostAddress ?: "Unknown"
                    }
                }
            }
            return bestIp
        } catch (ex: Exception) {
            ex.printStackTrace()
        }
        return "Unknown"
    }
}
