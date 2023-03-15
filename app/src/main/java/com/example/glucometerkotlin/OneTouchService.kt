package com.example.glucometerkotlin

import android.app.Service
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import no.nordicsemi.android.ble.BleManager
import no.nordicsemi.android.ble.BleManagerCallbacks

class OneTouchService : Service() {


    var btDevice: BluetoothDevice? = null

    private val mMeasurements = mutableListOf<OneTouchMeasurement>()

    var bleManager: BleManager<BleManagerCallbacks>? = null


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
}