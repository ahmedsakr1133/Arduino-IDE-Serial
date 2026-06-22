package com.serialmonitor

data class Message(
    val content: String,
    val type: Type,
    val timestamp: String = SerialHelper.formatTimestamp()
) {
    enum class Type {
        RX, TX, SYS, ERR
    }
}
