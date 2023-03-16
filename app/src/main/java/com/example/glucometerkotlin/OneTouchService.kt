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
import kotlinx.coroutines.flow.MutableStateFlow

class OneTouchService : Service(), OneTouchCallbacks {


    companion object {
        val measurements = MutableStateFlow<List<OneTouchMeasurement>>(emptyList())
        lateinit var device: BluetoothDevice

        fun run(context: Context) = Intent(context, OneTouchService::class.java)
    }

    lateinit var mManager: OneTouchManager

    private val bluetoothAdapter: BluetoothAdapter by lazy {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothManager.adapter
    }

    private val stateBR = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.STATE_OFF)
            if (state == BluetoothAdapter.STATE_ON) {
                if (!mManager.isConnected) {
                    /* If it was previously connected, reconnect! */
                    log("Reconnecting...")
                    mManager.connect(device).enqueue()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        log("service onCreate")
        mManager = OneTouchManager(this)
        mManager.setGattCallbacks(this)
        registerReceiver(
            stateBR,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        log("service onStartCommand")

        mManager.connect(device)
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

    override fun onBind(p0: Intent?): IBinder? = null


    override fun onDeviceConnecting(device: BluetoothDevice) {

    }

    override fun onDeviceConnected(device: BluetoothDevice) {

    }

    override fun onDeviceDisconnecting(device: BluetoothDevice) {

    }

    override fun onDeviceDisconnected(device: BluetoothDevice) {
        // Note 1: Do not use the device argument here unless you change calling onDeviceDisconnected from the binder above

        // Note 2: if BleManager#shouldAutoConnect() for this device returned true, this callback will be
        // invoked ONLY when user requested disconnection (using Disconnect button). If the device
        // disconnects due to a link loss, the onLinkLossOccurred(BluetoothDevice) method will be called instead.

        log("Stopping service...")
        stopSelf()
    }

    override fun onLinkLossOccurred(device: BluetoothDevice) {

    }

    override fun onServicesDiscovered(device: BluetoothDevice, optionalServicesFound: Boolean) {

    }

    override fun onDeviceReady(device: BluetoothDevice) {

    }

    override fun onBondingRequired(device: BluetoothDevice) {

    }

    override fun onBonded(device: BluetoothDevice) {

    }

    override fun onBondingFailed(device: BluetoothDevice) {

    }

    override fun onError(device: BluetoothDevice, message: String, errorCode: Int) {

    }

    override fun onDeviceNotSupported(device: BluetoothDevice) {

    }

    override fun onMeasurementsReceived(measurements: List<OneTouchMeasurement>) {
        val currList = OneTouchService.measurements.value.toMutableList()
        currList.addAll(measurements)
        OneTouchService.measurements.value = currList
    }
}