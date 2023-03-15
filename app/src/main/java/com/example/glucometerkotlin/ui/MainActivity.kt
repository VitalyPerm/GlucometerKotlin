package com.example.glucometerkotlin.ui

import android.bluetooth.BluetoothDevice
import android.content.*
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.ui.Modifier
import com.example.glucometerkotlin.Constants
import com.example.glucometerkotlin.OneTouchManager
import com.example.glucometerkotlin.OneTouchService
import com.example.glucometerkotlin.entity.OneTouchInfo
import com.example.glucometerkotlin.entity.OneTouchMeasurement
import com.example.glucometerkotlin.ui.theme.GlucometerKotlinTheme
import kotlinx.coroutines.flow.MutableStateFlow
import no.nordicsemi.android.ble.BleManagerCallbacks


fun log(msg: String) {
    Log.d("check___", msg)
}

class MainActivity : ComponentActivity(), BleManagerCallbacks {

    private var deviceName: String? = null

    private var service: OneTouchService? = null

    private val measurementsList = MutableStateFlow<List<OneTouchMeasurement>>(emptyList())

    private var bluetoothDevice: BluetoothDevice? = null

    private var mBatteryCapacity = 0

    private var mSerialNumber: ByteArray? = byteArrayOf()

    private var mManager: OneTouchManager? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder) {
            val mService = (service as OneTouchService.ServiceBinder).service
            val device = mService.btDevice

            onServiceBound(mService)

            /*
            			// Update UI
			deviceName = bleService.getDeviceName();
			deviceNameView.setText(deviceName);
			connectButton.setText(R.string.action_disconnect);
             */

            if (mService.mManager.isConnected) {
                device?.let { onDeviceConnected(it) }
            } else {
                device?.let { onDeviceConnecting(it) }
            }


        }

        override fun onServiceDisconnected(p0: ComponentName?) {
            service = null
            deviceName = null
            bluetoothDevice = null
        }

    }

    private val oneTouchReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Constants.BROADCAST_MEASUREMENT -> {
                    log("measurement received!")
                    onMeasurementsReceived()
                }
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
                    onInformationReceived()
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
                            deviceName = intent.getStringExtra(Constants.EXTRA_DEVICE_NAME)
                            onDeviceConnected(btDevice)
                        }
                        Constants.STATE_DISCONNECTED -> {
                            onDeviceDisconnected(btDevice)
                            deviceName = null
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
            GlucometerKotlinTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colors.background
                ) {

                }
            }
        }

        registerReceiver(oneTouchReceiver, makeOneTouchIntentFilter())
        registerReceiver(commonBroadCastReceiver, makeCommonIntentFilter())
    }

    override fun onStart() {
        super.onStart()
        kotlin.runCatching {
            Intent(this, OneTouchService::class.java).also { intent ->
                bindService(intent, serviceConnection, 0)
            }
        }
    }

    override fun onStop() {
        super.onStop()
        unbindService(serviceConnection)
        service = null
        log("Activity unbound from the service")
        deviceName = null
        bluetoothDevice = null
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(commonBroadCastReceiver)
        unregisterReceiver(oneTouchReceiver)
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
        addAction(Constants.BROADCAST_MEASUREMENT)
        addAction(Constants.BROADCAST_INFORMATION)
        addAction(Constants.BROADCAST_COMM_FAILED)
    }

    private fun onServiceBound(service: OneTouchService) {
        this.service = service
        onMeasurementsReceived()
    }

    private fun onInformationReceived() {
        service?.let { s ->
            val info: OneTouchInfo? = s.getDeviceInfo()
            log("Device information receivec $info")
            // 	batteryLevelView.setText(info.batteryCapacity+"%");
        }
    }

    private fun onMeasurementsReceived() {
        service?.let { s ->
            val newMeasurements = s.getMeasurements()
            val currentMeasurements = measurementsList.value.toMutableList()
            for (i in newMeasurements) {
                log("add measurement $i")
                currentMeasurements.add(i)
            }
            measurementsList.value = currentMeasurements
        }
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