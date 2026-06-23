package com.serialmonitor

import android.app.PendingIntent
import android.content.*
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.os.IBinder
import android.view.Menu
import android.view.MenuItem
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.serialmonitor.adapters.MessageAdapter
import com.serialmonitor.databinding.ActivityMainBinding
import com.serialmonitor.databinding.DialogSettingsBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var serialService: SerialService? = null
    private var isBound = false
    private lateinit var messageAdapter: MessageAdapter
    
    private val baudRates = listOf(300, 1200, 2400, 4800, 9600, 19200, 38400, 57600, 74880, 115200, 230400, 250000, 460800, 921600)
    private var availablePorts = mutableListOf<UsbSerialPort>()

    // Serial settings
    private var baudRate = 115200
    private var dataBits = UsbSerialPort.DATABITS_8
    private var stopBits = UsbSerialPort.STOPBITS_1
    private var parity = UsbSerialPort.PARITY_NONE
    private var lineEnding = 3 // Standard default (both)
    
    private val commandHistory = mutableListOf<String>()
    private var historyIndex = -1

    private val ansiRegex = Regex("\u001B\\[[;?]*[0-9.;]*[a-zA-Z]")

    private var totalRx = 0L
    private var totalTx = 0L

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
            registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(usbReceiver, filter)
        }

        setupUI()
        setupSerial()
        
        if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            handleUsbIntent(intent)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                showSettingsDialog()
                true
            }
            R.id.action_clear -> {
                messageAdapter.clear()
                totalRx = 0
                totalTx = 0
                updateStatsUI()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showSettingsDialog() {
        val dialogBinding = DialogSettingsBinding.inflate(layoutInflater)
        
        val baudAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, baudRates)
        baudAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        dialogBinding.baudSpinner.adapter = baudAdapter
        dialogBinding.baudSpinner.setSelection(baudRates.indexOf(baudRate).coerceAtLeast(0))

        val dataBitsValues = listOf(5, 6, 7, 8)
        val dataBitsAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, dataBitsValues)
        dialogBinding.dataBitsSpinner.adapter = dataBitsAdapter
        dialogBinding.dataBitsSpinner.setSelection(dataBitsValues.indexOf(when(dataBits) {
            UsbSerialPort.DATABITS_5 -> 5
            UsbSerialPort.DATABITS_6 -> 6
            UsbSerialPort.DATABITS_7 -> 7
            else -> 8
        }))

        val stopBitsValues = listOf("1", "1.5", "2")
        val stopBitsAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, stopBitsValues)
        dialogBinding.stopBitsSpinner.adapter = stopBitsAdapter
        dialogBinding.stopBitsSpinner.setSelection(when(stopBits) {
            UsbSerialPort.STOPBITS_1_5 -> 1
            UsbSerialPort.STOPBITS_2 -> 2
            else -> 0
        })

        val parityOptions = listOf(getString(R.string.none), getString(R.string.odd), getString(R.string.even), getString(R.string.mark), getString(R.string.space))
        val parityAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, parityOptions)
        dialogBinding.paritySpinner.adapter = parityAdapter
        dialogBinding.paritySpinner.setSelection(when(parity) {
            UsbSerialPort.PARITY_ODD -> 1
            UsbSerialPort.PARITY_EVEN -> 2
            UsbSerialPort.PARITY_MARK -> 3
            UsbSerialPort.PARITY_SPACE -> 4
            else -> 0
        })

        val lineEndingOptions = listOf(getString(R.string.none), getString(R.string.nl), getString(R.string.cr), getString(R.string.both))
        val lineEndingAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, lineEndingOptions)
        dialogBinding.lineEndingSpinner.adapter = lineEndingAdapter
        dialogBinding.lineEndingSpinner.setSelection(lineEnding)

        refreshPortsInDialog(dialogBinding)

        AlertDialog.Builder(this)
            .setTitle(R.string.settings)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.connect) { _, _ ->
                baudRate = baudRates[dialogBinding.baudSpinner.selectedItemPosition]
                dataBits = when(dialogBinding.dataBitsSpinner.selectedItemPosition) {
                    0 -> UsbSerialPort.DATABITS_5
                    1 -> UsbSerialPort.DATABITS_6
                    2 -> UsbSerialPort.DATABITS_7
                    else -> UsbSerialPort.DATABITS_8
                }
                stopBits = when(dialogBinding.stopBitsSpinner.selectedItemPosition) {
                    1 -> UsbSerialPort.STOPBITS_1_5
                    2 -> UsbSerialPort.STOPBITS_2
                    else -> UsbSerialPort.STOPBITS_1
                }
                parity = when(dialogBinding.paritySpinner.selectedItemPosition) {
                    1 -> UsbSerialPort.PARITY_ODD
                    2 -> UsbSerialPort.PARITY_EVEN
                    3 -> UsbSerialPort.PARITY_MARK
                    4 -> UsbSerialPort.PARITY_SPACE
                    else -> UsbSerialPort.PARITY_NONE
                }
                lineEnding = dialogBinding.lineEndingSpinner.selectedItemPosition
                
                if (dialogBinding.portSpinner.adapter != null && dialogBinding.portSpinner.adapter.count > 0) {
                    connectSerial(dialogBinding.portSpinner.selectedItemPosition)
                } else {
                    Toast.makeText(this, R.string.no_devices_found, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun refreshPortsInDialog(dialogBinding: DialogSettingsBinding) {
        val usbManager = getSystemService(USB_SERVICE) as UsbManager
        val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        val tempPorts = mutableListOf<UsbSerialPort>()
        val portNames = mutableListOf<String>()

        for (driver in drivers) {
            for (port in driver.ports) {
                tempPorts.add(port)
                portNames.add("${driver.device.productName ?: "USB Device"} (${port.portNumber})")
            }
        }

        availablePorts.clear()
        availablePorts.addAll(tempPorts)
        
        val portAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, portNames)
        portAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        dialogBinding.portSpinner.adapter = portAdapter
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleUsbIntent(intent)
    }

    private fun handleUsbIntent(intent: Intent?) {
        if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            refreshPorts()
            showSettingsDialog()
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

        binding.connectButton.setOnClickListener { showSettingsDialog() }
        binding.disconnectButton.setOnClickListener { disconnectSerial() }
        binding.settingsButton.setOnClickListener { showSettingsDialog() }
        binding.clearButton.setOnClickListener {
            messageAdapter.clear()
            totalRx = 0
            totalTx = 0
            updateStatsUI()
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
                kotlinx.coroutines.delay(1000)
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
                val text = String(data)
                val filteredText = ansiRegex.replace(text, "")
                
                stringBuffer.append(filteredText)
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

            for (driver in drivers) {
                for (port in driver.ports) {
                    newAvailablePorts.add(port)
                }
            }

            withContext(Dispatchers.Main) {
                availablePorts.clear()
                availablePorts.addAll(newAvailablePorts)
            }
        }
    }

    private fun connectSerial(portIndex: Int = -1) {
        if (availablePorts.isEmpty()) {
            refreshPorts()
            return
        }

        val index = if (portIndex != -1) portIndex else 0
        if (index >= availablePorts.size) return
        
        binding.connectButton.isEnabled = false
        val port = availablePorts[index]
        val usbManager = getSystemService(USB_SERVICE) as UsbManager
        
        if (!usbManager.hasPermission(port.driver.device)) {
            val flags = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
            val usbPermissionIntent = PendingIntent.getBroadcast(this, 0, Intent("com.serialmonitor.USB_PERMISSION"), flags)
            usbManager.requestPermission(port.driver.device, usbPermissionIntent)
            return
        }

        serialService?.connect(port, baudRate, dataBits, stopBits, parity)
    }

    private fun disconnectSerial() {
        serialService?.disconnect()
    }

    private fun sendData() {
        val text = binding.commandInput.text.toString()
        if (text.isEmpty()) return

        val suffix = when(lineEnding) {
            1 -> "\n"
            2 -> "\r"
            3 -> "\r\n"
            else -> ""
        }
        val data = (text + suffix).toByteArray()
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

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(usbReceiver)
        } catch (ignored: Exception) {}
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
    }
}
