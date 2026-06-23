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

    fun startServer(port: Int) {
        serverJob?.cancel()
        serverJob = serviceScope.launch {
            try {
                serverSocket = java.net.ServerSocket(port)
                emitEvent("SERVER_STARTED:$port")
                while (isActive) {
                    val client = serverSocket?.accept() ?: break
                    launch {
                        try {
                            val inputStream = client.getInputStream()
                            val outputStream = client.getOutputStream()
                            val buffer = ByteArray(1024)
                            val bytesRead = inputStream.read(buffer)
                            
                            if (bytesRead > 0) {
                                val received = String(buffer, 0, bytesRead)
                                if (received.startsWith("GET /send?cmd=")) {
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
                                    if (usbSerialPort != null) {
                                        write(dataToSend)
                                        emitEvent("SERVER_CMD:$decodedCmd")
                                    } else {
                                        emitEvent("SERVER_ERROR: Serial not connected (Cmd: $decodedCmd)")
                                    }
                                    
                                    val response = "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nAccess-Control-Allow-Origin: *\r\n\r\nOK: $decodedCmd"
                                    outputStream.write(response.toByteArray())
                                } else if (received.startsWith("GET /")) {
                                    // Basic HTTP response for other paths
                                    val response = "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\n\r\nSerial Monitor Server Running\nUse /send?cmd=YOUR_COMMAND"
                                    outputStream.write(response.toByteArray())
                                } else {
                                    // Handle raw TCP data
                                    val decodedCmd = received.trimEnd('\n', '\r')
                                    if (usbSerialPort != null) {
                                        write(buffer.copyOfRange(0, bytesRead))
                                        emitEvent("SERVER_CMD:$decodedCmd")
                                    } else {
                                        emitEvent("SERVER_ERROR: Serial not connected")
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            // Ignore client errors
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
        emitEvent("SERVER_STOPPED")
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
            ioManager?.stop()
            ioManager = null
            try { usbSerialPort?.close() } catch (ignored: Exception) {}
            usbSerialPort = null
            connection?.close()
            connection = null
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
            } catch (e: IOException) {
                emitEvent("ERROR: Write failed: ${e.message}")
            }
        }
    }

    override fun onNewData(data: ByteArray) {
        _dataBytes.tryEmit(data)
    }

    override fun onRunError(e: Exception) {
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
