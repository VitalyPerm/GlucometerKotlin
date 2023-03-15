package com.example.glucometerkotlin

import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.example.glucometerkotlin.entity.OneTouchInfo
import com.example.glucometerkotlin.entity.OneTouchMeasurement
import com.example.glucometerkotlin.interfaces.OneTouchCallbacks
import com.example.glucometerkotlin.ui.log

class OneTouchService : Service(), OneTouchCallbacks {

    private var deviceName = ""

    private lateinit var bluetoothDevice: BluetoothDevice

    var mManager = OneTouchManager(this)

    private lateinit var handler: Handler

    private val bluetoothAdapter: BluetoothAdapter by lazy {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothManager.adapter
    }

    private val stateBR = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.STATE_OFF)
            if (state == BluetoothAdapter.STATE_ON) {
                onBluetoothEnabled()
            }
        }
    }


    var btDevice: BluetoothDevice? = null

    private val mMeasurements = mutableListOf<OneTouchMeasurement>()

    private var oneTouchInfo: OneTouchInfo? = null

    override fun onCreate() {
        super.onCreate()
        handler = Handler(Looper.getMainLooper())
        mManager.setGattCallbacks(this)
        registerReceiver(
            stateBR,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || !intent.hasExtra(Constants.EXTRA_DEVICE_ADDRESS))
            throw UnsupportedOperationException("No device address at EXTRA_DEVICE_ADDRESS key")
        deviceName = intent.getStringExtra(Constants.EXTRA_DEVICE_NAME) ?: ""
        log("Service started")
        val deviceAddress = intent.getStringExtra(Constants.EXTRA_DEVICE_ADDRESS)
        bluetoothDevice = bluetoothAdapter.getRemoteDevice(deviceAddress)

        mManager.connect(bluetoothDevice)
            .useAutoConnect(false)
            .retry(3, 100)
            .enqueue()
        return START_REDELIVER_INTENT
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(stateBR)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        stopSelf()
    }

    override fun onBind(p0: Intent?): IBinder = ServiceBinder()

    inner class ServiceBinder : Binder() {
        val service: OneTouchService
            get() = this@OneTouchService
    }

    fun getMeasurements(): List<OneTouchMeasurement> {
        val list = mMeasurements
        mMeasurements.clear()
        return list
    }


    fun getDeviceInfo() = oneTouchInfo

    override fun onDeviceConnecting(device: BluetoothDevice) {
        Intent(Constants.BROADCAST_CONNECTION_STATE).apply {
            putExtra(Constants.EXTRA_DEVICE, bluetoothDevice)
            putExtra(Constants.EXTRA_CONNECTION_STATE, Constants.STATE_CONNECTING)
            sendBroadcast(this)
        }
    }

    override fun onDeviceConnected(device: BluetoothDevice) {
        Intent(Constants.BROADCAST_CONNECTION_STATE).apply {
            putExtra(Constants.EXTRA_CONNECTION_STATE, Constants.STATE_CONNECTED)
            putExtra(Constants.EXTRA_DEVICE, bluetoothDevice)
            putExtra(Constants.EXTRA_DEVICE_NAME, deviceName)
            sendBroadcast(this)
        }
    }

    override fun onDeviceDisconnecting(device: BluetoothDevice) {
        Intent(Constants.BROADCAST_CONNECTION_STATE).apply {
            putExtra(Constants.EXTRA_DEVICE, bluetoothDevice)
            putExtra(Constants.EXTRA_CONNECTION_STATE, Constants.STATE_DISCONNECTING)
            sendBroadcast(this)
        }
    }

    override fun onDeviceDisconnected(device: BluetoothDevice) {
        // Note 1: Do not use the device argument here unless you change calling onDeviceDisconnected from the binder above

        // Note 2: if BleManager#shouldAutoConnect() for this device returned true, this callback will be
        // invoked ONLY when user requested disconnection (using Disconnect button). If the device
        // disconnects due to a link loss, the onLinkLossOccurred(BluetoothDevice) method will be called instead.
        Intent(Constants.BROADCAST_CONNECTION_STATE).apply {
            putExtra(Constants.EXTRA_DEVICE, bluetoothDevice)
            putExtra(Constants.EXTRA_CONNECTION_STATE, Constants.STATE_DISCONNECTED)
            sendBroadcast(this)
        }
        log("Stopping service...")
        stopSelf()
    }

    override fun onLinkLossOccurred(device: BluetoothDevice) {
        TODO("Not yet implemented")
    }

    override fun onServicesDiscovered(device: BluetoothDevice, optionalServicesFound: Boolean) {
        TODO("Not yet implemented")
    }

    override fun onDeviceReady(device: BluetoothDevice) {
        TODO("Not yet implemented")
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

    override fun onMeasurementsReceived(measurements: List<OneTouchMeasurement>) {
        TODO("Not yet implemented")
    }

    override fun onProtocolError(message: String) {
        TODO("Not yet implemented")
    }


    fun onBluetoothEnabled() {
        /* Get bluetooth device. */
        if (!mManager.isConnected) {
            /* If it was previously connected, reconnect! */
            log("Reconnecting...")
            mManager.connect(bluetoothDevice).enqueue()
        }
    }
}