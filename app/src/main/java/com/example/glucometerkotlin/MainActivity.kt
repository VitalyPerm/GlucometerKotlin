package com.example.glucometerkotlin

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
import com.example.glucometerkotlin.ui.theme.GlucometerKotlinTheme
import kotlinx.coroutines.flow.MutableStateFlow


fun log(msg: String) {
    Log.d("check___", msg)
}

class MainActivity : ComponentActivity() {

    private var deviceName: String? = null

    private var service: OneTouchService? = null

    private val measurementsList = MutableStateFlow<List<OneTouchMeasurement>>(emptyList())

    private var bluetoothDevice: BluetoothDevice? = null

    /*
        private val glucometerServiceConnection: ServiceConnection = object : ServiceConnection {
        override fun onServiceDisconnected(name: ComponentName) {}
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            device?.let { btDevice ->
                (service as GlucometerService.GlucometerBinder).service.connectDevice(btDevice)
            }
        }
    }
     */

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

            if (mService.bleManager?.isConnected == true) {
                onDeviceConnected(device)
            } else {
                onDeviceConnecting(device)
            }


        }

        override fun onServiceDisconnected(p0: ComponentName?) {
            service = null
            deviceName = null
            bluetoothDevice = null
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
                        //onDeviceNotSupported(bluetoothDevice)
                        // метод показывает тост что девайс не поддерживается
                    }
                }
                Constants.BROADCAST_DEVICE_READY -> {
                    onDeviceReady(bluetoothDevice)
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
                    Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
                }
            }
        }

    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            GlucometerKotlinTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colors.background
                ) {

                }
            }
        }
        registerReceiver(commonBroadCastReceiver, makeIntentFilter())
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(commonBroadCastReceiver)
    }

    private fun makeIntentFilter(): IntentFilter {
        return IntentFilter().apply {
            addAction(Constants.BROADCAST_CONNECTION_STATE)
            addAction(Constants.BROADCAST_SERVICES_DISCOVERED)
            addAction(Constants.BROADCAST_DEVICE_READY)
            addAction(Constants.BROADCAST_BOND_STATE)
            addAction(Constants.BROADCAST_ERROR)
        }
    }

    private fun onServiceBound(service: OneTouchService) {
        this.service = service
        onMeasurementsReceived()
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

    private fun onDeviceReady(bluetoothDevice: BluetoothDevice?) {
        /*
        		progressBar.setProgress(0);
		statusView.setBackground(ContextCompat.getDrawable(this,drawable.button_onoff_indicator_on));
         */
    }

    private fun onDeviceConnecting(bluetoothDevice: BluetoothDevice?) {
        /*
        		deviceNameView.setText(deviceName != null ? deviceName : getString(R.string.not_available));
		connectButton.setText(R.string.action_connecting);
         */
    }

    private fun onDeviceConnected(bluetoothDevice: BluetoothDevice?) {
        /*
         deviceNameView.setText(deviceName);
         connectButton.setText(R.string.action_disconnect);
         */
    }

    private fun onDeviceDisconnected(bluetoothDevice: BluetoothDevice) {
        /*
        		connectButton.setText(R.string.action_connect);
		deviceNameView.setText(getDefaultDeviceName());
         */
        kotlin.runCatching {
            unbindService(serviceConnection)
            service = null
            deviceName = null
            this.bluetoothDevice = null
        }
    }

    private fun onLinkLossOccurred(bluetoothDevice: BluetoothDevice?) {
        /*
        		runOnUiThread(() -> batteryLevelView.setText(""));
		statusView.setBackground(ContextCompat.getDrawable(this,drawable.button_onoff_indicator_off));
         */
    }

    private fun onDeviceDisconnecting(bluetoothDevice: BluetoothDevice?) {
        /*
        connectButton.setText(R.string.action_disconnecting);
         */
    }

}