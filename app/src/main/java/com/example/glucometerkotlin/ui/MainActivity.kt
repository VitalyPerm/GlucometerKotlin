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
import android.os.IBinder
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
import com.example.glucometerkotlin.entity.OneTouchInfo
import com.example.glucometerkotlin.entity.OneTouchMeasurement
import com.example.glucometerkotlin.ui.theme.GlucometerKotlinTheme
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import no.nordicsemi.android.ble.BleManagerCallbacks


fun log(msg: String) {
    Log.d("check___", msg)
}

@SuppressLint("MissingPermission")
class MainActivity : ComponentActivity(), BleManagerCallbacks {

    private val bluetoothPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) arrayOf(
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.BLUETOOTH_SCAN
    ) else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

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
                            if(device.bondState == BluetoothDevice.BOND_BONDED) trySend(device)
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

    private var mBound = false

    private var foundDeviceName by mutableStateOf("")

    private var bluetoothDevice: BluetoothDevice? = null

    private var mBatteryCapacity = 0

    private var mSerialNumber: ByteArray? = byteArrayOf()

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder) {
            log("onServiceConnected")
            val mService = (service as OneTouchService.ServiceBinder).service
            onServiceBound(mService)
            if (mService.mManager.isConnected) {
                bluetoothDevice?.let { onDeviceConnected(it) }
            } else {
                bluetoothDevice?.let { onDeviceConnecting(it) }
            }


        }

        override fun onServiceDisconnected(p0: ComponentName?) {
            bluetoothDevice = null
        }

    }

    private val oneTouchReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Constants.BROADCAST_COUNTDOWN -> {
                    val count = intent.getIntExtra(Constants.EXTRA_COUNTDOWN, 0)
                    log("countdown received $count")
                    //    onCountdownReceived(count)
                    // какая то хрен с анимацией
                }
                Constants.BROADCAST_INFORMATION -> {
                    log("information received!")
                    mBatteryCapacity = intent.getIntExtra(Constants.EXTRA_BATTERY_CAPACITY, 0)
                    mSerialNumber = intent.getByteArrayExtra(Constants.EXTRA_SERIAL_NUMBER)
                }
                Constants.BROADCAST_COMM_FAILED -> {
                    log("Broadcast communication failed received!")
                    val message = intent.getStringExtra(Constants.EXTRA_ERROR_MESSAGE)
                    showToast("Error - $message")
                }
            }
        }

    }

    private val commonBroadCastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val btDevice: BluetoothDevice =
                intent.getParcelableExtra(Constants.EXTRA_DEVICE) ?: return
            when (intent.action) {
                Constants.BROADCAST_CONNECTION_STATE -> {
                    val state = intent.getIntExtra(
                        Constants.EXTRA_CONNECTION_STATE,
                        Constants.STATE_DISCONNECTED
                    )
                    when (state) {
                        Constants.STATE_CONNECTED -> {
                            onDeviceConnected(btDevice)
                        }
                        Constants.STATE_DISCONNECTED -> {
                            onDeviceDisconnected(btDevice)
                        }
                        Constants.STATE_LINK_LOSS -> {
                            onLinkLossOccurred(btDevice)
                        }
                        Constants.STATE_CONNECTING -> {
                            onDeviceConnecting(btDevice)
                        }
                        Constants.STATE_DISCONNECTING -> {
                            onDeviceDisconnecting(btDevice)
                        }
                    }
                }
                Constants.BROADCAST_SERVICES_DISCOVERED -> {
                    val primaryService =
                        intent.getBooleanExtra(Constants.EXTRA_SERVICE_PRIMARY, false)
                    val secondaryService =
                        intent.getBooleanExtra(Constants.EXTRA_SERVICE_SECONDARY, false)
                    if (primaryService) {
                        //    onServicesDiscovered(bluetoothDevice, secondaryService)
                        // а метод пустой
                    } else {
                        showToast("Device not supported")
                    }
                }
                Constants.BROADCAST_DEVICE_READY -> {
                    bluetoothDevice?.let { onDeviceReady(it) }
                }
                Constants.BROADCAST_BOND_STATE -> {
                    val state =
                        intent.getIntExtra(Constants.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
                    when (state) {
                        BluetoothDevice.BOND_BONDING -> {
                            //   onBondingRequired(bluetoothDevice)
                            // empty default implementation
                        }
                        BluetoothDevice.BOND_BONDED -> {
                            // onBonded(bluetoothDevice)
                            // empty default implementation
                        }
                    }
                }
                Constants.BROADCAST_ERROR -> {
                    val message = intent.getStringExtra(Constants.EXTRA_ERROR_MESSAGE)
                    val errorCode = intent.getIntExtra(Constants.EXTRA_ERROR_CODE, 0)
                    val msg = "Ошибка! - $message код - $errorCode"
                    log(msg)
                    showToast(msg)
                }
            }
        }

    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // todo grant all permissions
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
                            onClick = { bindService() },
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

        registerReceiver(oneTouchReceiver, makeOneTouchIntentFilter())
        registerReceiver(commonBroadCastReceiver, makeCommonIntentFilter())
        permissionGranted = checkPermissionsGranted()
        if (permissionGranted.not()) {
            permissionLauncher.launch(bluetoothPermissions)
        }
    }

    override fun onStart() {
        super.onStart()
        searchDeviceFlow
            .onEach {
                bluetoothDevice = it
                foundDeviceName = it.name
            }
            .launchIn(lifecycleScope)
    }

    private fun bindService() {
        if (mBound) return
        log("bind service called")
        val i = Intent(this, OneTouchService::class.java).apply {
            putExtra(Constants.EXTRA_DEVICE_ADDRESS, bluetoothDevice?.address)
        }
        startService(i)
        bindService(i, serviceConnection, BIND_AUTO_CREATE)
    }

    private fun unbindService() {
        if (mBound.not()) return
        log("unbind service called")
        unbindService(serviceConnection)
        mBound = false
    }


    override fun onStop() {
        super.onStop()
        if (permissionGranted) {
            unbindService()
            log("Activity unbound from the service")
            bluetoothDevice = null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(commonBroadCastReceiver)
        unregisterReceiver(oneTouchReceiver)
        unbindService()
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

    private fun makeCommonIntentFilter() = IntentFilter().apply {
        addAction(Constants.BROADCAST_CONNECTION_STATE)
        addAction(Constants.BROADCAST_SERVICES_DISCOVERED)
        addAction(Constants.BROADCAST_DEVICE_READY)
        addAction(Constants.BROADCAST_BOND_STATE)
        addAction(Constants.BROADCAST_ERROR)
    }


    private fun makeOneTouchIntentFilter() = IntentFilter().apply {
        addAction(Constants.BROADCAST_COUNTDOWN)
        addAction(Constants.BROADCAST_INFORMATION)
        addAction(Constants.BROADCAST_COMM_FAILED)
    }

    private fun onServiceBound(service: OneTouchService) {
        mBound = true
    }

    override fun onDeviceConnecting(device: BluetoothDevice) {
        /*
                     deviceNameView.setText(deviceName != null ? deviceName : getString(R.string.not_available));
             connectButton.setText(R.string.action_connecting);
              */
    }

    override fun onDeviceConnected(device: BluetoothDevice) {
        /*
                deviceNameView.setText(deviceName);
                connectButton.setText(R.string.action_disconnect);
                */
    }

    override fun onDeviceDisconnecting(device: BluetoothDevice) {
        /*
        connectButton.setText(R.string.action_disconnecting);
         */
    }

    override fun onDeviceDisconnected(device: BluetoothDevice) {
        /*
                 connectButton.setText(R.string.action_connect);
         deviceNameView.setText(getDefaultDeviceName());
          */
    }


    override fun onLinkLossOccurred(device: BluetoothDevice) {
        /*
              runOnUiThread(() -> batteryLevelView.setText(""));
      statusView.setBackground(ContextCompat.getDrawable(this,drawable.button_onoff_indicator_off));
       */
    }

    override fun onServicesDiscovered(device: BluetoothDevice, optionalServicesFound: Boolean) {
        TODO("Not yet implemented")
    }

    override fun onDeviceReady(device: BluetoothDevice) {
        /*
                   progressBar.setProgress(0);
           statusView.setBackground(ContextCompat.getDrawable(this,drawable.button_onoff_indicator_on));
            */
    }

    override fun onBondingRequired(device: BluetoothDevice) {
        TODO("Not yet implemented")
    }

    override fun onBonded(device: BluetoothDevice) {
        TODO("Not yet implemented")
    }

    override fun onBondingFailed(device: BluetoothDevice) {
        TODO("Not yet implemented")
    }

    override fun onError(device: BluetoothDevice, message: String, errorCode: Int) {
        TODO("Not yet implemented")
    }

    override fun onDeviceNotSupported(device: BluetoothDevice) {
        TODO("Not yet implemented")
    }

    private fun showToast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

}