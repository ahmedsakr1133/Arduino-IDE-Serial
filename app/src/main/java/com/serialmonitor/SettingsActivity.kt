package com.serialmonitor

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.serialmonitor.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private val baudRates = listOf(300, 1200, 2400, 4800, 9600, 19200, 38400, 57600, 74880, 115200, 230400, 250000, 460800, 921600)
    private val languages = listOf("ar", "en")
    private val themes = listOf("light", "dark", "night")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupSpinners()
        loadSettings()

        binding.saveButton.setOnClickListener {
            saveSettings()
            Toast.makeText(this, R.string.save, Toast.LENGTH_SHORT).show()
            finish()
            // Restart MainActivity to apply language/theme
            val intent = Intent(this, MainActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        }
        
        binding.closeButton.setOnClickListener {
            finish()
        }
    }

    private fun setupSpinners() {
        val langOptions = listOf(getString(R.string.arabic), getString(R.string.english))
        val langAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, langOptions)
        langAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.languageSpinner.adapter = langAdapter

        val themeOptions = listOf(getString(R.string.light_mode), getString(R.string.dark_mode), getString(R.string.night_mode))
        val themeAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, themeOptions)
        themeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.themeSpinner.adapter = themeAdapter

        val baudAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, baudRates)
        baudAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.baudSpinner.adapter = baudAdapter

        val dataBitsValues = listOf(5, 6, 7, 8)
        val dataBitsAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, dataBitsValues)
        binding.dataBitsSpinner.adapter = dataBitsAdapter

        val stopBitsValues = listOf("1", "1.5", "2")
        val stopBitsAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, stopBitsValues)
        binding.stopBitsSpinner.adapter = stopBitsAdapter

        val parityOptions = listOf(getString(R.string.none), getString(R.string.odd), getString(R.string.even), getString(R.string.mark), getString(R.string.space))
        val parityAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, parityOptions)
        binding.paritySpinner.adapter = parityAdapter

        val lineEndingOptions = listOf(getString(R.string.none), getString(R.string.nl), getString(R.string.cr), getString(R.string.both))
        val lineEndingAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, lineEndingOptions)
        binding.lineEndingSpinner.adapter = lineEndingAdapter
    }

    private fun loadSettings() {
        val prefs = getSharedPreferences("serial_settings", Context.MODE_PRIVATE)
        
        val lang = prefs.getString("language", "ar") ?: "ar"
        binding.languageSpinner.setSelection(languages.indexOf(lang).coerceAtLeast(0))

        val theme = prefs.getString("theme", "dark") ?: "dark"
        binding.themeSpinner.setSelection(themes.indexOf(theme).coerceAtLeast(0))

        val serverPort = prefs.getInt("server_port", 8080)
        binding.serverPortInput.setText(serverPort.toString())

        val baudRate = prefs.getInt("baud_rate", 115200)
        binding.baudSpinner.setSelection(baudRates.indexOf(baudRate).coerceAtLeast(0))

        val dataBits = prefs.getInt("data_bits", UsbSerialPort.DATABITS_8)
        binding.dataBitsSpinner.setSelection(when(dataBits) {
            UsbSerialPort.DATABITS_5 -> 0
            UsbSerialPort.DATABITS_6 -> 1
            UsbSerialPort.DATABITS_7 -> 2
            else -> 3
        })

        val stopBits = prefs.getInt("stop_bits", UsbSerialPort.STOPBITS_1)
        binding.stopBitsSpinner.setSelection(when(stopBits) {
            UsbSerialPort.STOPBITS_1_5 -> 1
            UsbSerialPort.STOPBITS_2 -> 2
            else -> 0
        })

        val parity = prefs.getInt("parity", UsbSerialPort.PARITY_NONE)
        binding.paritySpinner.setSelection(when(parity) {
            UsbSerialPort.PARITY_ODD -> 1
            UsbSerialPort.PARITY_EVEN -> 2
            UsbSerialPort.PARITY_MARK -> 3
            UsbSerialPort.PARITY_SPACE -> 4
            else -> 0
        })

        val lineEnding = prefs.getInt("line_ending", 3)
        binding.lineEndingSpinner.setSelection(lineEnding)
    }

    private fun saveSettings() {
        val prefs = getSharedPreferences("serial_settings", Context.MODE_PRIVATE)
        val lang = languages[binding.languageSpinner.selectedItemPosition]
        val theme = themes[binding.themeSpinner.selectedItemPosition]
        val serverPortStr = binding.serverPortInput.text.toString()
        val serverPort = serverPortStr.toIntOrNull() ?: 8080

        val baudRate = baudRates[binding.baudSpinner.selectedItemPosition]
        val dataBits = when(binding.dataBitsSpinner.selectedItemPosition) {
            0 -> UsbSerialPort.DATABITS_5
            1 -> UsbSerialPort.DATABITS_6
            2 -> UsbSerialPort.DATABITS_7
            else -> UsbSerialPort.DATABITS_8
        }
        val stopBits = when(binding.stopBitsSpinner.selectedItemPosition) {
            1 -> UsbSerialPort.STOPBITS_1_5
            2 -> UsbSerialPort.STOPBITS_2
            else -> UsbSerialPort.STOPBITS_1
        }
        val parity = when(binding.paritySpinner.selectedItemPosition) {
            1 -> UsbSerialPort.PARITY_ODD
            2 -> UsbSerialPort.PARITY_EVEN
            3 -> UsbSerialPort.PARITY_MARK
            4 -> UsbSerialPort.PARITY_SPACE
            else -> UsbSerialPort.PARITY_NONE
        }
        val lineEnding = binding.lineEndingSpinner.selectedItemPosition

        prefs.edit().apply {
            putString("language", lang)
            putString("theme", theme)
            putInt("server_port", serverPort)
            putInt("baud_rate", baudRate)
            putInt("data_bits", dataBits)
            putInt("stop_bits", stopBits)
            putInt("parity", parity)
            putInt("line_ending", lineEnding)
            apply()
        }
    }
}
