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
}
