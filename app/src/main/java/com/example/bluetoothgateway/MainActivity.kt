package com.example.bluetoothgateway

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
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

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action
            when (action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device: BluetoothDevice? = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    device?.let {
                        if (ActivityCompat.checkSelfPermission(
                                this@MainActivity,
                                Manifest.permission.BLUETOOTH_CONNECT
                            ) == PackageManager.PERMISSION_GRANTED
                        ) {
                            if (!discoveredDevices.any { d -> d.address == device.address }) {
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

        val toolbar: Toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)

        statusTextView = findViewById(R.id.statusTextView)
        scanButton = findViewById(R.id.scanButton)
        deviceRecyclerView = findViewById(R.id.deviceRecyclerView)

        // Setup RecyclerView
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

        // Register the BroadcastReceiver
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

    private fun connectToDevice(device: BluetoothDevice) {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestBluetoothPermissions()
            return
        }

        bluetoothAdapter.cancelDiscovery()

        try {
            val socket = device.createRfcommSocketToServiceRecord(MY_UUID)

            Thread {
                try {
                    socket.connect()
                    connectedSocket = socket
                    runOnUiThread {
                        Toast.makeText(this, "Connected to ${device.name}", Toast.LENGTH_SHORT).show()
                        statusTextView.text = "Connected to: ${device.name}"
                    }
                } catch (e: IOException) {
                    runOnUiThread {
                        Toast.makeText(this, "Connection failed: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                    try {
                        socket.close()
                    } catch (closeException: IOException) {
                        Log.e("Bluetooth", "Could not close the client socket", closeException)
                    }
                }
            }.start()
        } catch (e: IOException) {
            Log.e("Bluetooth", "Socket's create() method failed", e)
            Toast.makeText(this, "Socket creation failed", Toast.LENGTH_SHORT).show()
        }
    }

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

    private fun requestBluetoothPermissions() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        } else {
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }
        requestPermissionLauncher.launch(permissions)
    }

    private val requestEnableBluetooth = 1  // Add this line at class level

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
        when (requestCode) {
            requestEnableBluetooth -> {
                if (resultCode == RESULT_OK) {
                    startBluetoothScan()
                } else {
                    Toast.makeText(this, "Bluetooth must be enabled to scan for devices", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    private fun startBluetoothScan() {
        if (!checkBluetoothPermissions()) {
            requestBluetoothPermissions()
            return
        }

        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_SCAN
            ) != PackageManager.PERMISSION_GRANTED
        ) {
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

        handler.postDelayed({
            stopBluetoothScan()
        }, 10000)
    }

    private fun stopBluetoothScan() {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_SCAN
            ) != PackageManager.PERMISSION_GRANTED
        ) {
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
        connectedSocket?.close()
    }

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
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.device_item, parent, false)
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