package com.serialmonitor

import android.app.*
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.util.SerialInputOutputManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.IOException

class SerialService : Service(), SerialInputOutputManager.Listener {

    private val binder = SerialBinder()
    private var usbSerialPort: UsbSerialPort? = null
    private var ioManager: SerialInputOutputManager? = null
    private var connection: UsbDeviceConnection? = null

    private val _dataBytes = MutableSharedFlow<ByteArray>(extraBufferCapacity = 100)
    val dataBytes = _dataBytes.asSharedFlow()

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 10)
    val events = _events.asSharedFlow()

    private val _messagesFlow = MutableSharedFlow<Message>(extraBufferCapacity = 500)
    val messagesFlow = _messagesFlow.asSharedFlow()

    private val rxBuffer = StringBuilder()
    private var lastRxEmitTime = 0L
    private val ansiRegex = Regex("\u001B\\[[;\\d]*m")

    private val _messages = mutableListOf<Message>()
    fun getMessageHistory(): List<Message> = synchronized(_messages) { _messages.toList() }
    fun clearMessageHistory() = synchronized(_messages) { 
        _messages.clear()
        emitEvent("HISTORY_CLEARED")
    }

    fun addManualMessage(content: String, type: Message.Type) {
        addMessage(content, type)
    }

    private fun addMessage(content: String, type: Message.Type) {
        val msg = Message(content, type)
        synchronized(_messages) {
            _messages.add(msg)
            if (_messages.size > 2000) { // Increased history limit
                _messages.removeAt(0)
            }
        }
        _messagesFlow.tryEmit(msg)
    }

    fun isSerialConnected() = usbSerialPort != null
    fun isServerRunning() = localServer?.wasStarted() == true && localServer?.isAlive == true
    fun getServerPort() = localServer?.listeningPort ?: 0

    inner class SerialBinder : Binder() {
        fun getService(): SerialService = this@SerialService
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.disconnected))
                .setSmallIcon(R.drawable.ic_serial_notification)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .build()

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID, 
                    notification, 
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            // Silently fail to start foreground if something goes wrong to avoid crash
            e.printStackTrace()
        }
        return START_NOT_STICKY
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var localServer: LocalServer? = null

    fun startServer(port: Int) {
        if (isServerRunning() && getServerPort() == port) return

        stopServer(silent = true)
        try {
            localServer = LocalServer(port) { cmd ->
                handleServerCommand(cmd)
            }
            localServer?.start()
            val ip = SerialHelper.getLocalIpAddress(this@SerialService)
            addMessage(getString(R.string.server_active, "$ip:$port"), Message.Type.SYS)
            emitEvent("SERVER_STARTED:$port")
        } catch (e: Exception) {
            emitEvent("SERVER_ERROR: ${e.message}")
        }
    }

    private fun handleServerCommand(command: String) {
        val prefs = getSharedPreferences("serial_settings", Context.MODE_PRIVATE)
        val lineEnding = prefs.getInt("line_ending", 3)
        val suffix = when (lineEnding) {
            1 -> "\n"
            2 -> "\r"
            3 -> "\r\n"
            else -> ""
        }
        val dataToSend = (command + suffix).toByteArray()
        write(dataToSend)
        addMessage("[SERVER] $command", Message.Type.TX)
        emitEvent("SERVER_CMD:$command")
    }

    fun stopServer(silent: Boolean = false) {
        localServer?.stop()
        localServer = null
        if (!silent) {
            addMessage(getString(R.string.server_stopped), Message.Type.SYS)
            emitEvent("SERVER_STOPPED")
        }
    }

    fun connect(port: UsbSerialPort, baudRate: Int, dataBits: Int, stopBits: Int, parity: Int) {
        serviceScope.launch {
            val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
            try {
                connection = usbManager.openDevice(port.driver.device)
                if (connection == null) {
                    emitEvent("ERROR: Connection failed (Open Device Null)")
                    return@launch
                }

                usbSerialPort = port
                port.open(connection)
                port.setParameters(baudRate, dataBits, stopBits, parity)
                
                ioManager = SerialInputOutputManager(port, this@SerialService)
                ioManager?.start()
                addMessage("Connected to ${port.driver.device.deviceName}", Message.Type.SYS)
                emitEvent("CONNECTED")
            } catch (e: Exception) {
                emitEvent("ERROR: ${e.message}")
                try { port.close() } catch (ignored: Exception) {}
                usbSerialPort = null
                connection?.close()
                connection = null
            }
        }
    }

    fun disconnect() {
        serviceScope.launch {
            synchronized(rxBuffer) { rxBuffer.setLength(0) }
            ioManager?.stop()
            ioManager = null
            try { usbSerialPort?.close() } catch (ignored: Exception) {}
            usbSerialPort = null
            connection?.close()
            connection = null
            addMessage(getString(R.string.disconnected), Message.Type.SYS)
            emitEvent("DISCONNECTED")
            withContext(Dispatchers.Main) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    fun write(data: ByteArray) {
        serviceScope.launch {
            try {
                usbSerialPort?.write(data, 1000)
                // We don't add to history here as MainActivity handles user TX
                // But for Server CMD we did add.
                // Wait, if we want history to survive, MainActivity should NOT be the one adding.
                // The Service should be the source of truth.
            } catch (e: IOException) {
                emitEvent("ERROR: Write failed: ${e.message}")
            }
        }
    }

    override fun onNewData(data: ByteArray) {
        _dataBytes.tryEmit(data)
        
        val text = String(data, Charsets.UTF_8)
        val filtered = ansiRegex.replace(text, "")
        
        synchronized(rxBuffer) {
            rxBuffer.append(filtered)
        }
        
        val now = System.currentTimeMillis()
        if (now - lastRxEmitTime > 200 || filtered.contains('\n') || filtered.contains('\r')) {
            emitBufferedRx()
            lastRxEmitTime = now
        }
    }

    private fun emitBufferedRx() {
        val content: String
        synchronized(rxBuffer) {
            content = rxBuffer.toString()
            rxBuffer.setLength(0)
        }
        if (content.isEmpty()) return
        
        val lines = content.replace("\r\n", "\n").replace("\r", "\n")
            .split(Regex("(?<=\n)"))
            .filter { it.isNotEmpty() }
            
        lines.forEach { line ->
            addMessage(line, Message.Type.RX)
        }
    }

    override fun onRunError(e: Exception) {
        addMessage("Error: ${e.message}", Message.Type.ERR)
        emitEvent("ERROR: ${e.message}")
        disconnect()
    }

    private fun emitEvent(event: String) {
        serviceScope.launch(Dispatchers.Main) {
            _events.emit(event)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    private fun createNotificationChannel() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Serial Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_ID = "serial_service_channel"
        const val NOTIFICATION_ID = 1
    }
}
