package com.example.glucometerkotlin

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.content.Context
import com.example.glucometerkotlin.entity.OneTouchMeasurement
import com.example.glucometerkotlin.interfaces.OneTouchCallbacks
import com.example.glucometerkotlin.interfaces.ProtocolCallBacks
import com.example.glucometerkotlin.ui.log
import no.nordicsemi.android.ble.BleManager
import no.nordicsemi.android.ble.data.Data

class OneTouchManager(context: Context) : BleManager<OneTouchCallbacks>(context),
    ProtocolCallBacks {

    private var mRxCharacteristic: BluetoothGattCharacteristic? = null
    private var mTxCharacteristic: BluetoothGattCharacteristic? = null

    private val mProtocol = Protocol(this, 20)


    override fun getGattCallback(): BleManagerGattCallback {
        return object : BleManagerGattCallback() {
            override fun isRequiredServiceSupported(gatt: BluetoothGatt): Boolean {
                val service = gatt.getService(Constants.ONETOUCH_SERVICE_UUID)
                service?.let { s ->
                    mRxCharacteristic =
                        s.getCharacteristic(Constants.ONETOUCH_RX_CHARACTERISTIC_UUID)
                    mTxCharacteristic =
                        s.getCharacteristic(Constants.ONETOUCH_TX_CHARACTERISTIC_UUID)
                }
                var writeRequest = false
                var writeCommand = false
                mRxCharacteristic?.also { rx ->
                    val rxProperties = rx.properties
                    writeRequest = rxProperties and BluetoothGattCharacteristic.PROPERTY_WRITE > 0
                    writeCommand =
                        rxProperties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE > 0
                    rx.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                }
                return (mRxCharacteristic != null) && (mTxCharacteristic != null) && (writeCommand || writeRequest)
            }

            override fun onDeviceDisconnected() {
                mProtocol.disconnect()
                mRxCharacteristic = null
                mTxCharacteristic = null
            }

            override fun onDeviceReady() {
                super.onDeviceReady()
                mProtocol.connect()
            }

            override fun initialize() {
                super.initialize()
                if (isConnected) {
                    requestMtu(20 + 3)
                        .with { device: BluetoothDevice?, mtu: Int ->
                            log("MTU set to $mtu")
                        }
                        .fail { device: BluetoothDevice?, status: Int ->
                            log("MTU change failed.")
                        }
                        .enqueue()

                    /* Register callback to get data from the device. */
                    setNotificationCallback(mTxCharacteristic)
                        .with { device: BluetoothDevice?, data: Data ->
                            log("BLE data received: $data")
                            mProtocol.onDataReceivedBA(data.value!!)
                        }
                    enableNotifications(mTxCharacteristic)
                        .done { device: BluetoothDevice? ->
                            log("Onetouch TX characteristic  notifications enabled")
                            mProtocol.getTime()
                        }
                        .fail { device: BluetoothDevice?, status: Int ->
                            log("Onetouch TX characteristic  notifications not enabled")
                        }
                        .enqueue()
                }
            }

        }
    }

    override fun onProtocolError(message: String) {
        mCallbacks.onProtocolError(message)
    }

    override fun sendData(bytes: ByteArray?) {
        // Are we connected?
        if (mRxCharacteristic == null) {
            log("Tried to send data but mRxCharacteristic was null: " + bytes.toString())
            return
        }

        if (bytes != null && bytes.isNotEmpty()) {
            writeCharacteristic(mRxCharacteristic, bytes)
                .with { device: BluetoothDevice?, data: Data ->
                    log("$data sent")
                } //.split()
                .enqueue()
        }
    }

    override fun onMeasurementsReceived(measurements: List<OneTouchMeasurement>) {
        mCallbacks.onMeasurementsReceived(measurements)
    }
}