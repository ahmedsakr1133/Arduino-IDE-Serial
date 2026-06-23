package com.serialmonitor

import android.app.PendingIntent
import android.content.*
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.os.IBinder
import android.view.Menu
import android.view.MenuItem
import android.view.View
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
    private var serverPort = 8080
    private var currentServerAddress = ""

    override fun attachBaseContext(newBase: Context) {
        val prefs = newBase.getSharedPreferences("serial_settings", Context.MODE_PRIVATE)
        val lang = prefs.getString("language", "ar") ?: "ar"
        val locale = java.util.Locale(lang)
        java.util.Locale.setDefault(locale)
        val config = newBase.resources.configuration
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        val context = newBase.createConfigurationContext(config)
        super.attachBaseContext(context)
    }

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
        applyTheme()
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

    private fun applyTheme() {
        val prefs = getSharedPreferences("serial_settings", Context.MODE_PRIVATE)
        val theme = prefs.getString("theme", "dark") ?: "dark"
        when(theme) {
            "light" -> androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO)
            "dark", "night" -> androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
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

    override fun onResume() {
        super.onResume()
        loadSettings()
        
        // Update switch colors based on current state
        updateSwitchColors(binding.connectionSwitch, binding.connectionSwitch.isChecked)
        updateSwitchColors(binding.serverSwitch, binding.serverSwitch.isChecked)
    }

    private fun loadSettings() {
        val prefs = getSharedPreferences("serial_settings", Context.MODE_PRIVATE)
        baudRate = prefs.getInt("baud_rate", 115200)
        dataBits = prefs.getInt("data_bits", UsbSerialPort.DATABITS_8)
        stopBits = prefs.getInt("stop_bits", UsbSerialPort.STOPBITS_1)
        parity = prefs.getInt("parity", UsbSerialPort.PARITY_NONE)
        lineEnding = prefs.getInt("line_ending", 3)
        serverPort = prefs.getInt("server_port", 8080)
    }

    private fun showPortSelectionDialog() {
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

        if (tempPorts.isEmpty()) {
            Toast.makeText(this, R.string.no_devices_found, Toast.LENGTH_SHORT).show()
            return
        }

        availablePorts.clear()
        availablePorts.addAll(tempPorts)

        if (availablePorts.size == 1) {
            connectSerial(0)
        } else {
            AlertDialog.Builder(this)
                .setTitle(R.string.connect)
                .setItems(portNames.toTypedArray()) { _, which ->
                    connectSerial(which)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
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
            showPortSelectionDialog()
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

        binding.settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.clearButton.setOnClickListener {
            messageAdapter.clear()
            totalRx = 0
            totalTx = 0
            updateStatsUI()
        }

        binding.connectionSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                showPortSelectionDialog()
            } else {
                disconnectSerial()
            }
            updateSwitchColors(binding.connectionSwitch, isChecked)
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

        binding.serverSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                serialService?.startServer(serverPort)
            } else {
                serialService?.stopServer()
                binding.copyServerAddress.visibility = View.GONE
            }
            updateSwitchColors(binding.serverSwitch, isChecked)
        }

        binding.copyServerAddress.setOnClickListener {
            if (currentServerAddress.isNotEmpty()) {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("Server Address", currentServerAddress)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this, "Copied: $currentServerAddress", Toast.LENGTH_SHORT).show()
            }
        }

        refreshPorts()
    }

    private fun updateSwitchColors(switch: androidx.appcompat.widget.SwitchCompat, isChecked: Boolean) {
        val color = if (isChecked) {
            androidx.core.content.ContextCompat.getColor(this, R.color.switch_on)
        } else {
            androidx.core.content.ContextCompat.getColor(this, R.color.switch_off)
        }
        switch.thumbTintList = android.content.res.ColorStateList.valueOf(color)
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
            val rxBuffer = StringBuilder()
            var lastUpdate = System.currentTimeMillis()

            serialService?.dataBytes?.collect { data ->
                val text = String(data, Charsets.UTF_8)
                val filteredText = ansiRegex.replace(text, "")
                rxBuffer.append(filteredText)
                totalRx += data.size

                val now = System.currentTimeMillis()
                if (rxBuffer.contains("\n") || rxBuffer.contains("\r") || rxBuffer.length > 1024 || (rxBuffer.isNotEmpty() && now - lastUpdate > 300)) {
                    val content = rxBuffer.toString()
                    rxBuffer.setLength(0)
                    lastUpdate = now
                    
                    val lines = content.replace("\r\n", "\n").replace("\r", "\n")
                        .split(Regex("(?<=\n)"))
                        .filter { it.isNotEmpty() }
                        .map { Message(it, Message.Type.RX) }

                    if (lines.isNotEmpty()) {
                        withContext(Dispatchers.Main) {
                            messageAdapter.addMessages(lines)
                            if (binding.autoScrollCheck.isChecked) {
                                binding.terminalRecyclerView.scrollToPosition(messageAdapter.itemCount - 1)
                            }
                            updateStatsUI()
                        }
                    } else {
                        withContext(Dispatchers.Main) { updateStatsUI() }
                    }
                }
            }
        }

        lifecycleScope.launch {
            serialService?.events?.collect { event ->
                handleEvent(event)
            }
        }
    }

    private fun updateStatsUI() {
        binding.receivedStats.text = getString(R.string.received_bytes, totalRx)
        binding.sentStats.text = getString(R.string.sent_bytes, totalTx)
    }

    private fun getLocalIpAddress(): String {
        try {
            val en = java.net.NetworkInterface.getNetworkInterfaces()
            while (en.hasMoreElements()) {
                val intf = en.nextElement()
                val enumIpAddr = intf.inetAddresses
                while (enumIpAddr.hasMoreElements()) {
                    val inetAddress = enumIpAddr.nextElement()
                    if (!inetAddress.isLoopbackAddress && inetAddress is java.net.Inet4Address) {
                        return inetAddress.hostAddress ?: "Unknown"
                    }
                }
            }
        } catch (ex: Exception) {
            ex.printStackTrace()
        }
        return "Unknown"
    }

    private fun handleEvent(event: String) {
        lifecycleScope.launch(Dispatchers.Main) {
            when {
                event == "CONNECTED" -> {
                    binding.connectionStatus.text = getString(R.string.connected)
                    binding.connectionStatus.setTextColor(androidx.core.content.ContextCompat.getColor(this@MainActivity, R.color.terminal_rx))
                    binding.connectionSwitch.isChecked = true
                    updateSwitchColors(binding.connectionSwitch, true)
                    messageAdapter.addMessage(Message(getString(R.string.connected), Message.Type.SYS))
                    Toast.makeText(this@MainActivity, R.string.successfully_connected, Toast.LENGTH_SHORT).show()
                }
                event == "DISCONNECTED" -> {
                    binding.connectionStatus.text = getString(R.string.disconnected)
                    binding.connectionStatus.setTextColor(androidx.core.content.ContextCompat.getColor(this@MainActivity, R.color.terminal_err))
                    binding.connectionSwitch.isChecked = false
                    updateSwitchColors(binding.connectionSwitch, false)
                    messageAdapter.addMessage(Message(getString(R.string.disconnected), Message.Type.SYS))
                }
                event.startsWith("SERVER_STARTED") -> {
                    val port = event.substringAfter(":")
                    val ip = getLocalIpAddress()
                    currentServerAddress = "http://$ip:$port"
                    val msg = getString(R.string.server_active, "$ip:$port")
                    messageAdapter.addMessage(Message(msg, Message.Type.SYS))
                    binding.serverSwitch.isChecked = true
                    updateSwitchColors(binding.serverSwitch, true)
                    binding.copyServerAddress.visibility = View.VISIBLE
                }
                event == "SERVER_STOPPED" -> {
                    messageAdapter.addMessage(Message(getString(R.string.server_stopped), Message.Type.SYS))
                    binding.serverSwitch.isChecked = false
                    updateSwitchColors(binding.serverSwitch, false)
                    binding.copyServerAddress.visibility = View.GONE
                    currentServerAddress = ""
                }
                event.startsWith("SERVER_CMD") -> {
                    val cmd = event.substringAfter(":")
                    messageAdapter.addMessage(Message("SERVER CMD: $cmd", Message.Type.TX))
                    totalTx += cmd.length
                    updateStatsUI()
                }
                event.startsWith("ERROR") -> {
                    binding.connectionSwitch.isChecked = false
                    updateSwitchColors(binding.connectionSwitch, false)
                    messageAdapter.addMessage(Message(event, Message.Type.ERR))
                    Toast.makeText(this@MainActivity, event, Toast.LENGTH_LONG).show()
                }
                event.startsWith("SERVER_ERROR") -> {
                    messageAdapter.addMessage(Message(event, Message.Type.ERR))
                    binding.serverSwitch.isChecked = false
                    updateSwitchColors(binding.serverSwitch, false)
                }
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
        if (serialService == null) {
            Toast.makeText(this, R.string.service_not_ready, Toast.LENGTH_SHORT).show()
            setupSerial()
            binding.connectionSwitch.isChecked = false
            return
        }

        if (availablePorts.isEmpty()) {
            refreshPorts()
            Toast.makeText(this, R.string.no_devices_found, Toast.LENGTH_SHORT).show()
            binding.connectionSwitch.isChecked = false
            return
        }

        val index = if (portIndex != -1) portIndex else 0
        if (index >= availablePorts.size) {
            binding.connectionSwitch.isChecked = false
            return
        }
        
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
