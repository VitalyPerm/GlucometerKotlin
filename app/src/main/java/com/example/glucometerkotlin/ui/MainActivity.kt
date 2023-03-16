package com.example.glucometerkotlin.ui

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.Button
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import com.example.glucometerkotlin.Constants
import com.example.glucometerkotlin.OneTouchService
import com.example.glucometerkotlin.ui.theme.GlucometerKotlinTheme
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach


fun log(msg: String) {
    Log.d("check___", msg)
}

@SuppressLint("MissingPermission")
class MainActivity : ComponentActivity() {

    private val bluetoothPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) arrayOf(
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.BLUETOOTH_SCAN
    ) else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)


    private var serviceRun = false

    private var foundDeviceName by mutableStateOf("")

    private var permissionGranted = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            val allGranted = it.values.find { granted -> granted.not() } ?: true
            if (allGranted) {
                Toast.makeText(this, "all granted", Toast.LENGTH_SHORT).show()
                permissionGranted = true
            }
        }

    private val bluetoothAdapter: BluetoothAdapter by lazy {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothManager.adapter
    }

    private val searchDeviceFlow by lazy {
        callbackFlow {
            val scanCallback: ScanCallback = object : ScanCallback() {

                override fun onScanResult(callbackType: Int, result: ScanResult?) {
                    super.onScanResult(callbackType, result)
                    val device = result?.device ?: return
                    if (device?.name?.contains(Constants.DEVICE_NAME) == true) {
                        if (device.bondState == BluetoothDevice.BOND_NONE) device.createBond()
                        else {
                            if (device.bondState == BluetoothDevice.BOND_BONDED) trySend(device)
                        }
                    }
                }
            }

            bluetoothAdapter.bluetoothLeScanner?.startScan(
                listOf(),
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_BALANCED).build(),
                scanCallback
            )
            awaitClose { bluetoothAdapter.bluetoothLeScanner?.stopScan(scanCallback) }
        }
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val list by OneTouchService.measurements.collectAsState()
            GlucometerKotlinTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colors.background
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(vertical = 20.dp)
                        ) {
                            list.forEach {
                                Text(text = it.mGlucose.toString())
                                Spacer(modifier = Modifier.height(8.dp))
                            }
                        }
                        Text(
                            text = foundDeviceName,
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = 16.dp),
                            fontSize = 22.sp
                        )

                        Button(
                            onClick = { startService() },
                            enabled = foundDeviceName.isNotBlank(),
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 16.dp)
                        ) {
                            Text(text = "Let's Go!")
                        }
                    }
                }
            }
        }

        permissionGranted = checkPermissionsGranted()
        if (permissionGranted.not()) {
            permissionLauncher.launch(bluetoothPermissions)
        }
    }

    override fun onStart() {
        super.onStart()
        searchDeviceFlow
            .onEach {
                OneTouchService.device = it
                foundDeviceName = it.name
            }
            .launchIn(lifecycleScope)
    }

    private fun startService() {
        if (serviceRun) return
        log("bind service called")
        startService(OneTouchService.run(this))
    }

    private fun stopService() {
        if (serviceRun.not()) return
        log("unbind service called")
        stopService(OneTouchService.run(this))
        serviceRun = false
    }

    override fun onDestroy() {
        super.onDestroy()
        stopService()
    }

    private fun checkPermissionsGranted(): Boolean {
        bluetoothPermissions.forEach { permission ->
            if (ActivityCompat.checkSelfPermission(
                    application, permission
                ) != PackageManager.PERMISSION_GRANTED
            ) return false
        }
        return true
    }

}