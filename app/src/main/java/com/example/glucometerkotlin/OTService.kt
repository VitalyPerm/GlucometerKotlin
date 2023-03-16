package com.example.glucometerkotlin

import android.annotation.SuppressLint
import android.app.Service
import android.bluetooth.*
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.example.glucometerkotlin.entity.OneTouchMeasurement
import com.example.glucometerkotlin.ui.lod
import com.example.glucometerkotlin.ui.log
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.*

@SuppressLint("MissingPermission")
class OTService : Service() {

    companion object {
        val measurements = MutableStateFlow<List<OneTouchMeasurement>>(emptyList())
        lateinit var device: BluetoothDevice

        fun run(context: Context) = Intent(context, OneTouchService::class.java)
    }

    private val bluetoothAdapter: BluetoothAdapter by lazy {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothManager.adapter
    }

    private lateinit var bluetoothGatt: BluetoothGatt

    private val gattCallback: BluetoothGattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            super.onConnectionStateChange(gatt, status, newState)
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> gatt.discoverServices()

                BluetoothProfile.STATE_DISCONNECTED -> {
                    bluetoothGatt.close()
                    bluetoothGatt.disconnect()
                }
                else -> {}
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            super.onServicesDiscovered(gatt, status)
            val service = gatt.getService(Constants.ONETOUCH_SERVICE_UUID) ?: return
            val txCharacteristic =
                service.getCharacteristic(Constants.ONETOUCH_TX_CHARACTERISTIC_UUID) ?: return
            gatt.setCharacteristicNotification(txCharacteristic, true)
            // setCharacteristicNotification будет вызывать onCharacteristicChanged
            // вот тут хз надо ли
//            if (manager.tx == uuid) {
//                val descriptor =
//                    txCharacteristic.getDescriptor(manager.tx)
//                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
//                bluetoothGatt.writeDescriptor(descriptor)
//            }
        }

        override fun onDescriptorRead(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
            value: ByteArray
        ) {
            super.onDescriptorRead(gatt, descriptor, status, value)
            lod("onDescriptorRead")
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt?,
            descriptor: BluetoothGattDescriptor?,
            status: Int
        ) {
            super.onDescriptorWrite(gatt, descriptor, status)
            lod("onDescriptorWrite")
        }


        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            super.onCharacteristicChanged(gatt, characteristic, value)
            lod("onCharacteristicChanged")
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?,
            status: Int
        ) {
            super.onCharacteristicWrite(gatt, characteristic, status)
            lod("onCharacteristicWrite")
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            super.onCharacteristicRead(gatt, characteristic, value, status)
            lod("onCharacteristicRead")
        }
    }

    override fun onBind(p0: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        log("service onStartCommand")
        device.connectGatt(this, false, gattCallback)
        return START_NOT_STICKY
    }


    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        stopSelf()
    }

    private fun onMeasurementsReceived(measurements: List<OneTouchMeasurement>) {
        val currList = OneTouchService.measurements.value.toMutableList()
        currList.addAll(measurements)
        OneTouchService.measurements.value = currList
    }


}