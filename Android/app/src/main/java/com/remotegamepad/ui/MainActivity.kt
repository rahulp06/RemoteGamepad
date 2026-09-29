package com.remotegamepad.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomsheet.BottomSheetDialog
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.remotegamepad.bluetooth.BluetoothService
import com.remotegamepad.bluetooth.GamepadState
import com.remotegamepad.bluetooth.Protocol
import com.remotegamepad.databinding.ActivityMainBinding
import com.remotegamepad.wifi.UdpDiscoveryClient
import com.remotegamepad.wifi.WifiInputService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity(), BluetoothService.Callback, GamepadView.Listener {

    companion object {
        private const val TAG = "MainActivity"
        private const val PREFS_NAME = "remote_gamepad_prefs"
        private const val KEY_CONTROLLER_STYLE = "controller_style"
        private const val BACK_GUARD_TIMEOUT_MS = 2000L
    }

    // ── Screen navigation ──
    private enum class Screen { HOME, CONNECT, WIFI_CONNECT, GAMEPAD }
    private var currentScreen = Screen.HOME

    // ── Transport mode ──
    private enum class Transport { NONE, BLUETOOTH, WIFI }
    private var activeTransport = Transport.NONE

    private lateinit var binding: ActivityMainBinding
    private lateinit var bluetoothService: BluetoothService
    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }

    private val pairedAdapter = DeviceAdapter(showPairedBadge = true) { device -> connectTo(device) }
    private val discoveredAdapter = DeviceAdapter(showPairedBadge = false) { device -> connectTo(device) }
    private val discoveredDevices = mutableListOf<BluetoothDevice>()
    private var lastSentState: GamepadState? = null

    private var isScanning = false
    private var connectingDeviceName: String? = null

    // ── Wi-Fi transport ──
    private lateinit var udpDiscovery: UdpDiscoveryClient
    private lateinit var wifiInputService: WifiInputService
    private val discoveryExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "WifiDiscovery").apply { isDaemon = true }
    }
    private var isWifiScanning = false

    // ── Gameplay back-guard ──
    private var lastBackPressMs = 0L

    // Permission launcher
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            Log.d(TAG, "Permissions granted")
            loadPairedDevices()
        } else {
            Log.w(TAG, "Permissions denied")
            AlertDialog.Builder(this)
                .setTitle("Permissions Required")
                .setMessage("Bluetooth and location permissions are needed to discover and connect to your PC.")
                .setPositiveButton("OK") { _, _ -> }
                .show()
        }
    }

    // Discovery broadcast receiver
    private val discoveryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device: BluetoothDevice? =
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                        }
                    device?.let {
                        if (it.name != null && discoveredDevices.none { d -> d.address == it.address }) {
                            discoveredDevices.add(it)
                            discoveredAdapter.submitList(discoveredDevices.toList())
                            updateEmptyState()
                            Log.d(TAG, "Discovered: ${it.name}")
                        }
                    }
                }
                BluetoothAdapter.ACTION_DISCOVERY_STARTED -> {
                    isScanning = true
                    binding.btnScan.text = getString(com.remotegamepad.R.string.btn_stop_scan)
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    isScanning = false
                    binding.btnScan.text = getString(com.remotegamepad.R.string.btn_scan)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        bluetoothService = BluetoothService(this)
        bluetoothService.setCallback(this)
        udpDiscovery = UdpDiscoveryClient(this)
        wifiInputService = WifiInputService()

        setupRecyclerViews()
        setupButtons()
        setupGamepad()
        setupNavigation()
        setupControllerStyle()
        registerReceivers()
        checkPermissions()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(discoveryReceiver)
        if (isScanning) {
            bluetoothAdapter?.cancelDiscovery()
        }
        bluetoothService.release()
        wifiInputService.disconnect()
        discoveryExecutor.shutdownNow()
    }

    //region UI Setup
    private fun setupRecyclerViews() {
        binding.rvPairedDevices.layoutManager = LinearLayoutManager(this)
        binding.rvPairedDevices.adapter = pairedAdapter

        binding.rvDiscoveredDevices.layoutManager = LinearLayoutManager(this)
        binding.rvDiscoveredDevices.adapter = discoveredAdapter
    }

    private fun setupButtons() {
        binding.btnScan.setOnClickListener {
            if (isScanning) {
                stopDiscovery()
            } else {
                startDiscovery()
            }
        }
    }

    private fun setupGamepad() {
        binding.gamepadView.listener = this
        // Restore persisted controller style
        val savedStyle = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getString(KEY_CONTROLLER_STYLE, GamepadView.ControllerStyle.XBOX.name)
        binding.gamepadView.controllerStyle = try {
            GamepadView.ControllerStyle.valueOf(savedStyle!!)
        } catch (_: Exception) {
            GamepadView.ControllerStyle.XBOX
        }
    }

    private fun setupControllerStyle() {
        binding.btnControllerStyle.setOnClickListener {
            showControllerStyleDialog()
        }
    }

    private fun showControllerStyleDialog() {
        val dialog = BottomSheetDialog(this, com.remotegamepad.R.style.RemoteGamepad_BottomSheet)
        val view = layoutInflater.inflate(com.remotegamepad.R.layout.bottom_sheet_controller_style, null)
        dialog.setContentView(view)

        val radioXbox = view.findViewById<android.widget.RadioButton>(com.remotegamepad.R.id.radioXbox)
        val radioPs = view.findViewById<android.widget.RadioButton>(com.remotegamepad.R.id.radioPs)
        val optionXbox = view.findViewById<View>(com.remotegamepad.R.id.optionXbox)
        val optionPs = view.findViewById<View>(com.remotegamepad.R.id.optionPs)

        val currentStyle = binding.gamepadView.controllerStyle
        radioXbox.isChecked = currentStyle == GamepadView.ControllerStyle.XBOX
        radioPs.isChecked = currentStyle == GamepadView.ControllerStyle.PS

        fun selectStyle(style: GamepadView.ControllerStyle) {
            binding.gamepadView.controllerStyle = style
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putString(KEY_CONTROLLER_STYLE, style.name)
                .apply()
            radioXbox.isChecked = style == GamepadView.ControllerStyle.XBOX
            radioPs.isChecked = style == GamepadView.ControllerStyle.PS
            dialog.dismiss()
        }

        optionXbox.setOnClickListener { selectStyle(GamepadView.ControllerStyle.XBOX) }
        optionPs.setOnClickListener { selectStyle(GamepadView.ControllerStyle.PS) }

        dialog.show()
    }

    private fun setupNavigation() {
        // Home → Bluetooth connector
        binding.cardBluetooth.setOnClickListener {
            showConnect()
        }

        // Home → Wi-Fi connector
        binding.cardWifi.setOnClickListener {
            showWifiConnect()
        }

        // Bluetooth connector back arrow
        binding.btnBack.setOnClickListener {
            if (isScanning) stopDiscovery()
            bluetoothService.disconnect()
            showHome()
        }

        // Wi-Fi connector back arrow
        binding.btnWifiBack.setOnClickListener {
            stopWifiScan()
            showHome()
        }

        // Wi-Fi scan button
        binding.btnWifiScan.setOnClickListener {
            startWifiDiscovery()
        }

        // System back button handling
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when (currentScreen) {
                    Screen.GAMEPAD -> {
                        val now = android.os.SystemClock.elapsedRealtime()
                        if (now - lastBackPressMs > BACK_GUARD_TIMEOUT_MS) {
                            // First press — show guard hint, do NOT exit yet.
                            lastBackPressMs = now
                            android.widget.Toast.makeText(
                                this@MainActivity,
                                getString(com.remotegamepad.R.string.back_guard_hint),
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            // Second deliberate press within window — exit.
                            lastBackPressMs = 0L
                            when (activeTransport) {
                                Transport.BLUETOOTH -> {
                                    bluetoothService.disconnect()
                                    showConnect()
                                }
                                Transport.WIFI -> {
                                    wifiInputService.disconnect()
                                    activeTransport = Transport.NONE
                                    showHome()
                                }
                                else -> showHome()
                            }
                        }
                    }
                    Screen.CONNECT -> {
                        if (isScanning) stopDiscovery()
                        bluetoothService.disconnect()
                        showHome()
                    }
                    Screen.WIFI_CONNECT -> {
                        stopWifiScan()
                        showHome()
                    }
                    Screen.HOME -> {
                        finish()
                    }
                }
            }
        })
    }

    // ── Screen navigation ──

    private fun showHome() {
        currentScreen = Screen.HOME
        binding.layoutHome.visibility = View.VISIBLE
        binding.layoutConnection.visibility = View.GONE
        binding.layoutWifi.visibility = View.GONE
        binding.gamepadView.visibility = View.GONE
        setGamepadOrientation(active = false)
    }

    private fun showConnect() {
        currentScreen = Screen.CONNECT
        binding.layoutHome.visibility = View.GONE
        binding.layoutConnection.visibility = View.VISIBLE
        binding.layoutWifi.visibility = View.GONE
        binding.gamepadView.visibility = View.GONE
        setGamepadOrientation(active = false)
        updateEmptyState()
    }

    private fun showWifiConnect() {
        currentScreen = Screen.WIFI_CONNECT
        binding.layoutHome.visibility = View.GONE
        binding.layoutConnection.visibility = View.GONE
        binding.layoutWifi.visibility = View.VISIBLE
        binding.gamepadView.visibility = View.GONE
        setGamepadOrientation(active = false)
        updateWifiEmptyState()
        // Do NOT auto-start discovery — user must tap Scan manually.
        // Auto-discovery on screen entry caused spurious reconnections
        // when returning from an active Wi-Fi gamepad session.
    }

    private fun showGamepad() {
        currentScreen = Screen.GAMEPAD
        binding.layoutHome.visibility = View.GONE
        binding.layoutConnection.visibility = View.GONE
        binding.layoutWifi.visibility = View.GONE
        binding.gamepadView.visibility = View.VISIBLE
        setGamepadOrientation(active = true)
    }

    private fun setGamepadOrientation(active: Boolean) {
        requestedOrientation = if (active) {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }

    // ── Empty state management ──

    private fun updateEmptyState() {
        val hasPaired = pairedAdapter.itemCount > 0
        val hasDiscovered = discoveredAdapter.itemCount > 0
        val hasDevices = hasPaired || hasDiscovered

        binding.emptyState.visibility = if (hasDevices) View.GONE else View.VISIBLE
        binding.deviceListScroll.visibility = if (hasDevices) View.VISIBLE else View.GONE
        binding.pairedSectionHeader.visibility = if (hasPaired) View.VISIBLE else View.GONE
        binding.discoveredSectionHeader.visibility = if (hasDiscovered) View.VISIBLE else View.GONE
    }

    private fun showConnectingState(deviceName: String?) {
        connectingDeviceName = deviceName
        binding.connectingIndicator.visibility = View.VISIBLE
        binding.tvConnectingText.text = getString(com.remotegamepad.R.string.connecting_to_device)
    }

    private fun hideConnectingState() {
        connectingDeviceName = null
        binding.connectingIndicator.visibility = View.GONE
    }
    //endregion

    //region GamepadView.Listener
    override fun onStateChanged(state: GamepadState) {
        when (activeTransport) {
            Transport.BLUETOOTH -> {
                if (!bluetoothService.isConnected()) return
                if (lastSentState != state) {
                    lastSentState = state
                    bluetoothService.send(state.toProtocolMessage())
                }
            }
            Transport.WIFI -> {
                if (!wifiInputService.isConnected) return
                lastSentState = state
                wifiInputService.updateState(state)
            }
            else -> { /* no transport active */ }
        }
    }
    //endregion

    //region Permissions
    private fun checkPermissions() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH)
                != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.BLUETOOTH)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADMIN)
                != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.BLUETOOTH_ADMIN)
            }
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        if (permissions.isNotEmpty()) {
            permissionLauncher.launch(permissions.toTypedArray())
        } else {
            loadPairedDevices()
        }
    }
    //endregion

    //region Discovery
    private fun registerReceivers() {
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        registerReceiver(discoveryReceiver, filter)
    }

    private fun loadPairedDevices() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED) {
            return
        }
        val paired = bluetoothAdapter?.bondedDevices?.toList() ?: emptyList()
        pairedAdapter.submitList(paired)
        updateEmptyState()
    }

    private fun startDiscovery() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
            != PackageManager.PERMISSION_GRANTED) {
            return
        }
        discoveredDevices.clear()
        discoveredAdapter.submitList(emptyList())
        updateEmptyState()
        bluetoothAdapter?.cancelDiscovery()
        val started = bluetoothAdapter?.startDiscovery() ?: false
        if (!started) {
            Log.w(TAG, "Failed to start discovery")
        }
    }

    private fun stopDiscovery() {
        bluetoothAdapter?.cancelDiscovery()
    }
    //endregion

    //region Connection — Bluetooth
    private fun connectTo(device: BluetoothDevice) {
        if (isScanning) stopDiscovery()
        showConnectingState(device.name)
        bluetoothService.connect(device)
    }
    //endregion

    //region Connection — Wi-Fi
    private fun connectToWifiServer(server: UdpDiscoveryClient.ServerInfo) {
        stopWifiScan()
        binding.wifiScanningIndicator.visibility = View.VISIBLE
        binding.tvWifiScanningText.text = getString(com.remotegamepad.R.string.wifi_connecting)

        discoveryExecutor.execute {
            wifiInputService.connect(server.ip, server.inputPort)
            activeTransport = Transport.WIFI
            runOnUiThread {
                binding.wifiScanningIndicator.visibility = View.GONE
                showGamepad()
            }
        }
    }
    //endregion

    //region BluetoothService.Callback
    override fun onStateChanged(state: BluetoothService.State) {
        when (state) {
            is BluetoothService.State.Disconnected -> {
                hideConnectingState()
                if (currentScreen == Screen.GAMEPAD && activeTransport == Transport.BLUETOOTH) {
                    activeTransport = Transport.NONE
                    showConnect()
                }
                Log.d(TAG, "State: Disconnected")
            }
            is BluetoothService.State.Connecting -> {
                Log.d(TAG, "State: Connecting")
            }
            is BluetoothService.State.Connected -> {
                hideConnectingState()
                activeTransport = Transport.BLUETOOTH
                showGamepad()
                Log.d(TAG, "State: Connected")
            }
            is BluetoothService.State.Error -> {
                hideConnectingState()
                if (currentScreen == Screen.GAMEPAD && activeTransport == Transport.BLUETOOTH) {
                    activeTransport = Transport.NONE
                    showConnect()
                }
                Toast.makeText(this, getString(com.remotegamepad.R.string.connection_failed), Toast.LENGTH_SHORT).show()
                Log.d(TAG, "State: Error — ${state.reason}")
            }
        }
    }

    override fun onMessageReceived(message: String) {
        Log.d(TAG, "Received: $message")
    }

    override fun onError(message: String) {
        Log.e(TAG, "Error: $message")
    }
    //endregion

    //region DeviceAdapter
    class DeviceAdapter(
        private val showPairedBadge: Boolean = false,
        private val onClick: (BluetoothDevice) -> Unit
    ) : RecyclerView.Adapter<DeviceAdapter.ViewHolder>() {

        private var devices: List<BluetoothDevice> = emptyList()

        fun submitList(newList: List<BluetoothDevice>) {
            devices = newList
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(com.remotegamepad.R.layout.item_device, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(devices[position])
        }

        override fun getItemCount(): Int = devices.size

        inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val tvName: TextView = itemView.findViewById(com.remotegamepad.R.id.tvDeviceName)
            private val tvBadge: TextView = itemView.findViewById(com.remotegamepad.R.id.tvDeviceBadge)

            fun bind(device: BluetoothDevice) {
                tvName.text = device.name ?: "Unknown"
                tvBadge.visibility = if (showPairedBadge) View.VISIBLE else View.GONE
                itemView.setOnClickListener { onClick(device) }
            }
        }
    }
    //endregion

    //region Wi-Fi Discovery
    private fun startWifiDiscovery() {
        if (isWifiScanning) return
        isWifiScanning = true
        binding.wifiScanningIndicator.visibility = View.VISIBLE
        binding.tvWifiScanningText.text = getString(com.remotegamepad.R.string.wifi_scanning)
        binding.btnWifiScan.text = getString(com.remotegamepad.R.string.wifi_stop_scan)
        binding.wifiEmptyState.visibility = View.GONE
        binding.wifiServerListScroll.visibility = View.GONE
        binding.wifiServerList.removeAllViews()

        discoveryExecutor.execute {
            val servers = udpDiscovery.discover()
            runOnUiThread {
                isWifiScanning = false
                binding.wifiScanningIndicator.visibility = View.GONE
                binding.btnWifiScan.text = getString(com.remotegamepad.R.string.wifi_scan_again)

                if (servers.isEmpty()) {
                    binding.wifiEmptyState.visibility = View.VISIBLE
                    binding.wifiServerListScroll.visibility = View.GONE
                } else {
                    binding.wifiEmptyState.visibility = View.GONE
                    binding.wifiServerListScroll.visibility = View.VISIBLE
                    binding.wifiServerList.removeAllViews()
                    for (server in servers) {
                        addWifiServerCard(server)
                    }
                }
            }
        }
    }

    private fun stopWifiScan() {
        isWifiScanning = false
    }

    private fun updateWifiEmptyState() {
        val hasChildren = binding.wifiServerList.childCount > 0
        binding.wifiEmptyState.visibility = if (hasChildren || isWifiScanning) View.GONE else View.VISIBLE
        binding.wifiServerListScroll.visibility = if (hasChildren) View.VISIBLE else View.GONE
    }

    private fun addWifiServerCard(server: UdpDiscoveryClient.ServerInfo) {
        val cardView = LayoutInflater.from(this)
            .inflate(com.remotegamepad.R.layout.item_wifi_server, binding.wifiServerList, false)

        cardView.findViewById<TextView>(com.remotegamepad.R.id.tvServerName).text = server.name
        cardView.findViewById<TextView>(com.remotegamepad.R.id.tvServerIp).text = server.ip
        cardView.setOnClickListener { connectToWifiServer(server) }

        binding.wifiServerList.addView(cardView)
    }
    //endregion
}
