package com.serialmonitor

import android.app.PendingIntent
import android.content.*
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.serialmonitor.adapters.MessageAdapter
import com.serialmonitor.databinding.ActivityMainBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var serialService: SerialService? = null
    private var isBound = false
    private lateinit var messageAdapter: MessageAdapter
    
    private val baudRates = listOf(300, 600, 1200, 2400, 4800, 9600, 14400, 19200, 38400, 57600, 115200, 128000, 256000)
    private var availablePorts = mutableListOf<UsbSerialPort>()
    
    private val commandHistory = mutableListOf<String>()
    private var historyIndex = -1

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as SerialService.SerialBinder
            serialService = binder.getService()
            isBound = true
            observeService()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            isBound = false
            serialService = null
        }
    }

    private var autoSendJob: kotlinx.coroutines.Job? = null
    private var isLogging = false
    private var logFile: java.io.File? = null

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if ("com.serialmonitor.USB_PERMISSION" == intent.action) {
                synchronized(this) {
                    val device = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, android.hardware.usb.UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE) as? android.hardware.usb.UsbDevice
                    }
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        device?.let { connectSerial() }
                    } else {
                        Toast.makeText(context, R.string.usb_permission_denied, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            val permission = android.Manifest.permission.POST_NOTIFICATIONS
            if (androidx.core.content.ContextCompat.checkSelfPermission(this, permission) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                androidx.core.app.ActivityCompat.requestPermissions(this, arrayOf(permission), 101)
            }
        }

        val filter = IntentFilter("com.serialmonitor.USB_PERMISSION")
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, android.content.Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(usbReceiver, filter)
        }

        setupUI()
        setupSerial()
        
        // Handle initial USB intent
        if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            handleUsbIntent(intent)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleUsbIntent(intent)
    }

    private fun handleUsbIntent(intent: Intent?) {
        if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            refreshPorts()
        }
    }

    private fun setupUI() {
        messageAdapter = MessageAdapter(mutableListOf())
        binding.terminalRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@MainActivity).apply {
                stackFromEnd = true
            }
            adapter = messageAdapter
        }

        val baudAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, baudRates)
        baudAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.baudSpinner.adapter = baudAdapter
        binding.baudSpinner.setSelection(baudRates.indexOf(9600))

        binding.connectButton.setOnClickListener { connectSerial() }
        binding.disconnectButton.setOnClickListener { disconnectSerial() }
        binding.clearButton.setOnClickListener {
            messageAdapter.clear()
            Toast.makeText(this, R.string.screen_cleared, Toast.LENGTH_SHORT).show()
        }

        binding.sendButton.setOnClickListener { sendData() }
        binding.commandInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND) {
                sendData()
                true
            } else {
                false
            }
        }
        
        binding.prevCommand.setOnClickListener { navigateHistory(true) }
        binding.nextCommand.setOnClickListener { navigateHistory(false) }
        
        binding.autoScrollCheck.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) startAutoSend() else stopAutoSend()
        }

        refreshPorts()
    }

    private fun startAutoSend() {
        autoSendJob?.cancel()
        autoSendJob = lifecycleScope.launch {
            while (true) {
                kotlinx.coroutines.delay(1000) // Default 1 second
                sendData()
            }
        }
    }

    private fun stopAutoSend() {
        autoSendJob?.cancel()
        autoSendJob = null
    }

    private fun setupSerial() {
        val intent = Intent(this, SerialService::class.java)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        bindService(intent, serviceConnection, BIND_AUTO_CREATE)
    }

    private fun observeService() {
        lifecycleScope.launch {
            val stringBuffer = StringBuilder()
            var lastUpdate = System.currentTimeMillis()

            serialService?.dataBytes?.collect { data ->
                stringBuffer.append(String(data))
                totalRx += data.size
                
                val now = System.currentTimeMillis()
                if (now - lastUpdate > 100 || stringBuffer.length > 2000) {
                    flushBuffer(stringBuffer)
                    lastUpdate = now
                }
            }
        }

        lifecycleScope.launch {
            serialService?.events?.collect { event ->
                handleEvent(event)
            }
        }
    }

    private fun flushBuffer(buffer: StringBuilder) {
        if (buffer.isEmpty()) {
            lifecycleScope.launch(Dispatchers.Main) { updateStatsUI() }
            return
        }
        val content = buffer.toString()
        buffer.setLength(0)
        
        lifecycleScope.launch(Dispatchers.Main) {
            messageAdapter.addMessage(Message(content, Message.Type.RX))
            if (messageAdapter.itemCount > 0 && binding.autoScrollCheck.isChecked) {
                binding.terminalRecyclerView.scrollToPosition(messageAdapter.itemCount - 1)
            }
            updateStatsUI()
        }
    }

    private fun updateStatsUI() {
        binding.receivedStats.text = getString(R.string.received_bytes, totalRx)
        binding.sentStats.text = getString(R.string.sent_bytes, totalTx)
    }

    private fun handleEvent(event: String) {
        when {
            event == "CONNECTED" -> {
                binding.connectionStatus.text = getString(R.string.connected)
                binding.connectionStatus.setTextColor(android.graphics.Color.GREEN)
                binding.connectButton.isEnabled = false
                binding.disconnectButton.isEnabled = true
                messageAdapter.addMessage(Message(getString(R.string.connected), Message.Type.SYS))
            }
            event == "DISCONNECTED" -> {
                binding.connectionStatus.text = getString(R.string.disconnected)
                binding.connectionStatus.setTextColor(android.graphics.Color.RED)
                binding.connectButton.isEnabled = true
                binding.disconnectButton.isEnabled = false
                messageAdapter.addMessage(Message(getString(R.string.connection_lost), Message.Type.SYS))
            }
            event.startsWith("ERROR") -> {
                binding.connectButton.isEnabled = true
                binding.disconnectButton.isEnabled = false
                messageAdapter.addMessage(Message(event, Message.Type.ERR))
                Toast.makeText(this, event, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun refreshPorts() {
        lifecycleScope.launch(Dispatchers.IO) {
            val usbManager = getSystemService(USB_SERVICE) as UsbManager
            val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
            val newAvailablePorts = mutableListOf<UsbSerialPort>()
            val portNames = mutableListOf<String>()

            for (driver in drivers) {
                val device = driver.device
                for (port in driver.ports) {
                    newAvailablePorts.add(port)
                    portNames.add("${device.productName ?: "USB Device"} (${port.portNumber})")
                }
            }

            withContext(Dispatchers.Main) {
                availablePorts.clear()
                availablePorts.addAll(newAvailablePorts)
                val portAdapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_item, portNames)
                portAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                binding.portSpinner.adapter = portAdapter

                if (portNames.isEmpty()) {
                    Toast.makeText(this@MainActivity, R.string.no_devices_found, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun connectSerial() {
        if (availablePorts.isEmpty()) {
            refreshPorts()
            return
        }

        binding.connectButton.isEnabled = false
        val port = availablePorts[binding.portSpinner.selectedItemPosition]
        val usbManager = getSystemService(USB_SERVICE) as UsbManager
        
        if (!usbManager.hasPermission(port.driver.device)) {
            val flags = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
            val usbPermissionIntent = PendingIntent.getBroadcast(this, 0, Intent("com.serialmonitor.USB_PERMISSION"), flags)
            usbManager.requestPermission(port.driver.device, usbPermissionIntent)
            return
        }

        val baudRate = baudRates[binding.baudSpinner.selectedItemPosition]
        serialService?.connect(port, baudRate, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
    }

    private fun disconnectSerial() {
        serialService?.disconnect()
    }

    private fun sendData() {
        val text = binding.commandInput.text.toString()
        if (text.isEmpty()) return

        val data = (text + "\r\n").toByteArray()
        serialService?.write(data)
        
        messageAdapter.addMessage(Message(text, Message.Type.TX))
        if (binding.autoScrollCheck.isChecked) {
            binding.terminalRecyclerView.scrollToPosition(messageAdapter.itemCount - 1)
        }

        if (commandHistory.isEmpty() || commandHistory.last() != text) {
            commandHistory.add(text)
            if (commandHistory.size > 20) commandHistory.removeAt(0)
        }
        historyIndex = commandHistory.size
        
        binding.commandInput.text.clear()
        totalTx += data.size
        updateStatsUI()
    }

    private fun navigateHistory(prev: Boolean) {
        if (commandHistory.isEmpty()) return
        if (prev) {
            if (historyIndex > 0) historyIndex--
        } else {
            if (historyIndex < commandHistory.size - 1) historyIndex++
        }
        
        if (historyIndex in commandHistory.indices) {
            binding.commandInput.setText(commandHistory[historyIndex])
            binding.commandInput.setSelection(binding.commandInput.text.length)
        }
    }

    private var totalRx = 0
    private var totalTx = 0

    private fun updateStats(rx: Int, tx: Int) {
        totalRx += rx
        totalTx += tx
        updateStatsUI()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(usbReceiver)
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
    }
}
