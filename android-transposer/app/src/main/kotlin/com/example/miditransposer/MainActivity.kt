package com.example.miditransposer

import android.app.Activity
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiInputPort
import android.media.midi.MidiManager
import android.media.midi.MidiDeviceStatus
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView

class MainActivity : Activity() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var midiManager: MidiManager
    private var midiDevice: MidiDevice? = null
    private var midiInputPort: MidiInputPort? = null
    private var availableDevices: List<MidiDeviceInfo> = emptyList()

    private lateinit var midiStatus: TextView
    private lateinit var deviceSpinner: Spinner
    private lateinit var currentKeyText: TextView
    private lateinit var currentOffsetText: TextView
    private lateinit var stepText: TextView

    // 12 个调键相对于 C 的基础移调量。名称顺序与样机一致。
    private val keyNames = arrayOf("C", "C#", "D", "Eb", "E", "F", "F#", "G", "Ab", "A", "Bb", "B")
    private val keyOffsets = intArrayOf(0, 1, 2, 3, 4, 5, 6, -5, -4, -3, -2, -1)

    private var currentOffset = 0
    private var step = 1
    private var selectedDeviceIndex = 0

    private val deviceCallback = object : MidiManager.DeviceCallback() {
        override fun onDeviceAdded(info: MidiDeviceInfo) = refreshDevices()
        override fun onDeviceRemoved(info: MidiDeviceInfo) = refreshDevices()
        // 打开或关闭端口本身也会触发此回调，不能在这里刷新并关闭当前连接。
        override fun onDeviceStatusChanged(status: MidiDeviceStatus) = Unit
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        midiManager = getSystemService(MIDI_SERVICE) as MidiManager
        setContentView(buildScreen())
        midiManager.registerDeviceCallback(deviceCallback, mainHandler)
        refreshDevices()
    }

    override fun onResume() {
        super.onResume()
        if (::midiManager.isInitialized) refreshDevices()
    }

    override fun onDestroy() {
        midiManager.unregisterDeviceCallback(deviceCallback)
        closeMidiDevice()
        super.onDestroy()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun label(text: String, size: Float, color: Int = Color.WHITE): TextView =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            gravity = Gravity.CENTER
        }

    private fun button(text: String, onClick: () -> Unit): Button =
        Button(this).apply {
            this.text = text
            textSize = 20f
            setOnClickListener { onClick() }
            minHeight = dp(54)
            minWidth = 0
            isAllCaps = false
        }

    private fun buildScreen(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(12))
            setBackgroundColor(Color.rgb(22, 22, 22))
        }

        midiStatus = label("MIDI：正在检测…", 15f, Color.LTGRAY).apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(8), 0)
        }
        root.addView(midiStatus, LinearLayout.LayoutParams(-1, dp(38)))

        deviceSpinner = Spinner(this)
        deviceSpinner.onItemSelectedListener = object : SimpleItemSelectedListener() {
            override fun onItemSelected(position: Int) {
                if (position < availableDevices.size) {
                    selectedDeviceIndex = position
                    openSelectedDevice()
                }
            }
        }
        root.addView(deviceSpinner, LinearLayout.LayoutParams(-1, dp(42)))

        val display = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setBackgroundColor(Color.rgb(4, 4, 4))
        }
        currentKeyText = label("C", 44f, Color.CYAN).apply { typeface = Typeface.DEFAULT_BOLD }
        currentOffsetText = label("0", 38f, Color.WHITE).apply { typeface = Typeface.DEFAULT_BOLD }
        stepText = label("1", 28f, Color.YELLOW).apply { typeface = Typeface.DEFAULT_BOLD }
        val topValues = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        topValues.addView(currentKeyText, LinearLayout.LayoutParams(0, dp(52), 1f))
        topValues.addView(currentOffsetText, LinearLayout.LayoutParams(0, dp(52), 1f))
        val displayValues = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        displayValues.addView(topValues, LinearLayout.LayoutParams(-1, dp(54)))
        displayValues.addView(stepText, LinearLayout.LayoutParams(-1, dp(32)))
        display.addView(displayValues, LinearLayout.LayoutParams(0, dp(88), 1f))
        root.addView(display, LinearLayout.LayoutParams(-1, dp(104)))

        val keyGrid = GridLayout(this).apply {
            columnCount = 7
            rowCount = 2
            useDefaultMargins = true
            setPadding(0, dp(8), 0, dp(2))
        }
        keyGrid.addView(button("+", { changeStep(1) }), gridParams())
        for (index in 0..5) {
            keyGrid.addView(button(keyNames[index]) { chooseKey(index) }, gridParams())
        }
        keyGrid.addView(button("-", { changeStep(-1) }), gridParams())
        for (index in 6..11) {
            keyGrid.addView(button(keyNames[index]) { chooseKey(index) }, gridParams())
        }
        root.addView(keyGrid, LinearLayout.LayoutParams(-1, dp(140)))

        val accidentalRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        accidentalRow.addView(button("♭", { shift(-step) }), LinearLayout.LayoutParams(0, dp(64), 1f))
        accidentalRow.addView(button("♯", { shift(step) }), LinearLayout.LayoutParams(0, dp(64), 1f))
        root.addView(accidentalRow, LinearLayout.LayoutParams(-1, dp(74)))

        val help = label("12 个调键选择基础调；+ / - 调整步长；♭ / ♯ 按步长升降", 13f, Color.LTGRAY)
        help.setPadding(dp(4), dp(5), dp(4), 0)
        root.addView(help, LinearLayout.LayoutParams(-1, dp(40)))

        updateDisplay()
        return root
    }

    private fun gridParams(): GridLayout.LayoutParams = GridLayout.LayoutParams().apply {
        width = 0
        height = dp(60)
        columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
        rowSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
        setMargins(dp(2), dp(2), dp(2), dp(2))
    }

    private fun chooseKey(index: Int) {
        currentOffset = keyOffsets[index]
        updateDisplay()
        sendTranspose()
    }

    private fun changeStep(delta: Int) {
        step = (step + delta).coerceIn(1, 12)
        updateDisplay()
    }

    private fun shift(delta: Int) {
        // JUNO-DS 官方范围为 ±24；这里限制在这个范围内，避免溢出 MIDI 数值。
        currentOffset = (currentOffset + delta).coerceIn(-24, 24)
        updateDisplay()
        sendTranspose()
    }

    private fun displayKey(offset: Int): String {
        val names = arrayOf("C", "C#", "D", "Eb", "E", "F", "F#", "G", "Ab", "A", "Bb", "B")
        val normalized = ((offset % 12) + 12) % 12
        return names[normalized]
    }

    private fun updateDisplay() {
        currentKeyText.text = displayKey(currentOffset)
        currentOffsetText.text = currentOffset.toString()
        stepText.text = step.toString()
    }

    private fun refreshDevices() {
        if (!::deviceSpinner.isInitialized) return
        // 不能假定输入端口一定编号为 0；部分 USB-MIDI 驱动的端口编号不连续。
        availableDevices = midiManager.devices.filter {
            it.ports.any { port -> port.type == MidiDeviceInfo.PortInfo.TYPE_INPUT }
        }
        val names = availableDevices.map { it.properties[MidiDeviceInfo.PROPERTY_NAME] ?: "MIDI 设备" }
        val displayNames = if (names.isEmpty()) listOf("未检测到可发送的 MIDI 设备") else names
        deviceSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, displayNames)
        if (availableDevices.isEmpty()) {
            closeMidiDevice()
            midiStatus.text = "MIDI：未检测到设备（请接入 OTG / USB-MIDI）"
            midiStatus.setTextColor(Color.LTGRAY)
        } else {
            selectedDeviceIndex = selectedDeviceIndex.coerceIn(0, availableDevices.lastIndex)
            deviceSpinner.setSelection(selectedDeviceIndex, false)
            midiStatus.text = "MIDI：检测到 ${names.size} 台设备"
            midiStatus.setTextColor(Color.GREEN)
            openSelectedDevice()
        }
    }

    private fun openSelectedDevice() {
        if (availableDevices.isEmpty()) return
        closeMidiDevice()
        val info = availableDevices[selectedDeviceIndex]
        midiManager.openDevice(info, { device ->
            if (device == null) {
                midiStatus.text = "MIDI：设备已识别，但打开失败"
                midiStatus.setTextColor(Color.RED)
                return@openDevice
            }
            midiDevice = device
            val inputPortNumbers = info.ports
                .filter { port -> port.type == MidiDeviceInfo.PortInfo.TYPE_INPUT }
                .map { port -> port.portNumber }
            midiInputPort = inputPortNumbers.firstNotNullOfOrNull { portNumber ->
                try {
                    device.openInputPort(portNumber)
                } catch (_: SecurityException) {
                    null
                } catch (_: RuntimeException) {
                    null
                }
            }
            if (midiInputPort == null) {
                midiStatus.text = "MIDI：设备没有可用输入端口"
                midiStatus.setTextColor(Color.RED)
            } else {
                val name = info.properties[MidiDeviceInfo.PROPERTY_NAME] ?: "MIDI 设备"
                midiStatus.text = "MIDI：已连接 $name"
                midiStatus.setTextColor(Color.GREEN)
            }
        }, mainHandler)
    }

    private fun closeMidiDevice() {
        try { midiInputPort?.close() } catch (_: Exception) { }
        try { midiDevice?.close() } catch (_: Exception) { }
        midiInputPort = null
        midiDevice = null
    }

    private fun sendTranspose() {
        val port = midiInputPort ?: run {
            midiStatus.text = "MIDI：未打开设备，当前设置未发送"
            midiStatus.setTextColor(Color.RED)
            return
        }
        val midiValue = (0x40 + currentOffset).coerceIn(0, 0x7F)
        val message = byteArrayOf(0xF0.toByte(), 0x7F, 0x7F, 0x04, 0x04, 0x00, midiValue.toByte(), 0xF7.toByte())
        try {
            port.send(message, 0, message.size, System.nanoTime())
            midiStatus.text = "MIDI：已发送 ${displayKey(currentOffset)} ${currentOffset} 半音"
            midiStatus.setTextColor(Color.GREEN)
        } catch (error: Exception) {
            midiStatus.text = "MIDI：发送失败：${error.message ?: "未知错误"}"
            midiStatus.setTextColor(Color.RED)
        }
    }

    private abstract class SimpleItemSelectedListener : android.widget.AdapterView.OnItemSelectedListener {
        override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        abstract fun onItemSelected(position: Int)
        override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
            onItemSelected(position)
        }
    }
}
