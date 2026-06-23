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

    private val _messagesFlow = MutableSharedFlow<Message>(extraBufferCapacity = 100)
    val messagesFlow = _messagesFlow.asSharedFlow()

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
            if (_messages.size > 1000) { // Limit history
                _messages.removeAt(0)
            }
        }
        serviceScope.launch(Dispatchers.Main) {
            _messagesFlow.emit(msg)
        }
    }

    fun isSerialConnected() = usbSerialPort != null
    fun isServerRunning() = serverSocket != null && !serverSocket!!.isClosed
    fun getServerPort() = serverSocket?.localPort ?: 0

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
    private var serverSocket: java.net.ServerSocket? = null
    private var serverJob: Job? = null

    private fun updateNotification(content: String) {
        try {
            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(content)
                .setSmallIcon(R.drawable.ic_serial_notification)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .build()
            val manager = getSystemService(NotificationManager::class.java)
            manager.notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun checkShouldStopService() {
        serviceScope.launch(Dispatchers.Main) {
            // Only stop if both features are off
            if (usbSerialPort == null && serverSocket == null) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                // We keep the service alive while activity is bound or until explicitly stopped by OS
            }
        }
    }

    fun startServer(port: Int) {
        serverJob?.cancel()
        serverJob = serviceScope.launch {
            try {
                serverSocket = java.net.ServerSocket(port)
                val ip = SerialHelper.getLocalIpAddress(this@SerialService)
                addMessage(getString(R.string.server_active, "$ip:$port"), Message.Type.SYS)
                emitEvent("SERVER_STARTED:$port")
                updateNotification("Server Active at $ip:$port")
                while (isActive) {
                    val client = serverSocket?.accept() ?: break
                    launch {
                        try {
                            val inputStream = client.getInputStream()
                            val outputStream = client.getOutputStream()
                            val buffer = ByteArray(2048)
                            var isFirstRead = true
                            
                            while (isActive && usbSerialPort != null) {
                                val bytesRead = inputStream.read(buffer)
                                if (bytesRead <= 0) break
                                
                                val received = String(buffer, 0, bytesRead)
                                
                                if (isFirstRead && received.startsWith("GET /send?cmd=")) {
                                    // Handle HTTP GET
                                    val cmd = received.substringAfter("cmd=").substringBefore(" ")
                                    val decodedCmd = java.net.URLDecoder.decode(cmd, "UTF-8")
                                    
                                    val prefs = getSharedPreferences("serial_settings", Context.MODE_PRIVATE)
                                    val lineEnding = prefs.getInt("line_ending", 3)
                                    val suffix = when(lineEnding) {
                                        1 -> "\n"
                                        2 -> "\r"
                                        3 -> "\r\n"
                                        else -> ""
                                    }
                                    
                                    val dataToSend = (decodedCmd + suffix).toByteArray()
                                    write(dataToSend)
                                    addMessage("[SERVER] $decodedCmd", Message.Type.TX)
                                    emitEvent("SERVER_CMD:$decodedCmd")
                                    
                                    val response = "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nAccess-Control-Allow-Origin: *\r\n\r\nOK: $decodedCmd"
                                    outputStream.write(response.toByteArray())
                                    break // HTTP usually closes after one response
                                } else if (isFirstRead && received.startsWith("GET /")) {
                                    val response = "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\n\r\nSerial Monitor Server Running\nUse /send?cmd=YOUR_COMMAND"
                                    outputStream.write(response.toByteArray())
                                    break
                                } else {
                                    // Handle raw TCP data
                                    val data = buffer.copyOfRange(0, bytesRead)
                                    write(data)
                                    
                                    val displayStr = received.trimEnd('\n', '\r')
                                    if (displayStr.isNotEmpty()) {
                                        addMessage("[SERVER] $displayStr", Message.Type.TX)
                                        emitEvent("SERVER_CMD:$displayStr")
                                    }
                                }
                                isFirstRead = false
                            }
                        } catch (e: Exception) {
                            // Client disconnected or error
                        } finally {
                            try { client.close() } catch (ignored: Exception) {}
                        }
                    }
                }
            } catch (e: Exception) {
                if (isActive) {
                    emitEvent("SERVER_ERROR: ${e.message}")
                }
            }
        }
    }

    fun stopServer() {
        serverJob?.cancel()
        serverJob = null
        try { serverSocket?.close() } catch (ignored: Exception) {}
        serverSocket = null
        addMessage(getString(R.string.server_stopped), Message.Type.SYS)
        emitEvent("SERVER_STOPPED")
        
        val status = if (usbSerialPort != null) "Connected" else getString(R.string.disconnected)
        updateNotification(status)
        checkShouldStopService()
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
                updateNotification("Connected to ${port.driver.device.deviceName}")
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
            ioManager?.stop()
            ioManager = null
            try { usbSerialPort?.close() } catch (ignored: Exception) {}
            usbSerialPort = null
            connection?.close()
            connection = null
            addMessage(getString(R.string.disconnected), Message.Type.SYS)
            emitEvent("DISCONNECTED")
            
            val status = if (isServerRunning()) "Server Running" else getString(R.string.disconnected)
            updateNotification(status)
            checkShouldStopService()
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
        val text = String(data)
        addMessage(text, Message.Type.RX)
        _dataBytes.tryEmit(data)
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
