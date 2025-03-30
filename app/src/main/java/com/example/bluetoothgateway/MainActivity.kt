package com.example.bluetoothgateway

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
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
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    private lateinit var bluetoothAdapter: BluetoothAdapter
    private lateinit var statusTextView: TextView
    private lateinit var scanButton: Button
    private val discoveredDevices = mutableSetOf<String>() // Track discovered devices by MAC address
    private val handler = Handler(Looper.getMainLooper())
    private var isScanning = false

    private val requestEnableBluetooth = 1

    // Permission launcher
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

    // BroadcastReceiver for Bluetooth discovery
    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action
            if (BluetoothDevice.ACTION_FOUND == action) {
                val device: BluetoothDevice = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)!!

                // Retrieve the device name from the cache
                val deviceName = device.name ?: "Unknown Device"
                val deviceAddress = device.address // MAC address

                // Check if we have the required permission
                if (ActivityCompat.checkSelfPermission(
                        this@MainActivity,
                        Manifest.permission.BLUETOOTH_CONNECT
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    // Log the device name and address
                    Log.d("Bluetooth", "Found device: $deviceName ($deviceAddress)")

                    // Update UI on the main thread
                    runOnUiThread {
                        statusTextView.append("\n$deviceName ($deviceAddress)")
                    }
                } else {
                    // If permission isn't granted, notify the user
                    Log.e("Bluetooth", "Bluetooth permission not granted")
                    Toast.makeText(this@MainActivity, "Permission denied for Bluetooth access", Toast.LENGTH_SHORT).show()
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

        // Clear previous results
        discoveredDevices.clear()
        statusTextView.text = "Starting scan..."

        if (!bluetoothAdapter.startDiscovery()) {
            Toast.makeText(this, "Failed to start discovery", Toast.LENGTH_SHORT).show()
        }

        // Stop discovery after 10 seconds
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

        if (bluetoothAdapter.isDiscovering) {
            bluetoothAdapter.cancelDiscovery()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Unregister receiver and clean up
        unregisterReceiver(bluetoothReceiver)
        handler.removeCallbacksAndMessages(null)
        stopBluetoothScan()
    }}