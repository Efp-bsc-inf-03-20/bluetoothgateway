package com.example.bluetoothgateway

import android.Manifest
import android.bluetooth.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresPermission
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import java.io.IOException
import java.util.UUID

class MainActivity : AppCompatActivity() {
    private lateinit var bluetoothAdapter: BluetoothAdapter
    private lateinit var statusTextView: TextView
    private lateinit var scanButton: MaterialButton
    private lateinit var deviceRecyclerView: RecyclerView
    private val discoveredDevices = mutableListOf<BluetoothDevice>()
    private lateinit var deviceAdapter: DeviceAdapter
    private val handler = Handler(Looper.getMainLooper())
    private var isScanning = false
    private var connectedSocket: BluetoothSocket? = null
    private val MY_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    // Permissions launcher for runtime permissions
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            startBluetoothScan()
        } else {
            Toast.makeText(this, "Required permissions denied", Toast.LENGTH_SHORT).show()
        }
    }

    // Receiver to listen for discovery events
    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device: BluetoothDevice? = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    device?.let {
                        if (ActivityCompat.checkSelfPermission(
                                this@MainActivity,
                                Manifest.permission.BLUETOOTH_CONNECT
                            ) == PackageManager.PERMISSION_GRANTED
                        ) {
                            if (discoveredDevices.none { d -> d.address == device.address }) {
                                discoveredDevices.add(device)
                                deviceAdapter.notifyItemInserted(discoveredDevices.size - 1)
                            }
                        }
                    }
                }
                BluetoothAdapter.ACTION_DISCOVERY_STARTED -> {
                    runOnUiThread {
                        statusTextView.text = "Scanning for devices..."
                        discoveredDevices.clear()
                        deviceAdapter.notifyDataSetChanged()
                    }
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    runOnUiThread {
                        statusTextView.text = "Scan completed. Found ${discoveredDevices.size} devices."
                        isScanning = false
                        scanButton.text = "Start Scanning"
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Set up toolbar and views
        val toolbar: Toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)

        statusTextView = findViewById(R.id.statusTextView)
        scanButton = findViewById(R.id.scanButton)
        deviceRecyclerView = findViewById(R.id.deviceRecyclerView)

        // Setup RecyclerView with the device adapter
        deviceAdapter = DeviceAdapter(discoveredDevices) { device ->
            connectToDevice(device)
        }
        deviceRecyclerView.adapter = deviceAdapter
        deviceRecyclerView.layoutManager = LinearLayoutManager(this)

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        if (bluetoothAdapter == null) {
            Toast.makeText(this, "Bluetooth not supported", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // Register BroadcastReceiver for discovery events
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        registerReceiver(bluetoothReceiver, filter)

        scanButton.setOnClickListener {
            if (isScanning) {
                stopBluetoothScan()
            } else {
                if (checkBluetoothPermissions()) {
                    if (bluetoothAdapter.isEnabled) {
                        startBluetoothScan()
                    } else {
                        requestBluetoothEnable()
                    }
                } else {
                    requestBluetoothPermissions()
                }
            }
        }
    }

    // Initiate connection process for a selected device
    private fun connectToDevice(device: BluetoothDevice) {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestBluetoothPermissions()
            return
        }

        // Cancel discovery before connecting and wait a moment
        if (bluetoothAdapter.isDiscovering) {
            bluetoothAdapter.cancelDiscovery()
            Log.d("Bluetooth", "Discovery cancelled before connection")
            handler.postDelayed({ proceedWithConnection(device) }, 500)
        } else {
            proceedWithConnection(device)
        }
    }

    // Check bonding state and either initiate pairing or connect directly
    private fun proceedWithConnection(device: BluetoothDevice) {
        when (device.bondState) {
            BluetoothDevice.BOND_NONE -> {
                Log.d("Bluetooth", "Device not bonded. Initiating pairing for ${device.name}")
                try {
                    val pairingReceiver = object : BroadcastReceiver() {
                        override fun onReceive(context: Context, intent: Intent) {
                            if (intent.action == BluetoothDevice.ACTION_BOND_STATE_CHANGED) {
                                val currentBondState = intent.getIntExtra(
                                    BluetoothDevice.EXTRA_BOND_STATE,
                                    BluetoothDevice.ERROR
                                )
                                Log.d("Bluetooth", "Bond state changed: $currentBondState for ${device.name}")
                                when (currentBondState) {
                                    BluetoothDevice.BOND_BONDED -> {
                                        unregisterReceiver(this)
                                        Toast.makeText(
                                            this@MainActivity,
                                            "Paired with ${device.name}",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                        // Proceed with connection after successful pairing
                                        attemptDeviceConnection(device)
                                    }
                                    BluetoothDevice.BOND_NONE -> {
                                        unregisterReceiver(this)
                                        Toast.makeText(
                                            this@MainActivity,
                                            "Pairing failed with ${device.name}",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                    // BOND_BONDING state can be used to show progress if desired
                                }
                            }
                        }
                    }
                    registerReceiver(pairingReceiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED))
                    if (!device.createBond()) {
                        Toast.makeText(
                            this,
                            "Failed to initiate pairing with ${device.name}",
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        Toast.makeText(
                            this,
                            "Pairing initiated with ${device.name}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                } catch (e: SecurityException) {
                    Log.e("Bluetooth", "Security exception during pairing", e)
                    Toast.makeText(
                        this,
                        "Pairing failed due to security restrictions",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            BluetoothDevice.BOND_BONDING -> {
                Toast.makeText(
                    this,
                    "Device is currently pairing. Please wait...",
                    Toast.LENGTH_SHORT
                ).show()
            }
            BluetoothDevice.BOND_BONDED -> {
                Log.d("Bluetooth", "Device already bonded. Proceeding with connection to ${device.name}")
                attemptDeviceConnection(device)
            }
        }
    }

    // Attempt to connect using insecure then secure methods
    private fun attemptDeviceConnection(device: BluetoothDevice) {
        Thread {
            var socket: BluetoothSocket? = null
            try {
                Log.d("Bluetooth", "Attempting insecure connection to ${device.name}")
                socket = tryInsecureConnection(device)

                if (socket == null || !socket.isConnected) {
                    Log.d("Bluetooth", "Insecure connection failed, trying secure connection to ${device.name}")
                    socket = trySecureConnection(device)
                }

                if (socket != null && socket.isConnected) {
                    connectedSocket = socket
                    runOnUiThread {
                        Toast.makeText(
                            this,
                            "Connected to ${device.name}",
                            Toast.LENGTH_SHORT
                        ).show()
                        statusTextView.text = "Connected to: ${device.name}"
                    }
                } else {
                    runOnUiThread {
                        Toast.makeText(
                            this,
                            "All connection attempts failed for ${device.name}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            } catch (e: Exception) {
                Log.e("Bluetooth", "Connection error for ${device.name}", e)
                runOnUiThread {
                    Toast.makeText(
                        this,
                        "Connection failed: ${e.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                try {
                    socket?.close()
                } catch (closeException: IOException) {
                    Log.e("Bluetooth", "Could not close socket for ${device.name}", closeException)
                }
            }
        }.start()
    }

    // Insecure connection method
    private fun tryInsecureConnection(device: BluetoothDevice): BluetoothSocket? {
        return try {
            val socket = device.createInsecureRfcommSocketToServiceRecord(MY_UUID)
            socket.connect()
            socket
        } catch (e: IOException) {
            Log.w("Bluetooth", "Insecure connection failed for ${device.name}: ${e.message}")
            null
        }
    }

    // Secure connection method with reflection fallback
    private fun trySecureConnection(device: BluetoothDevice): BluetoothSocket? {
        return try {
            val socket = device.createRfcommSocketToServiceRecord(MY_UUID)
            socket.connect()
            socket
        } catch (e: IOException) {
            Log.w("Bluetooth", "Secure connection failed for ${device.name}: ${e.message}")
            try {
                val method = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                val socket = method.invoke(device, 1) as BluetoothSocket
                socket.connect()
                socket
            } catch (e2: Exception) {
                Log.e("Bluetooth", "Reflection-based connection failed for ${device.name}: ${e2.message}")
                null
            }
        }
    }

    // Check required Bluetooth permissions based on Android version
    private fun checkBluetoothPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
                    (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED)
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    // Request the necessary Bluetooth permissions
    private fun requestBluetoothPermissions() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        requestPermissionLauncher.launch(permissions)
    }

    private val requestEnableBluetooth = 1

    // Request user to enable Bluetooth if it's not already enabled
    private fun requestBluetoothEnable() {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestBluetoothPermissions()
            return
        }
        val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
        startActivityForResult(enableBtIntent, requestEnableBluetooth)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == requestEnableBluetooth && resultCode == RESULT_OK) {
            startBluetoothScan()
        } else if (requestCode == requestEnableBluetooth) {
            Toast.makeText(this, "Bluetooth must be enabled to scan for devices", Toast.LENGTH_SHORT).show()
        }
    }

    // Start scanning for Bluetooth devices
    private fun startBluetoothScan() {
        if (!checkBluetoothPermissions()) {
            requestBluetoothPermissions()
            return
        }

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            return
        }

        statusTextView.text = "Starting scan..."
        isScanning = true
        scanButton.text = "Stop Scan"

        if (!bluetoothAdapter.startDiscovery()) {
            Toast.makeText(this, "Failed to start discovery", Toast.LENGTH_SHORT).show()
            isScanning = false
            scanButton.text = "Start Scanning"
        }

        // Stop scanning after 10 seconds
        handler.postDelayed({ stopBluetoothScan() }, 10000)
    }

    // Stop scanning for Bluetooth devices
    private fun stopBluetoothScan() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        if (isScanning) {
            if (bluetoothAdapter.isDiscovering) {
                bluetoothAdapter.cancelDiscovery()
            }
            isScanning = false
            scanButton.text = "Start Scanning"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(bluetoothReceiver)
        handler.removeCallbacksAndMessages(null)
        stopBluetoothScan()
        try {
            connectedSocket?.close()
        } catch (e: IOException) {
            Log.e("Bluetooth", "Error closing socket", e)
        }
    }

    // RecyclerView Adapter to display Bluetooth devices
    class DeviceAdapter(
        private val devices: List<BluetoothDevice>,
        private val onConnectClick: (BluetoothDevice) -> Unit
    ) : RecyclerView.Adapter<DeviceAdapter.DeviceViewHolder>() {

        class DeviceViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val deviceName: TextView = itemView.findViewById(R.id.deviceName)
            val deviceAddress: TextView = itemView.findViewById(R.id.deviceAddress)
            val connectButton: MaterialButton = itemView.findViewById(R.id.connectButton)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DeviceViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.device_item, parent, false)
            return DeviceViewHolder(view)
        }

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onBindViewHolder(holder: DeviceViewHolder, position: Int) {
            val device = devices[position]
            holder.deviceName.text = device.name ?: "Unknown Device"
            holder.deviceAddress.text = device.address
            holder.connectButton.setOnClickListener {
                onConnectClick(device)
            }
        }

        override fun getItemCount(): Int = devices.size
    }
}