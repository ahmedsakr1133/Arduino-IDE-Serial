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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.io.IOException

class SerialService : Service(), SerialInputOutputManager.Listener {

    private val binder = SerialBinder()
    private var usbSerialPort: UsbSerialPort? = null
    private var ioManager: SerialInputOutputManager? = null
    private var connection: UsbDeviceConnection? = null

    private val _dataBytes = MutableSharedFlow<ByteArray>()
    val dataBytes = _dataBytes.asSharedFlow()

    private val _events = MutableSharedFlow<String>()
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

    fun connect(port: UsbSerialPort, baudRate: Int, dataBits: Int, stopBits: Int, parity: Int) {
        val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        connection = usbManager.openDevice(port.driver.device)
        if (connection == null) {
            emitEvent("ERROR: Connection failed")
            return
        }

        usbSerialPort = port
        try {
            port.open(connection)
            port.setParameters(baudRate, dataBits, stopBits, parity)
            
            ioManager = SerialInputOutputManager(port, this)
            ioManager?.start()
            emitEvent("CONNECTED")
        } catch (e: IOException) {
            emitEvent("ERROR: ${e.message}")
            try { port.close() } catch (ignored: Exception) {}
            usbSerialPort = null
        }
    }

    fun disconnect() {
        ioManager?.stop()
        ioManager = null
        try { usbSerialPort?.close() } catch (ignored: Exception) {}
        usbSerialPort = null
        connection?.close()
        connection = null
        emitEvent("DISCONNECTED")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    fun write(data: ByteArray) {
        try {
            usbSerialPort?.write(data, 1000)
        } catch (e: IOException) {
            emitEvent("ERROR: Write failed: ${e.message}")
        }
    }

    override fun onNewData(data: ByteArray) {
        CoroutineScope(Dispatchers.IO).launch {
            _dataBytes.emit(data)
        }
    }

    override fun onRunError(e: Exception) {
        emitEvent("ERROR: ${e.message}")
        disconnect()
    }

    private fun emitEvent(event: String) {
        CoroutineScope(Dispatchers.Main).launch {
            _events.emit(event)
        }
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
